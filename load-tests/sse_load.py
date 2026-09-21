#!/usr/bin/env python3
"""Measure the application's real ticket + SSE HTTP path using only Python's stdlib.

Example: python3 load-tests/sse_load.py --base-url http://localhost:8081 \
    --concurrency 8 --duration 30 --timeout 20 --output work/sse-load.json

Each worker owns one guest cookie jar; every attempt gets a new conversation ID.
TTFT is the time from starting the SSE GET to receiving the first nonempty
``message`` content event, not response headers, metadata, or completed output.
"""

from __future__ import annotations

import argparse
import codecs
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
import http.cookiejar
import json
import math
from pathlib import Path
import socket
import sys
import threading
import time
from typing import BinaryIO, Iterator
import urllib.error
import urllib.parse
import urllib.request
import uuid


MAX_RESPONSE_BYTES = 1_048_576
MAX_EVENT_BYTES = 1_048_576
MAX_ERROR_RESPONSE_BYTES = 16_384
KNOWN_ERROR_CODES = frozenset({
    "AI_CAPACITY_REACHED", "STREAM_CAPACITY_REACHED", "AI_MEMORY_CAPACITY_REACHED",
    "AI_PROVIDER_CAPACITY", "AI_PROVIDER_QUEUE_TIMEOUT", "AI_PROVIDER_TIMEOUT",
    "AI_PROVIDER_CANCELLED", "AI_UPSTREAM_ERROR", "AI_STREAM_ERROR",
    "CONVERSATION_BUSY", "STREAM_NOT_FOUND", "INVALID_STREAM_OWNER",
    "INVALID_GUEST_SESSION", "GUARDRAIL_REJECTED", "VALIDATION_FAILED",
    "INVALID_JSON", "INVALID_REQUEST", "NOT_FOUND", "METHOD_NOT_ALLOWED",
    "UNSUPPORTED_MEDIA_TYPE", "INTERNAL_ERROR",
})
KNOWN_PROTOCOL_FAILURES = frozenset({
    "event_too_large", "json_response_too_large", "invalid_json_object",
    "missing_stream_url", "cross_origin_stream_url", "invalid_stream_url",
    "missing_guest_id", "invalid_content_type", "invalid_done_payload",
    "empty_stream", "sse_error", "invalid_content_event", "incomplete_stream",
})


