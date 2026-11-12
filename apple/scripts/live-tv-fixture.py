#!/usr/bin/env python3
"""Start and configure this process's fictional Live TV fixture, never a running server."""

import argparse
import json
import os
from pathlib import Path
import queue
import re
import signal
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid


def available_port(port):
    if not 1024 <= port <= 65535:
        raise ValueError("Choose an unprivileged port from 1024 through 65535.")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        try:
            listener.bind(("127.0.0.1", port))
        except OSError as error:
            raise RuntimeError(f"Port {port} is occupied; no server was contacted or changed.") from error


def configure(child, port, listings=False):
    """Called only after the ready message read from our child's private pipe."""
    base = f"http://127.0.0.1:{port}"
    token = None
    # Avoid any HTTP proxy configuration when talking to our loopback-only child.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(method, path, body=None):
        if child.poll() is not None:
            raise RuntimeError("The owned fixture exited; setup was stopped.")
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(base + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try:
            with opener.open(request, timeout=15) as response:
                data = response.read()
                return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            # Do not echo request bodies, bearer tokens or server responses.
            raise RuntimeError(f"Fixture setup failed at {method} {path} (HTTP {error.code}).") from None
        except urllib.error.URLError:
            raise RuntimeError(f"The owned fixture could not answer {method} {path}.") from None

    info = call("GET", "/api/server")
    if info.get("version") != "live-tv-fixture" or not info.get("setupRequired"):
        raise RuntimeError("The owned fixture did not report a fresh synthetic server; setup was refused.")
    grant = call("POST", "/api/setup", {
        "name": "admin", "password": "fixture-admin-1",
        "client": {"deviceId": str(uuid.uuid4()), "name": "Synthetic fixture setup",
                   "product": "WatchMyMedia fixture", "platform": "script", "version": "1"},
    })
    token = grant.get("token")
    if not token:
        raise RuntimeError("The owned fixture did not issue the setup credential.")
    call("POST", "/api/users", {"name": "viewer", "password": "fixture-viewer-1", "isAdmin": False})
    call("POST", "/api/settings/live-tv/sources", {"address": "tuner.test", "name": "Meadow antenna"})
    call("PUT", "/api/settings/live-tv", {"access": "everyone"})
    capabilities = call("GET", "/api/live-tv/capabilities")
    channels = call("GET", "/api/live-tv/channels")["items"]
    if not all(capabilities.get(key) for key in ("enabled", "allowed", "playback")) or not channels:
        raise RuntimeError("The synthetic fixture did not enable playable channels.")
    if listings:
        # The fixture's own fake SiliconDust and picture host; nothing leaves the Mac.
        call("PUT", "/api/settings/live-tv/guide", {"enabled": True, "tmdb": False})
        call("POST", "/api/settings/live-tv/guide/refresh")
        deadline = time.monotonic() + 60
        while not call("GET", "/api/live-tv/capabilities").get("guide"):
            if time.monotonic() > deadline:
                raise RuntimeError("The synthetic fixture's listings did not arrive within a minute.")
            time.sleep(0.5)
    else:
        # cmd/livetvfixture seeds a guide by default. The channel-only UI
        # checks need it explicitly off, including after an initial fetch.
        call("PUT", "/api/settings/live-tv/guide", {"enabled": False, "tmdb": False})
        if call("GET", "/api/live-tv/capabilities").get("guide"):
            raise RuntimeError("The channel-only synthetic fixture still advertises listings.")
    return len(channels)


def group_alive(pgid):
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False


def stop_owned(child):
    # The Go harness handles SIGINT and removes its temporary database in defers.
    # go run and its binary share this new process group; no unrelated server is signalled.
    if not group_alive(child.pid):
        child.wait()
        return
    os.killpg(child.pid, signal.SIGINT)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        child.poll()  # Reap go run if it exits before the fixture finishes its cleanup.
        if not group_alive(child.pid):
            child.wait()
            return
        time.sleep(0.1)
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(child.pid, sig)
        except ProcessLookupError:
            break
        time.sleep(0.2)
    child.wait()
    print("The owned fixture required forced shutdown; check its temporary cleanup.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=28281)
    parser.add_argument("--smoke", action="store_true", help="Verify setup, then stop and clean up.")
    parser.add_argument("--listings", action="store_true", help="Turn on programme listings from the fixture's fake SiliconDust.")
    parser.add_argument("--share-sources", action="store_true", help="Enable API-level-25 Live TV Automatic on this fictional tuner.")
    args = parser.parse_args()
    available_port(args.port)
    if not shutil.which("go") or not shutil.which("ffmpeg"):
        raise RuntimeError("Install Go and ffmpeg before starting the synthetic fixture.")
    root = Path(__file__).resolve().parents[2]
    command = ["go", "run", "./cmd/livetvfixture", "-port", str(args.port)]
    if args.share_sources:
        command.append("-share-sources")
    child = subprocess.Popen(command,
                             cwd=root / "server", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, bufsize=1, start_new_session=True)
    lines = queue.Queue()
    ready = threading.Event()

    def read_output():
        for line in child.stdout:
            if not ready.is_set():
                lines.put(line)
        if not ready.is_set():
            lines.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    data_dir = None
    try:
        deadline = time.monotonic() + 180
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError("The owned fixture did not become ready within three minutes.")
            try:
                line = lines.get(timeout=min(remaining, 1))
            except queue.Empty:
                if child.poll() is not None:
                    raise RuntimeError("The owned fixture exited before it was ready. Check Go and ffmpeg are installed.")
                continue
            if line is None:
                raise RuntimeError("The owned fixture exited before it was ready. Check Go and ffmpeg are installed.")
            if "fictional live TV fixture ready" in line and f"url=http://127.0.0.1:{args.port}" in line:
                match = re.search(r"\bdata=([^\s]+)", line)
                if match:
                    data_dir = Path(match.group(1).strip('"'))
                ready.set()
                break
        count = configure(child, args.port, listings=args.listings)
        print(f"Synthetic Live TV ready at http://127.0.0.1:{args.port} ({count} channels).", flush=True)
        print("Fictional admin and viewer accounts are configured; credentials are in apple/README.md.", flush=True)
        if not args.smoke:
            print("Press Ctrl-C to stop this fixture and remove its temporary database.", flush=True)
            while child.poll() is None:
                time.sleep(0.5)
    finally:
        stop_owned(child)
        if data_dir is not None and data_dir.exists():
            print("The fixture's temporary database was not removed; inspect its shutdown before rerunning.", file=sys.stderr)
        elif data_dir is not None:
            print("Owned fixture stopped; its temporary database was removed.", flush=True)


def interrupt(_signal, _frame):
    raise KeyboardInterrupt


if __name__ == "__main__":
    # SIGTERM to this launcher follows the same graceful cleanup as Ctrl-C.
    signal.signal(signal.SIGTERM, interrupt)
    try:
        main()
    except KeyboardInterrupt:
        pass
    except (RuntimeError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
