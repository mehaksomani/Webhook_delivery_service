#!/usr/bin/env python3
"""
End-to-end scenario simulator for the webhook delivery service.

Zero-dep (stdlib only). It starts a local mock receiver, then drives the running
service through behavioral scenarios S1-S9 over its real HTTP API and asserts the
observable outcome of each (status, attempt count, queue depth, endpoint health,
dead-letter listing).

Prerequisites — start the service with the `sim` profile in another terminal:

    ./gradlew bootRun --args='--spring.profiles.active=sim'

The sim profile allows a 127.0.0.1 receiver (the SSRF guard blocks private
addresses by default) and compresses retry/lease timings so dead-lettering and
crash recovery happen in seconds. Then, from the repo root:

    python3 tools/simulate.py

Options (env vars):
    BILLING_BASE_URL   default http://localhost:8080
    RECEIVER_HOST      default 127.0.0.1
    RECEIVER_PORT      default 9099

Exit code is non-zero if any scenario fails.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
import uuid
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request as urlreq
from urllib.error import HTTPError, URLError

# Windows consoles often default to cp1252; force UTF-8 so output never crashes.
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE_URL = os.environ.get("BILLING_BASE_URL", "http://localhost:8080").rstrip("/")
RECEIVER_HOST = os.environ.get("RECEIVER_HOST", "127.0.0.1")
RECEIVER_PORT = int(os.environ.get("RECEIVER_PORT", "9099"))
RECEIVER_BASE = f"http://{RECEIVER_HOST}:{RECEIVER_PORT}"

# A short token per run so re-running against the same (in-memory) service does
# not collide on event ids — submit is idempotent on event_id, so reuse would be
# a silent no-op.
RUN = uuid.uuid4().hex[:8]


# --------------------------------------------------------------------------- #
# Mock webhook receiver
# --------------------------------------------------------------------------- #
class Receiver:
    """Behavior is selected by URL path; failure/slow decisions key off the
    X-Billing-Delivery-Attempt header so retries of the same event progress
    deterministically. Records every request for later assertions."""

    def __init__(self):
        self.lock = threading.Lock()
        self.hits_by_path = defaultdict(int)         # path -> request count
        self.attempts_by_event = defaultdict(list)   # event_id -> [attempt_no, ...]

    def record(self, path: str, event_id: str, attempt: int):
        with self.lock:
            self.hits_by_path[path] += 1
            if event_id:
                self.attempts_by_event[event_id].append(attempt)

    def path_hits(self, path: str) -> int:
        with self.lock:
            return self.hits_by_path[path]

    def event_attempts(self, event_id: str) -> list:
        with self.lock:
            return list(self.attempts_by_event[event_id])


RECEIVER = Receiver()


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # silence default per-request stderr logging
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length:
            self.rfile.read(length)  # drain body
        path = self.path.split("?", 1)[0]
        event_id = self.headers.get("X-Billing-Event-Id", "")
        attempt = int(self.headers.get("X-Billing-Delivery-Attempt", "1") or "1")
        RECEIVER.record(path, event_id, attempt)

        status = self._decide(path, attempt)
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    @staticmethod
    def _decide(path: str, attempt: int) -> int:
        if path == "/happy":
            return 200
        if path == "/flaky":
            # Fail the first two attempts (500), succeed on the third.
            return 200 if attempt >= 3 else 500
        if path == "/down":
            return 500                      # always retriable failure -> dead-letters
        if path == "/perm":
            return 400                      # permanent failure -> dead-letters at once
        if path == "/slow":
            time.sleep(2)                   # < lease, so no spurious recovery
            return 200
        if path == "/crash":
            # First attempt hangs past the lease (simulating a hung/crashed worker);
            # the re-dispatched attempt returns immediately.
            if attempt == 1:
                time.sleep(9)
            return 200
        return 404


def start_receiver() -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((RECEIVER_HOST, RECEIVER_PORT), _Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


# --------------------------------------------------------------------------- #
# Billing service client
# --------------------------------------------------------------------------- #
def _req(method: str, path: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urlreq.Request(BASE_URL + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urlreq.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode() or "{}"
            return resp.status, json.loads(raw)
    except HTTPError as e:
        raw = e.read().decode() or "{}"
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def register(endpoint_id: str, path: str):
    return _req("POST", "/api/v1/endpoints",
                {"endpointId": endpoint_id, "url": RECEIVER_BASE + path})


def submit(event_id: str, endpoint_id: str, payload=None):
    return _req("POST", "/api/v1/events", {
        "eventId": event_id,
        "eventType": "billing.invoice.created",
        "endpointId": endpoint_id,
        "payload": json.dumps(payload or {"amount": 100, "run": RUN}),
    })


def get_delivery(event_id: str):
    status, body = _req("GET", f"/api/v1/deliveries/{event_id}")
    return body if status == 200 else None


def queue_depth(endpoint_id: str) -> int:
    _, body = _req("GET", f"/api/v1/queues/{endpoint_id}/depth")
    return body.get("queue_depth", -1)


def endpoint_status(endpoint_id: str) -> str:
    _, body = _req("GET", f"/api/v1/endpoints/{endpoint_id}/status")
    return body.get("status", "?")


def dead_letters_since(since_iso: str):
    _, body = _req("GET", f"/api/v1/dead-letters?since={since_iso}")
    return body if isinstance(body, list) else []


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def poll(predicate, timeout=20.0, interval=0.25):
    """Call predicate() until it returns a truthy value or timeout. Returns the
    last value (truthy on success, falsy on timeout)."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(interval)
    return last