@dataclass(frozen=True)
class Config:
    base_url: str
    concurrency: int = 4
    duration: float = 30.0
    timeout: float = 30.0
    max_requests: int | None = None
    message: str = "Explain Java interfaces briefly."
    think_seconds: float = 3.1
    max_saturation_rate: float = 1.0

    def validate(self) -> None:
        parsed = urllib.parse.urlsplit(self.base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError("base-url must be an absolute HTTP(S) origin")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("base-url must not contain credentials, a query, or a fragment")
        if parsed.path not in ("", "/"):
            raise ValueError("base-url must be an origin without an API path")
        if not 1 <= self.concurrency <= 512:
            raise ValueError("concurrency must be between 1 and 512")
        if not math.isfinite(self.duration) or self.duration <= 0:
            raise ValueError("duration must be finite and greater than zero")
        if not math.isfinite(self.timeout) or self.timeout <= 0:
            raise ValueError("timeout must be finite and greater than zero")
        if self.max_requests is not None and self.max_requests <= 0:
            raise ValueError("max-requests must be greater than zero")
        if not self.message.strip():
            raise ValueError("message must not be blank")
        if not math.isfinite(self.think_seconds) or self.think_seconds < 0:
            raise ValueError("think-seconds must be finite and nonnegative")
        if not math.isfinite(self.max_saturation_rate) or not 0 <= self.max_saturation_rate <= 1:
            raise ValueError("max-saturation-rate must be between zero and one")


class ProtocolFailure(Exception):
    """A protocol failure with a stable, non-sensitive classification."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Do not forward this worker's traffic to another origin.
        return None


def error_code(payload: object) -> str:
    """Keep only exact known codes, never messages or arbitrary server labels."""
    if not isinstance(payload, dict):
        return "invalid_payload"
    if "code" not in payload:
        return "missing"
    code = payload["code"]
    return code if isinstance(code, str) and code in KNOWN_ERROR_CODES else "unknown"


def sse_error_code(data: str) -> str:
    # The backend emits a plain code. Also accept a structured JSON error event.
    if data in KNOWN_ERROR_CODES:
        return data
    if len(data.encode("utf-8")) > MAX_ERROR_RESPONSE_BYTES:
        return "payload_too_large"
    try:
        return error_code(json.loads(data))
    except (json.JSONDecodeError, UnicodeError):
        return "unknown"


def http_error_code(error: urllib.error.HTTPError, deadline: float) -> str:
    """Bound diagnostics by bytes and the attempt deadline without hiding status."""
    try:
        with DeadlineGuard(error.fp, deadline):
            content = bytearray()
            while True:
                remaining(deadline)
                chunk = error.read1(min(8192, MAX_ERROR_RESPONSE_BYTES + 1 - len(content)))
                remaining(deadline)
                if not chunk:
                    break
                content.extend(chunk)
                if len(content) > MAX_ERROR_RESPONSE_BYTES:
                    return "payload_too_large"
        try:
            return error_code(json.loads(content))
        except (json.JSONDecodeError, UnicodeError):
            return "invalid_payload"
    except Exception:
        return "unreadable_payload"
    finally:
        error.close()


def http_status(code: int) -> str:
    return str(code) if isinstance(code, int) and 100 <= code <= 599 else "other"


def remaining(deadline: float) -> float:
    timeout = deadline - time.perf_counter()
    if timeout <= 0:
        raise TimeoutError("request_deadline")
    return timeout


def _response_socket(response: BinaryIO) -> socket.socket | None:
    """urllib exposes the HTTPResponse's socket through its buffered reader.

    Retain it before EOF clears ``response.fp`` so the absolute-deadline guard
    can interrupt a read even when a peer trickles an unfinished line/chunk.
    """
    raw = getattr(getattr(response, "fp", None), "raw", None)
    return getattr(raw, "_sock", None)


class DeadlineGuard:
    """Interrupt a streaming read at the absolute deadline, including trickles."""

    def __init__(self, response: BinaryIO, deadline: float):
        self.socket = _response_socket(response)
        self.timer = threading.Timer(remaining(deadline), self._expire)
        self.timer.daemon = True

    def _expire(self) -> None:
        if self.socket is not None:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def __enter__(self):
        self.timer.start()
        return self

    def __exit__(self, *_):
        self.timer.cancel()


def iter_sse(response: BinaryIO, deadline: float) -> Iterator[tuple[str, str]]:
    """Parse incremental UTF-8 SSE without reading/buffering a whole response.

    HTTPResponse.read1 performs at most one buffered/raw body read. In contrast,
    read(n) can wait to fill n bytes and make measured TTFT equal completion time.
    LF, CRLF, CR, comments, and multiline data follow the SSE framing rules.
    """
    decoder = codecs.getincrementaldecoder("utf-8")("strict")
    line = ""
    event = "message"
    data: list[str] = []
    size = 0
    after_cr = False
    first_character = True

    while True:
        remaining(deadline)
        chunk = response.read1(8192)
        remaining(deadline)
        decoded = decoder.decode(chunk, final=not chunk)
        for character in decoded:
            if first_character:
                first_character = False
                if character == "\ufeff":
                    continue
            if after_cr and character == "\n":
                after_cr = False
                continue
            after_cr = character == "\r"
            size += len(character.encode("utf-8"))
            if size > MAX_EVENT_BYTES:
                raise ProtocolFailure("event_too_large")
            if character not in ("\r", "\n"):
                line += character
                continue
            if not line:
                if data:
                    yield event, "\n".join(data)
                event, data, size = "message", [], 0
            elif not line.startswith(":"):
                field, separator, value = line.partition(":")
                if separator and value.startswith(" "):
                    value = value[1:]
                if field == "event":
                    event = value or "message"
                elif field == "data":
                    data.append(value)
            line = ""
        if not chunk:
            # A trailing unterminated event is deliberately not dispatched.
            return


def json_post(opener, url: str, body: dict, deadline: float) -> dict:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    with opener.open(request, timeout=remaining(deadline)) as response:
        with DeadlineGuard(response, deadline):
            content = bytearray()
            while True:
                remaining(deadline)
                chunk = response.read1(8192)
                remaining(deadline)
                if not chunk:
                    break
                content.extend(chunk)
                if len(content) > MAX_RESPONSE_BYTES:
                    raise ProtocolFailure("json_response_too_large")
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            raise ProtocolFailure("invalid_json_object")
        return parsed


def same_origin_stream(base_url: str, value: object) -> str:
    if not isinstance(value, str) or not value:
        raise ProtocolFailure("missing_stream_url")
    resolved = urllib.parse.urljoin(base_url.rstrip("/") + "/", value)
    base, target = urllib.parse.urlsplit(base_url), urllib.parse.urlsplit(resolved)
    origin = lambda url: (url.scheme, url.hostname, url.port or (443 if url.scheme == "https" else 80))
    if origin(base) != origin(target) or target.username or target.password or target.fragment:
        raise ProtocolFailure("cross_origin_stream_url")
    if not target.path.startswith("/api/ai/chat/streams/"):
        raise ProtocolFailure("invalid_stream_url")
    return resolved


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower, upper = math.floor(index), math.ceil(index)
    return round(ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower), 3)


def distribution(values: list[float]) -> dict:
    return {
        "count": len(values), "p50": percentile(values, .50),
        "p95": percentile(values, .95), "p99": percentile(values, .99),
        "min": round(min(values), 3) if values else None,
        "max": round(max(values), 3) if values else None,
    }


def classify(error: Exception, deadline: float) -> str:
    # Once headers report an HTTP failure, preserve it even if reading its
    # optional diagnostic body reaches the deadline.
    if isinstance(error, urllib.error.HTTPError):
        return "http_" + http_status(error.code)
    if time.perf_counter() >= deadline or isinstance(error, (TimeoutError, socket.timeout)):
        return "timeout"
    if isinstance(error, ProtocolFailure):
        return str(error) if str(error) in KNOWN_PROTOCOL_FAILURES else "protocol_error"
    if isinstance(error, (json.JSONDecodeError, UnicodeError)):
        return "malformed_json_or_utf8"
    if isinstance(error, urllib.error.URLError):
        return "timeout" if isinstance(error.reason, (TimeoutError, socket.timeout)) else "network_error"
    if isinstance(error, OSError):
        return "network_error"
    return "unexpected_error"


def run_load(config: Config) -> dict:
    config.validate()
    base = config.base_url.rstrip("/")
    started_at = datetime.now(timezone.utc).isoformat()
    started = time.perf_counter()
    launch_deadline = started + config.duration
    lock = threading.Lock()
    records: list[dict] = []
    setup_errors: Counter = Counter()
    setup_http_statuses: Counter = Counter()
    setup_error_codes: Counter = Counter()
    workers_registered = 0
    attempted = 0
    active = 0
    max_active = 0

    def worker(worker_id: int) -> None:
        nonlocal workers_registered, attempted, active, max_active
        opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect(),
        )
        setup_deadline = time.perf_counter() + config.timeout
        try:
            guest = json_post(opener, base + "/api/users/guest", {
                "displayName": f"SSE load worker {worker_id}",
            }, setup_deadline)
            user_id = guest.get("userId")
            if not isinstance(user_id, str) or not user_id:
                raise ProtocolFailure("missing_guest_id")
            with lock:
                workers_registered += 1
        except Exception as error:
            classification = classify(error, setup_deadline)
            code = http_error_code(error, setup_deadline) if isinstance(error, urllib.error.HTTPError) else None
            with lock:
                setup_errors[classification] += 1
                if isinstance(error, urllib.error.HTTPError):
                    setup_http_statuses[http_status(error.code)] += 1
                    setup_error_codes[code] += 1
            return

        while True:
            with lock:
                if time.perf_counter() >= launch_deadline:
                    return
                if config.max_requests is not None and attempted >= config.max_requests:
                    return
                attempted += 1
            attempt_start = time.perf_counter()
            deadline = attempt_start + config.timeout
            record = {"success": False, "phase": "ticket", "content_events": 0, "content_characters": 0}
            stream_start = None
            opened = False
            try:
                ticket = json_post(opener, base + "/api/ai/chat/streams", {
                    "userId": user_id, "memoryId": "load-" + uuid.uuid4().hex,
                    "message": config.message,
                }, deadline)
                stream_url = same_origin_stream(base, ticket.get("streamUrl"))
                record["phase"] = "stream"
                stream_start = time.perf_counter()
                request = urllib.request.Request(stream_url, headers={"Accept": "text/event-stream"})
                with opener.open(request, timeout=remaining(deadline)) as response:
                    if response.headers.get_content_type() != "text/event-stream":
                        raise ProtocolFailure("invalid_content_type")
                    with lock:
                        active += 1
                        max_active = max(active, max_active)
                        opened = True
                    with DeadlineGuard(response, deadline):
                        for event, data in iter_sse(response, deadline):
                            if event == "done":
                                if data != "[DONE]":
                                    raise ProtocolFailure("invalid_done_payload")
                                if not record["content_events"]:
                                    raise ProtocolFailure("empty_stream")
                                record["success"] = True
                                break
                            if event == "error":
                                record["error_code"] = sse_error_code(data)
                                raise ProtocolFailure("sse_error")
                            payload = json.loads(data)
                            if event == "message":
                                if not isinstance(payload, dict) or not isinstance(payload.get("content"), str):
                                    raise ProtocolFailure("invalid_content_event")
                                content = payload["content"]
                                if content:
                                    received = time.perf_counter()
                                    if not record["content_events"]:
                                        record["ttft_ms"] = (received - stream_start) * 1000
                                        record["ticket_to_first_content_ms"] = (received - attempt_start) * 1000
                                    record["content_events"] += 1
                                    record["content_characters"] += len(content)
                        if not record["success"]:
                            raise ProtocolFailure("incomplete_stream")
            except Exception as error:
                record["success"] = False
                record["error"] = classify(error, deadline)
                if isinstance(error, urllib.error.HTTPError):
                    record["http_status"] = error.code
                    record["error_code"] = http_error_code(error, deadline)
            finally:
                ended = time.perf_counter()
                record["request_duration_ms"] = (ended - attempt_start) * 1000
                if stream_start is not None:
                    record["stream_duration_ms"] = (ended - stream_start) * 1000
                with lock:
                    if opened:
                        active -= 1
                    records.append(record)
            with lock:
                if config.max_requests is not None and attempted >= config.max_requests:
                    return
            pause = min(config.think_seconds, launch_deadline - time.perf_counter())
            if pause > 0:
                time.sleep(pause)

    with ThreadPoolExecutor(max_workers=config.concurrency, thread_name_prefix="sse-load") as executor:
        futures = [executor.submit(worker, index) for index in range(config.concurrency)]
        for future in futures:
            future.result()

    elapsed = time.perf_counter() - started
    succeeded = sum(record["success"] for record in records)
    saturated = sum(record.get("http_status") == 429 for record in records)
    failed = len(records) - succeeded
    content_events = sum(record["content_events"] for record in records)
    latency = {
        key: distribution([record[key] for record in records if key in record])
        for key in ("ttft_ms", "ticket_to_first_content_ms", "request_duration_ms", "stream_duration_ms")
    }
    latency["successful_request_duration_ms"] = distribution([
        record["request_duration_ms"] for record in records if record["success"]
    ])
    return {
        "schema_version": 1, "started_at": started_at, "base_url": base,
        "configuration": {
            "concurrency": config.concurrency, "launch_duration_seconds": config.duration,
            "request_timeout_seconds": config.timeout, "max_requests": config.max_requests,
            "think_seconds": config.think_seconds, "max_saturation_rate": config.max_saturation_rate,
        },
        "duration_seconds": round(elapsed, 6),
        "workers": {
            "requested": config.concurrency, "registered": workers_registered,
            "setup_failures": sum(setup_errors.values()), "error_counts": dict(setup_errors),
            "http_status_counts": dict(setup_http_statuses),
            "error_code_counts": dict(setup_error_codes),
        },
        "requests": {
            "attempted": len(records), "succeeded": succeeded, "failed": failed,
            "http_429": saturated, "non_saturation_failures": failed - saturated,
            "error_rate": round(failed / len(records), 6) if records else None,
            "unexpected_error_rate": round((failed - saturated) / len(records), 6) if records else None,
            "non_saturated_error_rate": round((failed - saturated) / (len(records) - saturated), 6) if len(records) > saturated else None,
            "http_429_rate": round(saturated / len(records), 6) if records else None,
            "error_counts": dict(Counter(record["error"] for record in records if "error" in record)),
            "http_status_counts": dict(Counter(http_status(record["http_status"]) for record in records if "http_status" in record)),
            "error_code_counts": dict(Counter(record["error_code"] for record in records if "error_code" in record)),
            "http_429_by_phase": dict(Counter(record["phase"] for record in records if record.get("http_status") == 429)),
        },
        "streams": {
            "active": active, "max_active": max_active, "completed": succeeded,
            "content_events": content_events,
            "content_characters": sum(record["content_characters"] for record in records),
        },
        "throughput": {
            "attempts_per_second": round(len(records) / elapsed, 6),
            "completed_streams_per_second": round(succeeded / elapsed, 6),
            "content_events_per_second": round(content_events / elapsed, 6),
        },
        "latency_ms": latency,
        "measurement_notes": [
            "TTFT starts before the SSE GET and ends at the first nonempty message content event; headers, meta and sources do not count.",
            "Ticket-to-first-content starts before the ticket POST; guest setup is excluded from request latency.",
            "Milestone distributions include failed attempts that reached that milestone; successful request duration is also reported separately.",
            "Percentiles use linear interpolation. Empty distributions are null, never fabricated zero latency.",
            "Active streams counts open HTTP SSE responses; throughput uses total wall time including guest setup and final request drain.",
            "Duration limits launching new requests; in-flight requests drain for up to the request timeout. Each worker has its own guest cookies and each request a unique memory ID.",
            "Default 3.1-second worker think time avoids the default 20 starts/minute per-owner limiter; use --think-seconds 0 only when intentionally testing that limiter or changing its configuration.",
            "Content events are not model tokens. HTTP 429 is counted as failure and also reported separately as saturation; guest setup errors are separate.",
            "unexpected_error_rate divides non-429 failures by all attempts; non_saturated_error_rate divides them by attempts excluding HTTP 429. HTTP 503 remains an unexpected failure.",
            "Error-code counts include only allowlisted application codes or fixed fallback buckets, from bounded HTTP bodies and SSE error events; no response text is retained.",
            "This is measured HTTP transport/load behavior, not an evaluation of answer quality or provider correctness.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--duration", type=float, default=30.0, help="seconds to launch requests, including guest setup")
    parser.add_argument("--timeout", type=float, default=30.0, help="absolute seconds allowed for each ticket + stream attempt")
    parser.add_argument("--max-requests", type=int, help="optional total request cap across all workers")
    parser.add_argument("--message", default="Explain Java interfaces briefly.")
    parser.add_argument("--think-seconds", type=float, default=3.1, help="pause between a worker's requests to avoid the default per-owner start rate limit")
    parser.add_argument("--max-saturation-rate", type=float, default=1.0, help="maximum acceptable HTTP 429 fraction; at least one successful stream is always required")
    parser.add_argument("--output", type=Path, help="also write the JSON report to this file")
    args = parser.parse_args(argv)
    config = Config(
        args.base_url, args.concurrency, args.duration, args.timeout, args.max_requests,
        args.message, args.think_seconds, args.max_saturation_rate,
    )
    try:
        config.validate()
    except ValueError as error:
        parser.error(str(error))
    report = run_load(config)
    serialized = json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized, encoding="utf-8")
    sys.stdout.write(serialized)
    requests = report["requests"]
    rejected = (
        report["workers"]["setup_failures"] or requests["non_saturation_failures"]
        or not requests["succeeded"]
        or requests["http_429"] / requests["attempted"] > config.max_saturation_rate
    )
    return 1 if rejected else 0


if __name__ == "__main__":
    raise SystemExit(main())
