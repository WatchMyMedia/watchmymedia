import http.server
import json
import threading
import time
import unittest
import urllib.request

from link_proxy import Downlink, Proxy, verified_target


class FictionalFixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/_fixture/status":
            data = json.dumps({"fixture": "streaming-fixture-v1",
                               "baseUrl": f"http://127.0.0.1:{self.server.server_port}"}).encode()
        elif self.path == "/blob":
            data = b"fictional media bytes" * 4096
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_args):
        pass


class AppleFixture(FictionalFixture):
    def do_GET(self):
        if self.path == "/_fixture/status":
            self.send_error(503)
        elif self.path == "/api/server":
            data = json.dumps({"version": "live-tv-fixture", "name": "Meadow Live TV fixture"}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            super().do_GET()


class LinkProxyTests(unittest.TestCase):
    def test_held_response_resumes_after_rate_change(self):
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), FictionalFixture) as fixture:
            fixture_thread = threading.Thread(target=fixture.serve_forever, daemon=True)
            fixture_thread.start()
            base = f"http://127.0.0.1:{fixture.server_port}"
            self.assertEqual(verified_target(base), ("127.0.0.1", fixture.server_port))
            limiter = Downlink(0)
            with Proxy(("127.0.0.1", fixture.server_port), limiter) as proxy:
                proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
                proxy_thread.start()
                received = []
                fetch = threading.Thread(target=lambda: received.append(
                    urllib.request.urlopen(f"http://127.0.0.1:{proxy.server_address[1]}/blob", timeout=5).read()), daemon=True)
                fetch.start()
                time.sleep(.1)
                self.assertTrue(fetch.is_alive())
                limiter.set_rate(-1)
                fetch.join(timeout=5)
                self.assertFalse(fetch.is_alive())
                self.assertEqual(received, [b"fictional media bytes" * 4096])
                self.assertGreaterEqual(limiter.delivered, len(received[0]))
                limiter.close()
                proxy.shutdown()
                proxy_thread.join(timeout=5)
            fixture.shutdown()
            fixture_thread.join(timeout=5)

    def test_rejects_unverified_and_non_loopback_targets(self):
        for value in ("http://example.com:1234", "https://127.0.0.1:1234", "http://127.0.0.1:1234/path"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                verified_target(value)

    def test_accepts_the_channel_only_apple_fixture_identity(self):
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), AppleFixture) as fixture:
            thread = threading.Thread(target=fixture.serve_forever, daemon=True)
            thread.start()
            self.assertEqual(verified_target(f"http://127.0.0.1:{fixture.server_port}"),
                             ("127.0.0.1", fixture.server_port))
            fixture.shutdown()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
