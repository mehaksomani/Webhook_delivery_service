#!/usr/bin/env python3
"""
Diagnostic queries against webhook_delivery.log.jsonl.

Zero-dep (stdlib only). Run from the repo root:

    python3 tools/diagnose.py

Every section corresponds to a citation in diagnosis.md.
"""

from __future__ import annotations

import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path


LOG_PATH = Path(__file__).resolve().parent.parent / "webhook_delivery.log.jsonl"


def parse_ts(ts: str) -> datetime:
    # Accept both "...Z" and fractional-seconds variants.
    if ts.endswith("Z"):
        ts = ts[:-1] + "+00:00"
    return datetime.fromisoformat(ts)


def load_events():
    lines = []
    by_event: dict[str, list[dict]] = defaultdict(list)
    with LOG_PATH.open("r", encoding="utf-8") as f:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            obj = json.loads(raw)
            lines.append(obj)
            if "event_id" in obj:
                by_event[obj["event_id"]].append(obj)
    # Each event's lines must be in chronological order.
    for evt in by_event.values():
        evt.sort(key=lambda r: parse_ts(r["ts"]))
    return lines, by_event


def shape(records: list[dict]) -> str:
    return " -> ".join(r["msg"] for r in records)


def q1_sanity(lines: list[dict], by_event: dict[str, list[dict]]) -> None:
    print("Q1 — Sanity counts")
    print("-" * 60)
    statuses = Counter(r["status"] for r in lines if "status" in r)
    levels = Counter(r["level"] for r in lines if "level" in r)
    timestamps = [parse_ts(r["ts"]) for r in lines]
    print(f"  Total lines           : {len(lines)}")
    print(f"  Distinct event_ids    : {len(by_event)}")
    print(f"  Time range            : {min(timestamps).isoformat().replace('+00:00', 'Z')}  ->  "
          f"{max(timestamps).isoformat().replace('+00:00', 'Z')}")
    status_str = ", ".join(f"{k}={v}" for k, v in sorted(statuses.items()))
    print(f"  Status codes seen     : {status_str}")
    level_str = ", ".join(f"{k}={v}" for k, v in sorted(levels.items()))
    print(f"  Levels                : {level_str}")
    print()


def q2_shapes(by_event: dict[str, list[dict]]) -> None:
    print("Q2 — Per-event lifecycle shapes")
    print("-" * 60)
    shape_counts: Counter = Counter()
    for evt_id, records in by_event.items():
        s = shape(records)
        terminal_msgs = {"delivery_succeeded", "delivery_abandoned"}
        if not any(r["msg"] in terminal_msgs for r in records):
            s = s + "        [NO TERMINAL]"
        shape_counts[s] += 1
    for s, n in shape_counts.most_common():
        print(f"  [{n:>3}]  {s}")
    print()


def q3_abandonments(by_event: dict[str, list[dict]]) -> None:
    print("Q3 — delivery_abandoned anatomy")
    print("-" * 60)
    abandons = []
    for evt_id, records in by_event.items():
        for r in records:
            if r["msg"] == "delivery_abandoned":
                last_status = None
                for prev in records:
                    if prev["msg"] == "http_response_received":
                        last_status = prev.get("status")
                abandons.append((evt_id, r, last_status, records[0].get("endpoint_id")))
    attempts = Counter(r.get("attempt") for _, r, _, _ in abandons)
    reasons = Counter(r.get("reason") for _, r, _, _ in abandons)
    statuses = Counter(st for _, _, st, _ in abandons if st is not None)
    print(f"  Total delivery_abandoned  : {len(abandons)}")
    print(f"  Attempt distribution      : {dict(attempts)}    "
          f"-> every abandon happened on attempt {sorted(attempts)[0] if len(attempts)==1 else '?'}")
    print(f"  Reason distribution       : {dict(reasons)}")
    print(f"  HTTP status at abandonment: {dict(statuses)}")
    print()
    for evt_id, _, status, ep in abandons:
        print(f"  {evt_id}  status={status}   ({ep})")
    print()


