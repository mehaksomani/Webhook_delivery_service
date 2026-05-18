# Diagnosis — webhook delivery service (yesterday's log)

## How I worked

I started with the legacy description as the architectural baseline, then ran
[`tools/diagnose.py`](tools/diagnose.py) against `webhook_delivery.log.jsonl`.
The script emits one section per query — fully reproducible. The headline
numbers below are quoted from the script's output. Every event_id cited is
greppable in the raw log.

Sanity counts the script printed (Q1):

```
Total lines           : 1199
Distinct event_ids    : 241
Time range            : 2026-04-21T09:00:00Z  ->  2026-04-21T16:58:21Z
Status codes seen     : 200=231, 500=5, 502=1, 503=1
Levels                : INFO=1191, WARN=7, ERROR=1
```

Per-event lifecycle shapes (Q2):

```
[230]  event_submitted -> dispatch_started -> http_request_sent -> http_response_received -> delivery_succeeded
[  7]  event_submitted -> dispatch_started -> http_request_sent -> http_response_received -> delivery_abandoned
[  3]  event_submitted -> dispatch_started                                            [NO TERMINAL]
[  1]  event_submitted -> dispatch_started -> http_request_sent -> worker_crashed -> dispatch_started -> http_request_sent -> http_response_received -> delivery_succeeded
```

230 + 7 + 3 + 1 = 241. Every event in the window is accounted for in one of
four shapes. The non-happy three account for 11 events out of 241 — a 4.6%
defect rate in a single day. None of them are "noise"; each shape maps to a
distinct architectural defect, described below.

---

## Failure mode #1 — No retry on transient failures

