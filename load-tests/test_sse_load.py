"""Exercise the load runner against real, incrementally flushed HTTP streams.

These tests deliberately use no Qwen/MCP credentials and do not claim anything
about model quality or production capacity.
"""

import io
import json
import threading
import time
import unittest
from contextlib import contextmanager, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from sse_load import Config, MAX_ERROR_RESPONSE_BYTES, main, run_load


class MockBackend:
    def __init__(self, scenario="success", error_payload=None):
        self.scenario = scenario
        self.error_payload = error_payload
        self.lock = threading.Lock()
        self.guests = []
        self.tickets = []
        self.stream_cookies = []
        self.identity_errors = []

    def handler(self):
        state = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, _format, *args):
                pass

            def json_response(self, status, payload, cookie=None):
                encoded = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                if cookie:
                    self.send_header("Set-Cookie", cookie + "; Path=/; HttpOnly")
                self.end_headers()
                self.wfile.write(encoded)
                self.wfile.flush()

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                if self.path == "/api/users/guest":
                    if state.scenario == "guest_error":
                        self.json_response(503, state.error_payload)
                        return
                    with state.lock:
                        user_id = "guest-" + str(len(state.guests) + 1)
                        state.guests.append(user_id)
                    self.json_response(201, {"userId": user_id}, "guest=" + user_id)
                    return
                if self.path != "/api/ai/chat/streams":
                    self.json_response(404, {})
                    return
                cookie = self.headers.get("Cookie", "")
                if cookie != "guest=" + body.get("userId", ""):
                    with state.lock:
                        state.identity_errors.append((body.get("userId"), cookie))
                    self.json_response(403, {"code": "IDENTITY_MISMATCH"})
                    return
                with state.lock:
                    ticket = dict(body, cookie=cookie, ticketId=len(state.tickets) + 1)
                    state.tickets.append(ticket)
                if state.scenario == "ticket_429" or (
                    state.scenario == "mixed_429" and ticket["ticketId"] % 2 == 1
                ):
                    self.json_response(429, {"code": "AI_CAPACITY_REACHED"})
                    return
                if state.scenario == "ticket_error":
                    self.json_response(503, state.error_payload)
                    return
                if state.scenario == "delayed_success":
                    time.sleep(0.07)
                self.json_response(201, {
                    "streamId": str(ticket["ticketId"]),
                    "streamUrl": "/api/ai/chat/streams/" + str(ticket["ticketId"]),
                })

            def event(self, name, data):
                self.wfile.write(("event: " + name + "\ndata: " + data + "\n\n").encode("utf-8"))
                self.wfile.flush()

            def do_GET(self):
                path = urlsplit(self.path).path
                if path == "/api/health":
                    self.json_response(200, {
                        "status": "UP", "chatModel": "local-mock",
                        "embeddingModel": "local-hash", "mcpConfigured": False,
                    })
                    return
                if not path.startswith("/api/ai/chat/streams/"):
                    self.json_response(404, {})
                    return
                ticket_id = int(path.rsplit("/", 1)[1])
                with state.lock:
                    ticket = state.tickets[ticket_id - 1]
                    cookie = self.headers.get("Cookie", "")
                    state.stream_cookies.append(cookie)
                    if cookie != ticket["cookie"]:
                        state.identity_errors.append((ticket["userId"], cookie))
                if cookie != ticket["cookie"]:
                    self.json_response(403, {"code": "IDENTITY_MISMATCH"})
                    return
                if state.scenario == "stream_429":
                    self.json_response(429, {"code": "STREAM_CAPACITY_REACHED"})
                    return
                if state.scenario == "stream_503":
                    self.json_response(503, state.error_payload if state.error_payload is not None
                                       else {"code": "AI_PROVIDER_CAPACITY"})
                    return
                if state.scenario == "http_error_trickle":
                    self.send_response(503)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", "100000")
                    self.end_headers()
                    try:
                        for _ in range(100):
                            self.wfile.write(b" ")
                            self.wfile.flush()
                            time.sleep(0.01)
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                    return
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                try:
                    self.event("meta", json.dumps({"memoryId": ticket["memoryId"], "model": "local-mock"}))
                    self.event("sources", "[]")
                    self.event("message", '{"content":""}')
                    if state.scenario == "trickle_timeout":
                        # Continuously receive bytes without ever completing a
                        # frame. A per-read timeout alone would not bound this.
                        self.wfile.write(b"event: message\ndata: {")
                        for _ in range(100):
                            self.wfile.write(b" ")
                            self.wfile.flush()
                            time.sleep(0.01)
                        return
                    if state.scenario == "delayed_success":
                        time.sleep(0.16)
                    if state.scenario == "malformed_json":
                        self.event("message", "{broken json")
                        self.event("done", "[DONE]")
                        return
                    self.event("message", json.dumps({"content": "First token"}))
                    if state.scenario == "incomplete":
                        return
                    if state.scenario == "error_event":
                        self.event("error", state.error_payload if state.error_payload is not None
                                   else "AI_UPSTREAM_ERROR")
                        self.event("done", "[DONE]")
                        return
                    if state.scenario == "delayed_success":
                        time.sleep(0.28)
                    else:
                        # Permit overlapping requests in the worker-isolation test.
                        time.sleep(0.015)
                    self.event("message", json.dumps({"content": " complete"}))
                    self.event("done", "[DONE]")
                except (BrokenPipeError, ConnectionResetError):
                    # A strict client may disconnect immediately upon an invalid frame.
                    pass

        return Handler