def q4_orphans(by_event: dict[str, list[dict]]) -> None:
    print("Q4 — Orphan events (event_submitted -> dispatch_started, no terminal, no crash)")
    print("-" * 60)
    orphans = []
    terminal_msgs = {"delivery_succeeded", "delivery_abandoned", "worker_crashed"}
    for evt_id, records in by_event.items():
        msgs = [r["msg"] for r in records]
        if "event_submitted" in msgs and "dispatch_started" in msgs and \
                not any(m in terminal_msgs for m in msgs) and \
                "http_request_sent" not in msgs and \
                "http_response_received" not in msgs:
            orphans.append((evt_id, records))
    print(f"  Orphan events: {len(orphans)}")
    for evt_id, records in orphans:
        ep = records[0].get("endpoint_id")
        ts = records[0]["ts"]
        print(f"    {evt_id}  endpoint={ep}  ts={ts}  shape: {shape(records)}")
    print()


def q5_crash(by_event: dict[str, list[dict]]) -> None:
    print("Q5 — Worker-crash trace for evt_0e64cc0aaece")
    print("-" * 60)
    records = by_event.get("evt_0e64cc0aaece", [])
    if not records:
        print("  (event not present in log)")
        print()
        return
    head = records[0]
    print(f"  evt_0e64cc0aaece ({head.get('event_type','?')} -> {head.get('endpoint_id','?')}):")
    for r in records:
        extras = []
        for key in ("attempt", "latency_ms", "reason", "note", "status", "total_attempts"):
            if key in r:
                extras.append(f"{key}={r[key]}")
        suffix = ("  " + "  ".join(extras)) if extras else ""
        print(f"    {r['ts']:30s}  {r['msg']:24s}{suffix}")
    print()