def iso_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + "Z"


PASS, FAIL = "PASS", "FAIL"
results = []


def report(scenario: str, ok: bool, detail: str):
    mark = PASS if ok else FAIL
    results.append(ok)
    print(f"  [{mark}] {scenario}: {detail}")


def eid(name: str) -> str:
    return f"{name}-{RUN}"


# --------------------------------------------------------------------------- #
# Scenarios
# --------------------------------------------------------------------------- #
def s1_happy_path():
    print("S1 — Happy path: a healthy endpoint delivers on the first attempt")
    register("ep-happy", "/happy")
    e = eid("evt-s1")
    submit(e, "ep-happy")
    d = poll(lambda: (get_delivery(e) or {}).get("status") == "SUCCEEDED" and get_delivery(e))
    ok = bool(d) and d["status"] == "SUCCEEDED" and d["attemptCount"] == 1
    report("S1", ok, f"status={d and d['status']} attempts={d and d['attemptCount']} (want SUCCEEDED/1)")


def s2_retry_backoff():
    print("S2 — Retry with backoff: two transient 500s, then success on attempt 3")
    register("ep-flaky", "/flaky")
    e = eid("evt-s2")
    submit(e, "ep-flaky")
    d = poll(lambda: (get_delivery(e) or {}).get("status") == "SUCCEEDED" and get_delivery(e), timeout=20)
    ok = bool(d) and d["status"] == "SUCCEEDED" and d["attemptCount"] == 3
    report("S2", ok, f"status={d and d['status']} attempts={d and d['attemptCount']} (want SUCCEEDED/3)")


def s3_permanent_dead_letter():
    print("S3 — Dead-letter: 4xx is permanent (1 attempt); repeated 5xx exhausts retries (4 attempts)")
    register("ep-perm", "/perm")
    register("ep-down", "/down")
    perm, down = eid("evt-s3-perm"), eid("evt-s3-down")
    submit(perm, "ep-perm")
    submit(down, "ep-down")

    dp = poll(lambda: (get_delivery(perm) or {}).get("status") == "DEAD_LETTERED" and get_delivery(perm), timeout=15)
    ok_perm = bool(dp) and dp["attemptCount"] == 1 and "permanent_failure" in (dp["deadLetterReason"] or "")
    report("S3a (permanent)", ok_perm,
           f"attempts={dp and dp['attemptCount']} reason={dp and dp['deadLetterReason']} (want 1 / permanent_failure)")

    dd = poll(lambda: (get_delivery(down) or {}).get("status") == "DEAD_LETTERED" and get_delivery(down), timeout=20)
    ok_down = bool(dd) and dd["attemptCount"] == 4 and "max_attempts_exceeded" in (dd["deadLetterReason"] or "")
    report("S3b (exhausted)", ok_down,
           f"attempts={dd and dd['attemptCount']} reason={dd and dd['deadLetterReason']} (want 4 / max_attempts_exceeded)")


def s4_endpoint_isolation():
    print("S4 — Endpoint isolation: a slow endpoint does not delay a fast one")
    register("ep-slow", "/slow")
    register("ep-fast", "/happy")
    # Back up the slow endpoint, then submit one fast event.
    for i in range(8):
        submit(eid(f"evt-s4-slow-{i}"), "ep-slow")
    fast = eid("evt-s4-fast")
    t0 = time.time()
    submit(fast, "ep-fast")
    d = poll(lambda: (get_delivery(fast) or {}).get("status") == "SUCCEEDED" and get_delivery(fast), timeout=8)
    elapsed = time.time() - t0
    ok = bool(d) and d["status"] == "SUCCEEDED" and elapsed < 5
    report("S4", ok, f"fast event SUCCEEDED in {elapsed:.1f}s while slow endpoint backed up (want < 5s)")