@contextmanager
def backend(scenario="success", error_payload=None):
    state = MockBackend(scenario, error_payload)
    server = ThreadingHTTPServer(("127.0.0.1", 0), state.handler())
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield "http://127.0.0.1:" + str(server.server_port), state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class SseLoadTests(unittest.TestCase):
    def load(self, base_url, **overrides):
        options = {"concurrency": 1, "duration": 2, "timeout": 2, "max_requests": 1,
                   "think_seconds": 0,
                   "message": "Test the actual SSE transport."}
        options.update(overrides)
        return run_load(Config(base_url=base_url, **options))

    def test_first_token_latency_measures_content_before_completion(self):
        with backend("delayed_success") as (base_url, _state):
            report = self.load(base_url)
        self.assertEqual(report["requests"]["succeeded"], 1)
        latency = report["latency_ms"]
        ttft = latency["ttft_ms"]
        ticket_ttft = latency["ticket_to_first_content_ms"]
        duration = latency["successful_request_duration_ms"]
        self.assertEqual(ttft["count"], 1)
        self.assertGreaterEqual(ttft["p50"], 100, "meta and empty frames are not first content")
        self.assertGreaterEqual(ticket_ttft["p50"] - ttft["p50"], 40,
                                "ticket latency must include the delayed POST")
        self.assertGreaterEqual(duration["p50"] - ticket_ttft["p50"], 200,
                                "reading the whole stream before timing would incorrectly measure done")
        for key in ("ttft_ms", "ticket_to_first_content_ms", "request_duration_ms",
                    "successful_request_duration_ms"):
            with self.subTest(metric=key):
                self.assertLessEqual(latency[key]["p50"], latency[key]["p95"])
                self.assertLessEqual(latency[key]["p95"], latency[key]["p99"])
        self.assertEqual(report["streams"]["content_events"], 2)
        self.assertEqual(report["streams"]["active"], 0)

    def test_workers_keep_separate_guest_cookies_and_attempts_use_fresh_memories(self):
        with backend() as (base_url, state):
            report = self.load(base_url, concurrency=2, max_requests=6)
        self.assertEqual(report["workers"]["registered"], 2)
        self.assertEqual(report["workers"]["setup_failures"], 0)
        self.assertEqual(len(state.guests), 2)
        self.assertEqual(state.identity_errors, [])
        self.assertEqual({ticket["userId"] for ticket in state.tickets}, set(state.guests))
        self.assertEqual(set(state.stream_cookies), {"guest=" + user for user in state.guests})
        self.assertEqual(len({ticket["memoryId"] for ticket in state.tickets}), 6)
        self.assertEqual(report["requests"]["attempted"], 6)
        self.assertEqual(report["requests"]["succeeded"], 6)
        self.assertEqual(report["requests"]["failed"], 0)
        self.assertGreaterEqual(report["streams"]["max_active"], 2)
        self.assertLessEqual(report["streams"]["max_active"], 2)
        self.assertEqual(report["streams"]["active"], 0)

    def test_ticket_and_stream_429_are_separate_from_non_saturation_failures(self):
        for scenario in ("ticket_429", "stream_429"):
            with self.subTest(scenario=scenario):
                with backend(scenario) as (base_url, _state):
                    report = self.load(base_url)
                requests = report["requests"]
                self.assertEqual(requests["attempted"], 1)
                self.assertEqual(requests["succeeded"], 0)
                self.assertEqual(requests["failed"], 1)
                self.assertEqual(requests["http_429"], 1)
                self.assertEqual(requests["non_saturation_failures"], 0)
                expected_code = "AI_CAPACITY_REACHED" if scenario == "ticket_429" else "STREAM_CAPACITY_REACHED"
                self.assertEqual(requests["error_code_counts"], {expected_code: 1})
                self.assertEqual(report["latency_ms"]["successful_request_duration_ms"]["count"], 0)
                self.assertEqual(report["latency_ms"]["ttft_ms"]["count"], 0)
                self.assertEqual(report["streams"]["active"], 0)

    def test_invalid_interrupted_and_error_streams_cannot_count_as_success(self):
        for scenario in ("malformed_json", "incomplete", "error_event"):
            with self.subTest(scenario=scenario):
                with backend(scenario) as (base_url, _state):
                    report = self.load(base_url)
                requests = report["requests"]
                self.assertEqual(requests["attempted"], 1)
                self.assertEqual(requests["succeeded"], 0)
                self.assertEqual(requests["failed"], 1)
                self.assertEqual(requests["http_429"], 0)
                self.assertEqual(requests["non_saturation_failures"], 1)
                self.assertTrue(requests["error_counts"])
                self.assertEqual(report["latency_ms"]["successful_request_duration_ms"]["count"], 0)
                self.assertEqual(report["streams"]["active"], 0)

    def test_unexpected_http_503_counts_as_non_saturation_failure(self):
        with backend("stream_503") as (base_url, _state):
            report = self.load(base_url)
        requests = report["requests"]
        self.assertEqual(requests["attempted"], 1)
        self.assertEqual(requests["succeeded"], 0)
        self.assertEqual(requests["failed"], 1)
        self.assertEqual(requests["http_429"], 0)
        self.assertEqual(requests["non_saturation_failures"], 1)
        self.assertEqual(requests["error_counts"].get("http_503"), 1)
        self.assertEqual(requests["error_code_counts"], {"AI_PROVIDER_CAPACITY": 1})

    def test_known_provider_errors_retain_code_and_failed_disposition(self):
        for code in ("AI_PROVIDER_CAPACITY", "AI_PROVIDER_QUEUE_TIMEOUT"):
            for scenario, payload in (("ticket_error", {"code": code}),
                                      ("stream_503", {"code": code}),
                                      ("error_event", code),
                                      ("error_event", json.dumps({"code": code}))):
                with self.subTest(code=code, scenario=scenario, payload=payload):
                    with backend(scenario, payload) as (base_url, _state):
                        report = self.load(base_url)
                    requests = report["requests"]
                    self.assertEqual(requests["error_code_counts"], {code: 1})
                    self.assertEqual(requests["succeeded"], 0)
                    self.assertEqual(requests["http_429"], 0)
                    self.assertEqual(requests["non_saturation_failures"], 1)
                    self.assertEqual(requests["error_counts"], {"sse_error" if scenario == "error_event" else "http_503": 1})

    def test_setup_failure_reports_code_separately(self):
        with backend("guest_error", {"code": "AI_PROVIDER_CAPACITY"}) as (base_url, _state):
            report = self.load(base_url)
        self.assertEqual(report["workers"]["setup_failures"], 1)
        self.assertEqual(report["workers"]["error_code_counts"], {"AI_PROVIDER_CAPACITY": 1})
        self.assertEqual(report["workers"]["http_status_counts"], {"503": 1})
        self.assertEqual(report["requests"]["attempted"], 0)

    def test_unknown_and_malformed_error_diagnostics_never_emit_response_text(self):
        secret = "private-prompt-answer-cookie-marker"
        cases = (
            ({"code": secret, "message": secret}, "unknown"),
            ({"code": [secret]}, "unknown"),
            ({"message": secret}, "missing"),
            ([secret], "invalid_payload"),
            (("<html>" + secret).encode(), "invalid_payload"),
            ({"code": "AI_PROVIDER_CAPACITY", "message": secret * 1000}, "payload_too_large"),
        )
        for payload, expected_code in cases:
            with self.subTest(expected_code=expected_code, payload_type=type(payload).__name__):
                with backend("stream_503", payload) as (base_url, _state):
                    report = self.load(base_url)
                self.assertEqual(report["requests"]["error_code_counts"], {expected_code: 1})
                self.assertEqual(report["requests"]["error_counts"], {"http_503": 1})
                self.assertNotIn(secret, json.dumps(report))
        for payload, expected_code in ((secret, "unknown"),
                                       (json.dumps({"code": secret, "message": secret}), "unknown"),
                                       (secret * MAX_ERROR_RESPONSE_BYTES, "payload_too_large")):
            with self.subTest(sse_code=expected_code):
                with backend("error_event", payload) as (base_url, _state):
                    report = self.load(base_url)
                self.assertEqual(report["requests"]["error_code_counts"], {expected_code: 1})
                self.assertEqual(report["requests"]["error_counts"], {"sse_error": 1})
                self.assertEqual(report["requests"]["succeeded"], 0)
                self.assertNotIn(secret, json.dumps(report))

    def test_trickling_http_error_body_keeps_status_and_absolute_deadline(self):
        with backend("http_error_trickle") as (base_url, _state):
            report = self.load(base_url, timeout=0.12)
        self.assertEqual(report["requests"]["error_counts"], {"http_503": 1})
        self.assertEqual(report["requests"]["error_code_counts"], {"unreadable_payload": 1})
        self.assertEqual(report["requests"]["non_saturation_failures"], 1)
        self.assertLess(report["latency_ms"]["request_duration_ms"]["p50"], 700)

    def test_absolute_timeout_interrupts_an_unfinished_trickling_frame(self):
        with backend("trickle_timeout") as (base_url, _state):
            report = self.load(base_url, timeout=0.12)
        self.assertEqual(report["requests"]["succeeded"], 0)
        self.assertEqual(report["requests"]["non_saturation_failures"], 1)
        self.assertEqual(report["requests"]["error_counts"].get("timeout"), 1)
        self.assertEqual(report["streams"]["active"], 0)
        self.assertEqual(report["latency_ms"]["ttft_ms"]["count"], 0)
        self.assertLess(report["latency_ms"]["request_duration_ms"]["p50"], 700,
                        "continuous trickling must not extend the absolute request deadline")

    def test_cli_exit_status_distinguishes_saturation_from_unsuccessful_runs(self):
        cases = (("mixed_429", "1", 0, 1), ("ticket_429", "1", 1, 0),
                 ("mixed_429", "0.25", 1, 1), ("stream_503", "1", 1, 0))
        for scenario, saturation_limit, expected_exit, expected_successes in cases:
            with self.subTest(scenario=scenario, saturation_limit=saturation_limit):
                with backend(scenario) as (base_url, _state):
                    output = io.StringIO()
                    with redirect_stdout(output):
                        exit_code = main([
                            "--base-url", base_url, "--concurrency", "1", "--duration", "2",
                            "--timeout", "2", "--max-requests", "2", "--think-seconds", "0",
                            "--max-saturation-rate", saturation_limit,
                        ])
                self.assertEqual(exit_code, expected_exit)
                report = json.loads(output.getvalue())
                self.assertEqual(report["requests"]["succeeded"], expected_successes)
                self.assertEqual(report["requests"]["attempted"], 2)


if __name__ == "__main__":
    unittest.main()