def q6_dispatch_latency(by_event: dict[str, list[dict]]) -> None:
    print("Q6 — Dispatch latency (dispatch_started.ts - event_submitted.ts) per 30-min window")
    print("-" * 60)
    buckets: dict[datetime, list[float]] = defaultdict(list)
    for evt_id, records in by_event.items():
        sub = next((r for r in records if r["msg"] == "event_submitted"), None)
        disp = next((r for r in records if r["msg"] == "dispatch_started"), None)
        if not sub or not disp:
            continue
        sub_ts = parse_ts(sub["ts"])
        disp_ts = parse_ts(disp["ts"])
        latency = (disp_ts - sub_ts).total_seconds()
        # Round-down to 30-min boundary.
        bucket = sub_ts.replace(minute=(sub_ts.minute // 30) * 30, second=0, microsecond=0)
        buckets[bucket].append(latency)
    print(f"  {'window_start (UTC)':25s} {'n':>4} {'med(s)':>8} {'p95(s)':>8} {'max(s)':>8}")
    for bucket in sorted(buckets):
        vals = buckets[bucket]
        med = statistics.median(vals)
        # p95 — nearest-rank.
        idx = max(0, int(round(0.95 * len(vals))) - 1)
        p95 = sorted(vals)[idx]
        mx = max(vals)
        flag = "   <-- spike" if p95 > 1.0 else ""
        print(f"  {bucket.isoformat():25s} {len(vals):>4} {med:>8.3f} {p95:>8.3f} {mx:>8.3f}{flag}")
    print()


def q7_per_endpoint(by_event: dict[str, list[dict]]) -> None:
    print("Q7 — Per-endpoint submitted vs succeeded vs abandoned vs orphaned")
    print("-" * 60)
    stats: dict[str, dict[str, int]] = defaultdict(lambda: {"sub": 0, "ok": 0, "aban": 0, "orph": 0})
    terminal_msgs = {"delivery_succeeded", "delivery_abandoned", "worker_crashed"}
    for evt_id, records in by_event.items():
        ep = records[0].get("endpoint_id", "?")
        msgs = [r["msg"] for r in records]
        if "event_submitted" in msgs:
            stats[ep]["sub"] += 1
        if "delivery_succeeded" in msgs:
            stats[ep]["ok"] += 1
        if "delivery_abandoned" in msgs:
            stats[ep]["aban"] += 1
        if "dispatch_started" in msgs and not any(m in terminal_msgs for m in msgs) \
                and "http_request_sent" not in msgs:
            stats[ep]["orph"] += 1
    print(f"  {'endpoint':14s} {'submitted':>10} {'succeeded':>10} {'abandoned':>10} {'orphaned':>10} {'fail_rate':>10}")
    ordered = sorted(stats.items(), key=lambda kv: -(kv[1]["aban"] + kv[1]["orph"]))
    for ep, s in ordered:
        total_fail = s["aban"] + s["orph"]
        rate = (100.0 * total_fail / s["sub"]) if s["sub"] else 0.0
        print(f"  {ep:14s} {s['sub']:>10} {s['ok']:>10} {s['aban']:>10} {s['orph']:>10} {rate:>9.1f}%")
    print()


def q8_payment_failed(by_event: dict[str, list[dict]]) -> None:
    print("Q8 — payment.failed delivery breakdown by endpoint")
    print("-" * 60)
    stats: dict[str, dict[str, int]] = defaultdict(lambda: {"sub": 0, "ok": 0, "aban": 0, "orph": 0})
    terminal_msgs = {"delivery_succeeded", "delivery_abandoned", "worker_crashed"}
    for evt_id, records in by_event.items():
        head = records[0]
        if head.get("event_type") != "payment.failed":
            continue
        ep = head.get("endpoint_id", "?")
        msgs = [r["msg"] for r in records]
        stats[ep]["sub"] += 1
        if "delivery_succeeded" in msgs:
            stats[ep]["ok"] += 1
        if "delivery_abandoned" in msgs:
            stats[ep]["aban"] += 1
        if "dispatch_started" in msgs and not any(m in terminal_msgs for m in msgs) \
                and "http_request_sent" not in msgs:
            stats[ep]["orph"] += 1
    for ep, s in sorted(stats.items()):
        print(f"  {ep:14s}  submitted={s['sub']}  succeeded={s['ok']}  abandoned={s['aban']}  orphaned={s['orph']}")
    print()


def q9_reason_status(by_event: dict[str, list[dict]]) -> None:
    print("Q9 — (reason, last_http_status) on abandonments")
    print("-" * 60)
    pairs: Counter = Counter()
    for evt_id, records in by_event.items():
        for r in records:
            if r["msg"] != "delivery_abandoned":
                continue
            last_status = None
            for prev in records:
                if prev["msg"] == "http_response_received":
                    last_status = prev.get("status")
            pairs[(r.get("reason"), last_status)] += 1
    print("  (reason, last_http_status) -> count")
    for k, v in sorted(pairs.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"    {k}: {v}")
    print()


def q10_slow_http(lines: list[dict]) -> None:
    print("Q10 — http_request_sent with latency_ms >= 1000")
    print("-" * 60)
    slow = [r for r in lines if r.get("msg") == "http_request_sent" and r.get("latency_ms", 0) >= 1000]
    print(f"  http_request_sent events with latency_ms >= 1s: {len(slow)}")
    by_ep = Counter(r.get("endpoint_id") for r in slow)
    print(f"  By endpoint: {dict(by_ep)}")
    for r in sorted(slow, key=lambda r: -r.get("latency_ms", 0)):
        print(f"    {r['ts']}  {r.get('endpoint_id')}  latency_ms={r['latency_ms']}  event_id={r.get('event_id')}")
    print()


def main() -> int:
    if not LOG_PATH.exists():
        print(f"error: log file not found at {LOG_PATH}", file=sys.stderr)
        return 2
    lines, by_event = load_events()
    q1_sanity(lines, by_event)
    q2_shapes(by_event)
    q3_abandonments(by_event)
    q4_orphans(by_event)
    q5_crash(by_event)
    q6_dispatch_latency(by_event)
    q7_per_endpoint(by_event)
    q8_payment_failed(by_event)
    q9_reason_status(by_event)
    q10_slow_http(lines)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