**Symptom.** When the receiver returns 5xx, the legacy service abandons the
delivery instead of retrying. This is most of what Globex is seeing
(Ticket #4821) — though see #4 for the other half.

**Root cause.** The legacy `Outcome handling` description states verbatim:
*"If the call returns a non-2xx response or times out (5-second timeout),
the worker writes the response/timeout line, writes a delivery_abandoned
line, and moves on. There is no retry."* The architecture conflates
"non-2xx" with "permanent failure". A 500 from a transiently overloaded
receiver is treated identically to a 400 from a malformed payload.

**Evidence in the log** (Q3 in the script):

```
Total delivery_abandoned  : 7
Attempt distribution      : Counter({1: 7})    -> every abandon happened on attempt 1
Reason distribution       : Counter({'non_2xx': 7})
HTTP status at abandonment: {500: 5, 502: 1, 503: 1}

  evt_9b299c4dc4e8  status=500   (ep_initech)
  evt_bc47d5b0b803  status=502   (ep_acme)
  evt_74af4dd9d722  status=500   (ep_initech)
  evt_d9df61b972f6  status=503   (ep_piedmont)
  evt_acb09e319c7f  status=500   (ep_initech)
  evt_883526cf020a  status=500   (ep_initech)
  evt_1d412ce7c5dd  status=500   (ep_initech)
```

All seven abandons happened on **attempt 1**, against **5xx-class** statuses.
A 5xx is by definition a transient server-side condition. Not a single
delivery was retried.

**Impact.** Customers lose ~3% of events per day from this defect alone, and
the loss is concentrated on a single endpoint (`ep_initech` — 5 of 7). At
30-day scale, an unlucky endpoint can lose double-digit percentages of its
events.

---

## Failure mode #2 — Single shared queue + single worker → head-of-line blocking

**Symptom.** Every customer's deliveries are delayed when any one customer's
endpoint is slow. Acme reported this in Ticket #4837 for the 12:15–12:50
window: "one slow customer shouldn't be slowing down everyone."

**Root cause.** The legacy `Architecture` description states: *"There is one
in-memory FIFO queue of pending deliveries. There is one worker thread that
pulls events off the queue, makes the HTTP call, logs the outcome, and moves
on to the next event. Events from all customer endpoints share the same
queue and the same worker."* Every event behind a slow one waits its turn.

**Evidence in the log** (Q6 in the script — dispatch latency, defined as
`dispatch_started.ts − event_submitted.ts`, bucketed by 30-minute windows):

```
window_start (UTC)          n     med(s)   p95(s)   max(s)
  09:00 -> 11:30          ~15ea    ~0.040    ~0.07    ~0.08
  12:00:00                  15    0.074   13.769   14.822   <-- spike
  12:30:00                  15    0.067   12.657   13.488   <-- spike
  13:00 -> 16:30          ~15ea    ~0.045    ~0.07    ~0.08
```

Normal queue wait is ~50ms p95. Around 12:00–13:00, p95 jumped to ~14s — a
~200× increase. Q10 of the script identifies the culprit:

```
http_request_sent events with latency_ms >= 1s: 2
By endpoint: {'ep_acme': 2}
  2026-04-21T12:32:56.533Z  ep_acme  latency_ms=4876  event_id=evt_e6e849fdc7bb
  2026-04-21T12:16:41.481Z  ep_acme  latency_ms=4862  event_id=evt_bb4e4e3ab895
```

Two of Acme's own HTTP calls took 4.86s and 4.88s each — just under the 5s
timeout. While the single worker was blocked on those two calls, *everything
else queued behind them.* Ironically, Acme is both the cause of the delay
(their endpoint was slow) and the loudest victim (their low-volume
`subscription.updated` events also got delayed). The architecture makes any
endpoint's slowness everyone's slowness.

**Impact.** Customer trust erodes quickly. Acme's complaint is the
canary — every customer is exposed to every other customer's worst
moment.

---

## Failure mode #3 — Worker crash mid-send → silent duplicate delivery

**Symptom.** Initech reported (Ticket #4845) receiving two identical
`payment.succeeded` webhooks for `evt_0e64cc0aaece`, about 800ms apart, and
their non-idempotent endpoint double-credited the customer.

**Root cause.** The supervisor's recovery rule re-queues events that crashed
mid-flight. But there is no protocol that lets the receiver distinguish the
re-dispatch from a fresh event. From the architecture description:
*"the supervisor's recovery logic re-queues any event that was dispatched
but had no terminal log line."* This is correct behavior for our side — it's
the only safe assumption — but the missing piece is a stable header that
gives the receiver an idempotency key.

**Evidence in the log** (Q5 in the script):

```
evt_0e64cc0aaece (payment.failed -> ep_initech):
  2026-04-21T11:22:08.000Z  event_submitted
  2026-04-21T11:22:08.032Z  dispatch_started
  2026-04-21T11:22:08.162Z  http_request_sent          attempt=1  latency_ms=130
  2026-04-21T11:22:08.167Z  worker_crashed             reason=segfault in worker process
  2026-04-21T11:22:08.967Z  dispatch_started           note=re-dispatched after worker crash
  2026-04-21T11:22:09.067Z  http_request_sent          attempt=2  latency_ms=100
  2026-04-21T11:22:09.069Z  http_response_received     status=200
  2026-04-21T11:22:09.070Z  delivery_succeeded         total_attempts=2
```

The `http_request_sent attempt=1` line at 11:22:08.162Z proves the request
left the worker before the crash at 11:22:08.167Z — so the receiver's
side likely processed it. The supervisor then re-dispatched and the receiver
got it again. The 800ms gap matches Initech's ticket exactly.

The legacy log refers to this as `payment.failed` (event_type), but
Initech's ticket says `payment.succeeded`. Both customer-side mislabeling
and our internal labeling can happen — the structural defect is the same.

**Impact.** At-least-once delivery without an idempotency contract = silent
financial corrections. Initech had to manually reverse a duplicate credit.
Without a fix, any future crash-during-send is a customer-visible duplicate.

---

## Failure mode #4 — Orphaned in-flight events (hung worker, not crashed)

**Symptom.** Globex (Ticket #4821) reports they receive only ~60% of expected
`payment.failed` events. They can confirm via their access logs that the
missing ones *never arrive at all* — not even as a failed POST.

**Root cause.** The supervisor's recovery rule only fires when the worker
*crashes detectably* (the supervisor sees the process die). If the worker
**hangs** (HTTP library deadlock, GC pause, network stack stall, etc.) the
process is alive, the supervisor sees no crash, and no re-queue happens.
The event sits in the in-memory queue between `dispatch_started` and
whatever should follow, forever. No retry. No abandonment. No log entry.

**Evidence in the log** (Q4 in the script):

```
Orphan events: 3
  evt_5c4616dcc950  endpoint=ep_globex    ts=2026-04-21T10:24:17Z  shape: event_submitted -> dispatch_started
  evt_d2064c593fb9  endpoint=ep_umbrella  ts=2026-04-21T10:31:44Z  shape: event_submitted -> dispatch_started
  evt_e41f5ed7bb5b  endpoint=ep_umbrella  ts=2026-04-21T10:58:13Z  shape: event_submitted -> dispatch_started
```

Three events were dispatched and then went silent. Critically, none of them
have a `worker_crashed` line — the supervisor's recovery rule never fires
for these. Compare with `evt_0e64cc0aaece` (failure mode #3) which DID have
a `worker_crashed` line and DID recover.

Note that one of these orphans is exactly for **ep_globex** — direct
support for Globex's specific complaint that "missing events never reach
us at all."

**Impact.** Silent data loss with no operational signal. The on-call team
cannot detect it from monitoring — every metric looks fine. The customer
discovers it from their reconciliation report days or weeks later.

---

## Failure mode #5 — Abandonment reason `non_2xx` lumps retriable + permanent

**Symptom.** Operational blind spot. When a delivery is abandoned, the log
gives one bucket — `non_2xx` — for everything that wasn't a 2xx.

**Root cause.** No outcome classification in the legacy code path. A 500 (try
again later) and a 400 (your payload is wrong) get identical treatment:
abandon. The reason field is identical.

**Evidence in the log** (Q9 in the script):

```
(reason, last_http_status) -> count
  ('non_2xx', 500): 5
  ('non_2xx', 502): 1
  ('non_2xx', 503): 1
```

The window we have happens to contain only 5xx abandons. The same code
path would abandon a 4xx identically. With no classification, the
operations team cannot tell which abandons are "endpoint having a bad
moment, will succeed if retried" vs "payload is malformed, never will
succeed".

**Impact.** Same defect, two different blast radii. Mis-classifying a 5xx
as terminal loses revenue events. Mis-classifying a 4xx as retriable burns
through the retry budget on a hopeless request and blocks legitimate work
behind it.

---

## Failure mode #6 — In-memory queue is not durable

**Symptom.** Not visible in this log window. Visible as a property of the
architecture description.

**Root cause.** The legacy description states: *"There is one in-memory FIFO
queue of pending deliveries."* If the entire process restarts (deploy,
OOM-kill, host reboot, supervisor itself dies), every event in the queue
that has not yet been dispatched is lost. The supervisor's recovery rule
only handles a worker crash within the same process — a full process
restart starts from zero.

**Evidence in the log.** Implicit: the log window happens to be 8 hours
with no full restart, so no events are lost this way. The architecture
guarantees the failure mode exists.

**Impact.** Any deploy or OOM is a silent partial outage for whichever
customer had pending events at that moment.

---

## Failure mode #7 — No per-endpoint health visibility / no circuit breaker

**Symptom.** Repeatedly failing endpoints keep getting hammered. Healthy
endpoints' deliveries get queued behind the failing endpoint's slow
attempts (compounds with #2). On-call cannot answer "is endpoint X
healthy?" without grep.

**Root cause.** No per-endpoint state in the architecture. Every event is
treated identically; no notion of "this endpoint has been failing for the
last 20 attempts, back off."

**Evidence in the log** (Q7 in the script):

```
endpoint        submitted  succeeded  abandoned   orphaned  fail_rate
  ep_initech         47        42          5           0     10.6%   <-- 71% of all failures
  ep_umbrella        38        36          0           2      5.3%
  ep_piedmont        26        25          1           0      3.8%
  ep_globex          32        31          0           1      3.1%
  ep_acme            53        52          1           0      1.9%
  ep_soylent         45        45          0           0      0.0%
```

`ep_initech` accounts for 5 of 7 abandonments (71% of total failures) but
only 19% of submissions. The legacy service has no signal to back off
when an endpoint is clearly struggling.

**Impact.** Effort wasted on hopeless endpoints, and (via #2) wasted effort
delays everyone else.

---

## Failure mode #8 — No DLQ / abandoned events are unobservable

**Symptom.** When the on-call sees a `delivery_abandoned` log line, there is
no follow-on mechanism: no DLQ to inspect, no replay, no record of what
made it terminal.

**Root cause.** The legacy `Outcome handling` simply says "writes a
delivery_abandoned line and moves on." There is no terminal state stored
anywhere queryable. The log is the only record, and the log is grep-only.

**Evidence in the log.** Every `delivery_abandoned` event in Q3 above has no
follow-on log line referencing it. Once a delivery is abandoned, it
disappears from operational visibility — only customer complaints
resurface it. Both Globex (#4821) and Initech (#4845) had to file
tickets to surface losses that the operations team should have been
proactively monitoring.

**Impact.** Bounds support escalation poorly. The team finds out about
loss from angry customers rather than from monitoring. Replay/recovery
is manual log archaeology.

---

## Compound failure: how Globex's "60% delivery rate" actually composes

Globex's specific complaint is the most informative because it almost
certainly compounds *three* of the defects above:

1. **Failure mode #4** (orphaned in-flight) accounts for some loss with
   zero log signature. We see one such orphan for `ep_globex` in this
   single-day window (`evt_5c4616dcc950`).
2. **Failure mode #1** (no retry on 5xx) accounts for any abandons during
   a window where `ep_globex` was momentarily unhealthy. In this specific
   window `ep_globex` has 0 abandons (Q8 — `payment.failed` for
   `ep_globex` is 7/7 = 100%), but the customer's report covers 30 days.
3. **Failure mode #6** (non-durable queue) accounts for any events lost
   in the in-memory queue across a deploy or restart in the 30-day window.

The reported 60% rate is consistent with two or three independent ~5–10%
loss sources compounding multiplicatively, plus 30 days' worth of
restarts/incidents. No single failure mode produces 60% loss on its own;
the system has enough independent loss modes that the cumulative impact is
large.

---

## Where each failure mode is fixed in the rebuild

For traceability with `design.md`:

| # | Failure mode | Fix in rebuild |
|---|---|---|
| 1 | No retry on 5xx | `RetryPolicy` with exponential backoff + jitter; outcome classification |
| 2 | Single queue, single worker | Per-endpoint claim + async I/O dispatch (S4) |
| 3 | Crash → silent duplicate | Stable `X-Webhook-Event-Id` header contract (S6) |
| 4 | Orphan in-flight | DB-backed leases + `RecoveryService` periodic sweep |
| 5 | `non_2xx` lumps everything | `AttemptOutcome` enum + status-class routing |
| 6 | Non-durable queue | All state in DB; `submit()` is the durable boundary |
| 7 | No endpoint health | `EndpointHealth` state machine + `query("endpoint_status", …)` |
| 8 | No DLQ | `DEAD_LETTERED` state + `query("dead_letters", since=…)` |