def s5_idempotent_resubmit():
    print("S5 — Idempotent submit: the same event_id submitted twice is one delivery")
    register("ep-idem", "/happy")
    e = eid("evt-s5")
    submit(e, "ep-idem")
    poll(lambda: (get_delivery(e) or {}).get("status") == "SUCCEEDED")
    submit(e, "ep-idem")          # duplicate — must be a no-op
    time.sleep(1.0)
    d = get_delivery(e)
    receiver_hits = RECEIVER.event_attempts(e)
    ok = bool(d) and d["attemptCount"] == 1 and len(receiver_hits) == 1
    report("S5", ok, f"attempts={d and d['attemptCount']} receiver_deliveries={len(receiver_hits)} (want 1 / 1)")


def s6_crash_recovery():
    print("S6 — Crash recovery: a hung attempt past the lease is re-dispatched as the same event")
    register("ep-crash", "/crash")
    e = eid("evt-s6")
    submit(e, "ep-crash")
    # Attempt 1 hangs 9s; lease is 5s, so the recovery sweep marks it CRASH and
    # re-dispatches. Attempt 2 returns immediately.
    d = poll(lambda: (get_delivery(e) or {}).get("status") == "SUCCEEDED" and get_delivery(e), timeout=25)
    outcomes = [a["outcome"] for a in (d["attempts"] if d else [])]
    ok = bool(d) and d["status"] == "SUCCEEDED" and d["attemptCount"] == 2 and "CRASH" in outcomes
    report("S6", ok, f"status={d and d['status']} attempts={d and d['attemptCount']} outcomes={outcomes} (want SUCCEEDED/2 incl CRASH)")


def s7_queue_depth():
    print("S7 — Queue depth: pending deliveries for an endpoint are countable")
    register("ep-depth", "/slow")
    for i in range(8):
        submit(eid(f"evt-s7-{i}"), "ep-depth")
    depth = poll(lambda: queue_depth("ep-depth") > 0, timeout=4)
    d = queue_depth("ep-depth")
    ok = bool(depth) and d > 0
    report("S7", ok, f"queue_depth={d} while the slow endpoint drains (want > 0)")


def s8_endpoint_health():
    print("S8 — Endpoint health: an endpoint that keeps failing is flagged UNHEALTHY")
    register("ep-health", "/down")
    for i in range(3):
        submit(eid(f"evt-s8-{i}"), "ep-health")
    status = poll(lambda: endpoint_status("ep-health") == "UNHEALTHY" and "UNHEALTHY", timeout=20)
    ok = status == "UNHEALTHY"
    report("S8", ok, f"endpoint health={endpoint_status('ep-health')} after repeated failures (want UNHEALTHY)")


def s9_dead_letter_listing(since_iso: str):
    print("S9 — Dead-letter listing: dead-lettered deliveries are queryable since a timestamp")
    dls = dead_letters_since(since_iso)
    ids = {d["eventId"] for d in dls}
    expected = {eid("evt-s3-perm"), eid("evt-s3-down")}
    ok = expected.issubset(ids)
    report("S9", ok, f"{len(dls)} dead-letters listed since start; contains S3 events = {expected.issubset(ids)}")


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def drain(endpoint_ids, timeout=15.0):
    """Let the slow-endpoint batches finish before we tear down the receiver, so
    still-in-flight deliveries don't fail against a dead target and dead-letter."""
    poll(lambda: all(queue_depth(e) == 0 for e in endpoint_ids), timeout=timeout)
    time.sleep(2.5)  # cover the last in-flight (claimed-but-not-pending) slow request


def wait_for_service() -> bool:
    for _ in range(20):
        try:
            with urlreq.urlopen(BASE_URL + "/actuator/health", timeout=2) as resp:
                if resp.status == 200:
                    return True
        except (URLError, OSError):
            time.sleep(0.5)
    return False


def main():
    print(f"Billing service : {BASE_URL}")
    print(f"Mock receiver   : {RECEIVER_BASE}")
    print(f"Run token       : {RUN}\n")

    if not wait_for_service():
        print(f"ERROR: billing service not reachable at {BASE_URL}.")
        print("Start it first with:  ./gradlew bootRun --args='--spring.profiles.active=sim'")
        return 2

    server = start_receiver()
    started_at = iso_now()
    try:
        s1_happy_path()
        s2_retry_backoff()
        s3_permanent_dead_letter()
        s4_endpoint_isolation()
        s5_idempotent_resubmit()
        s6_crash_recovery()
        s7_queue_depth()
        s8_endpoint_health()
        s9_dead_letter_listing(started_at)
        # Let the slow-endpoint batches (S4, S7) finish against a live receiver.
        drain(["ep-slow", "ep-depth"])
    finally:
        server.shutdown()

    passed = sum(1 for r in results if r)
    total = len(results)
    print(f"\n{'=' * 60}")
    print(f"Result: {passed}/{total} checks passed")
    print("=" * 60)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
