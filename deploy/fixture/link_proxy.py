#!/usr/bin/env python3
"""A task-owned, loopback TCP link shaper for native streaming acceptance.

The target must identify itself as a disposable WatchMyMedia fixture before
this process listens. Send {"bps": 5000000} JSON lines on stdin to change the
downlink rate; -1 removes the limit, 0 holds response bytes until changed.
"""
import argparse
import json
import socket
import socketserver
import sys
import threading
import time
import urllib.parse
import urllib.error
import urllib.request

MARKERS = {"streaming-fixture-v1", "dvr-fixture-v1"}
BURST_BYTES = 32 * 1024
CHUNK_BYTES = 16 * 1024


def verified_target(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "::1"}
            or not parsed.port or parsed.path not in {"", "/"} or parsed.query
            or parsed.fragment or parsed.username or parsed.password):
        raise ValueError("Use an HTTP fixture on a literal loopback host and port")
    base = value.rstrip("/")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(base + "/_fixture/status", timeout=5) as response:
            identity = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code != 503:
            raise
        # Apple's channel-only fixture has no DVR marker route. Its server
        # identity is pinned by the native integration tests too.
        with opener.open(base + "/api/server", timeout=5) as response:
            server = json.load(response)
        if server.get("version") != "live-tv-fixture" or server.get("name") != "Meadow Live TV fixture":
            raise ValueError("Target did not identify as the Apple Live TV fixture") from None
        return parsed.hostname, parsed.port
    marker = identity.get("fixture")
    if (marker not in MARKERS or
            (marker == "streaming-fixture-v1" and identity.get("baseUrl") != base) or
            (marker == "dvr-fixture-v1" and not identity.get("channels"))):
        raise ValueError("Target did not identify as a disposable fixture")
    return parsed.hostname, parsed.port


class Downlink:
    def __init__(self, bps):
        self.condition = threading.Condition()
        self.bps = bps
        self.credit = float(BURST_BYTES if bps < 0 else 0)
        self.last = time.monotonic()
        self.closed = False
        self.delivered = 0

    def set_rate(self, bps):
        if bps < -1:
            raise ValueError("bps must be -1 (unlimited), 0 (held), or a positive bit rate")
        with self.condition:
            self.bps = bps
            self.credit = float(BURST_BYTES if bps < 0 else 0)
            self.last = time.monotonic()
            self.condition.notify_all()

    def take(self, wanted):
        with self.condition:
            while not self.closed:
                if self.bps < 0:
                    self.delivered += wanted
                    return wanted
                now = time.monotonic()
                self.credit = min(float(BURST_BYTES), self.credit + (now - self.last) * self.bps / 8)
                self.last = now
                target = min(wanted, 4096)
                allowed = min(wanted, int(self.credit)) if self.credit >= target else 0
                if allowed:
                    self.credit -= allowed
                    self.delivered += allowed
                    return allowed
                wait = min(0.2, max(0.001, (target - self.credit) * 8 / self.bps)) if self.bps else 1
                self.condition.wait(timeout=wait)
            return 0

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()


class Proxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, target, limiter):
        self.target = target
        self.limiter = limiter
        super().__init__(("127.0.0.1", 0), Relay)


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(self.server.target, timeout=5) as upstream:
                upstream.settimeout(5)
                self.request.settimeout(5)
                sent = threading.Thread(target=self.copy, args=(self.request, upstream, False), daemon=True)
                sent.start()
                self.copy(upstream, self.request, True)
                try:
                    upstream.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                sent.join(timeout=5)
        except OSError:
            pass

    def copy(self, source, destination, downlink):
        try:
            while not self.server.limiter.closed:
                try:
                    chunk = source.recv(CHUNK_BYTES)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                if downlink:
                    while chunk:
                        count = self.server.limiter.take(len(chunk))
                        if not count:
                            return
                        destination.sendall(chunk[:count])
                        chunk = chunk[count:]
                else:
                    destination.sendall(chunk)
        except OSError:
            pass
        finally:
            try:
                destination.shutdown(socket.SHUT_WR)
            except OSError:
                pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", help="Verified http://127.0.0.1:<fixture-port>")
    parser.add_argument("--initial-bps", type=int, default=-1)
    args = parser.parse_args(argv)
    if args.initial_bps < -1:
        parser.error("initial rate must be at least -1")
    target = verified_target(args.target)
    limiter = Downlink(args.initial_bps)
    with Proxy(target, limiter) as proxy:
        thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        thread.start()
        print(json.dumps({"proxy": f"http://127.0.0.1:{proxy.server_address[1]}",
                          "targetFixture": args.target, "bps": args.initial_bps}), flush=True)
        try:
            for line in sys.stdin:
                try:
                    command = json.loads(line)
                    if command == "stop":
                        break
                    if type(command) is not dict or type(command.get("bps")) is not int:
                        raise ValueError("send a JSON object with an integer bps")
                    limiter.set_rate(command["bps"])
                    print(json.dumps({"bps": limiter.bps, "deliveredBytes": limiter.delivered}), flush=True)
                except (ValueError, json.JSONDecodeError) as error:
                    print(json.dumps({"error": str(error)}), flush=True)
        except KeyboardInterrupt:
            pass
        finally:
            limiter.close()
            proxy.shutdown()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
