"""Exercise the real k6 client against an isolated HTTP fixture, never an AI provider.

K6_BINARY=/path/to/k6 python3 -m unittest discover -s load-tests -p 'test_http_load.py'
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


K6 = os.environ.get("K6_BINARY") or shutil.which("k6")
SCRIPT = Path(__file__).with_name("http.js")


@unittest.skipUnless(K6, "Install k6 or set K6_BINARY to run real k6 fixture checks")
class K6ClientTests(unittest.TestCase):
    def run_workload(self, behavior="success", scenario="chat10", error_body=None):
        records = []
        identities = set()
        lock = threading.Lock()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, status, body, cookie=None):
                payload = body if isinstance(body, bytes) else json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                if cookie:
                    self.send_header("Set-Cookie", f"guest={cookie}; Path=/; HttpOnly")
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):
                self.reply(200, {"chatModel": "local-mock", "mcpConfigured": False})

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path == "/api/users/guest":
                    user = str(uuid.uuid4())
                    with lock:
                        identities.add(user)
                    self.reply(201, {"userId": user}, cookie=user)
                    return
                with lock:
                    records.append((self.path, body, self.headers.get("Cookie", "")))
                    number = len(records)
                if f"guest={body['userId']}" not in self.headers.get("Cookie", ""):
                    self.reply(401, {"error": "identity mismatch"})
                elif behavior == "saturated" or (behavior == "mixed" and number % 2 == 0):
                    self.reply(429, {"code": "AI_CAPACITY_REACHED"})
                elif behavior == "failed":
                    self.reply(503, error_body if error_body is not None else {"code": "AI_PROVIDER_CAPACITY"})
                else:
                    self.reply(200, {"memoryId": body["memoryId"], "answer": "fixture", "sources": []})

        class FixtureServer(ThreadingHTTPServer):
            # Avoid SYN backlog drops when many VUs initialize at once.
            request_queue_size = 128
            daemon_threads = True

        server = FixtureServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / "summary.json"
                env = {**os.environ, "BASE_URL": f"http://127.0.0.1:{server.server_port}",
                       "SCENARIO": scenario, "DURATION": "1s", "THINK_SECONDS": "0.1",
                       "SUMMARY_PATH": str(report), "K6_NO_USAGE_REPORT": "true"}
                result = subprocess.run([K6, "run", "--quiet", str(SCRIPT)], env=env,
                                        text=True, capture_output=True, timeout=20)
                summary = json.loads(report.read_text()) if report.exists() else None
                return result, summary, records, identities
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_guest_cookie_isolation_and_unique_memory(self):
        result, summary, records, identities = self.run_workload(scenario="rag")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(identities), 10)
        self.assertGreater(len(records), 10)
        self.assertEqual(len({body["memoryId"] for _, body, _ in records}), len(records))
        for path, body, cookie in records:
            self.assertEqual(path, "/api/ai/rag")
            self.assertIn(f"guest={body['userId']}", cookie)
        self.assertIsNotNone(summary)

    def test_saturation_is_separate_and_all_rejected_run_fails(self):
        result, summary, records, _ = self.run_workload(behavior="mixed")
        self.assertGreater(len(records), 0)
        self.assertEqual(result.returncode, 0, result.stderr)
        rejected = summary["metrics"]["ai_saturation_429"]["values"]["count"]
        self.assertEqual(summary["failure_classification"], {
            "http_status_counts": {"429": rejected},
            "error_code_counts": {"AI_CAPACITY_REACHED": rejected},
        })
        result, _, _, _ = self.run_workload(behavior="saturated")
        self.assertNotEqual(result.returncode, 0, "An all-429 run must fail success threshold")

    def test_server_errors_fail_threshold(self):
        for code in ("AI_PROVIDER_CAPACITY", "AI_PROVIDER_QUEUE_TIMEOUT"):
            with self.subTest(code=code):
                result, summary, records, _ = self.run_workload(behavior="failed", error_body={"code": code})
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(summary["failure_classification"], {
                    "http_status_counts": {"503": len(records)},
                    "error_code_counts": {code: len(records)},
                })
                self.assertEqual(summary["metrics"]["ai_unexpected_error_rate"]["values"]["rate"], 1)
                self.assertEqual(summary["metrics"]["ai_saturation_429"]["values"]["count"], 0)

    def test_error_diagnostics_are_bounded_and_never_emit_response_text(self):
        secret = "private-prompt-answer-cookie-marker"
        cases = (
            ({"code": secret, "message": secret}, "unknown"),
            ({"code": [secret]}, "unknown"),
            ({"message": secret}, "missing"),
            (("<html>" + secret).encode(), "invalid_payload"),
            ({"code": "AI_PROVIDER_CAPACITY", "message": secret * 1000}, "payload_too_large"),
        )
        for body, expected_code in cases:
            with self.subTest(expected_code=expected_code, body_type=type(body).__name__):
                result, summary, records, _ = self.run_workload(behavior="failed", error_body=body)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(summary["failure_classification"]["error_code_counts"], {expected_code: len(records)})
                self.assertEqual(summary["failure_classification"]["http_status_counts"], {"503": len(records)})
                self.assertNotIn(secret, json.dumps(summary))
                self.assertNotIn(secret, result.stdout + result.stderr)

    def test_tools_refuses_local_mock(self):
        result, _, records, identities = self.run_workload(scenario="tools")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("requires a real model", result.stderr)
        self.assertEqual(records, [])
        self.assertEqual(identities, set())


if __name__ == "__main__":
    unittest.main()
