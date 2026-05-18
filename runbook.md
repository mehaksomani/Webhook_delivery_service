# Runbook — "we're not getting webhooks"

This runbook covers what to do when an enterprise customer reports missing
or duplicate webhook deliveries. It assumes the rebuilt dispatcher
described in `design.md`. Every step references something the rebuild
made queryable that the legacy service didn't.

## 0. Ask the customer for the failure shape

Three different shapes, three different paths through this runbook:

| Customer says | This runbook section |
|---|---|
| "events never reach us at all" | §1 — silent loss |
| "we got the same event twice" | §2 — duplicates |
| "events arrive late / in bursts" | §3 — delays |

Whatever the shape, ask the customer for:

- specific `event_id`s where they can confirm the failure
- the time window they're investigating
- whether their endpoint was healthy in that window (especially: any 5xx?)

## 1. Silent loss — "events never reach us"

Most common cause: events that succeeded our side but the customer's
firewall/proxy dropped them. Rule that out by checking our records.

### 1a. Look up the events by `event_id`

```
GET /api/v1/deliveries/{eventId}
```

Returns the full `DeliveryView` including every attempt with its
outcome, status code, latency, and error message.

- If `status=SUCCEEDED`: we sent it, the receiver returned 2xx.
  The loss is on the customer's side. Share the attempt timestamp and
  status code; ask their team to grep their access logs at that time.
  Stop.

- If `status=DEAD_LETTERED`: we exhausted retries (or hit a permanent
  4xx). Read `dead_letter_reason` from the response. Continue at §1b.

- If `status=PENDING` or `IN_FLIGHT`: still working on it. Check the
  endpoint health (§1c).

- If 404 — we never received a `submit()` for this `event_id`. Upstream
  problem (the billing service that calls `submit` is the culprit, not us).

### 1b. The events were dead-lettered. Why?

List recent dead-letters for context:

```
GET /api/v1/dead-letters?since=2026-04-21T00:00:00Z
```

Look at `dead_letter_reason`:

- `permanent_failure (status=400)` → customer's endpoint rejected the
  payload. Get one example and ask them why their endpoint returned 400.
- `permanent_failure (status=401|403)` → auth failed. Either we have the
  wrong creds, or theirs rotated. Share the attempt timestamps.
- `max_attempts_exceeded` → all retries (5 by default, see `design.md`)
  hit 5xx. Check the endpoint's health history (§1c) — most likely a
  multi-hour outage on their side.

### 1c. Is the endpoint healthy now?

```
GET /api/v1/endpoints/{endpointId}/status
```

Returns one of: `HEALTHY`, `DEGRADED`, `TRIPPED`.

- `TRIPPED` → the endpoint has been failing badly. Look at the recent
  attempts (in `GET /api/v1/deliveries/{eventId}` for the dead-lettered
  events) to see what the receiver was returning. Almost certainly an
  outage on the customer side. Communicate that to them with evidence
  (timestamps + status codes).
- `DEGRADED` → some recent failures, mostly working. Ask the customer
  about flaky behavior at the timestamps in the attempt history.
- `HEALTHY` → not the endpoint itself. Move on to §1d.

### 1d. Check the queue depth

```
GET /api/v1/queues/{endpointId}/depth
```

- High depth (> 100, increasing) → we are backed up for this endpoint.
  Either the endpoint is slower than its delivery rate (talk to capacity
  planning) or we have a problem in the dispatcher (check logs for
  scheduler tick failures).
- Zero or low → not a queueing issue. The loss is elsewhere; the most
  likely cause is the customer's network path.

## 2. Duplicates — "we got the same event twice"

Replacement service behavior: **at-least-once delivery with a stable
idempotency key.** The customer needs to dedupe on `X-Billing-Event-Id`.
Receiver-side dedupe is the contract published in `design.md`.

### 2a. Confirm both deliveries belonged to the same event

Ask the customer for both timestamps. Look up the event:

```
GET /api/v1/deliveries/{eventId}
```

If the attempt history shows `attempt 1: outcome=CRASH` and
`attempt 2: outcome=SUCCESS`, the duplicate is by design: a worker
crashed mid-send, we re-dispatched. The receiver saw both HTTP requests.
The contract was honored — both requests carried the same
`X-Billing-Event-Id` header.

Reply to the customer with:

- the event_id
- the two attempt timestamps
- "this was a crash-recovery re-delivery. Our service guarantees
  at-least-once delivery; receivers must dedupe on `X-Billing-Event-Id`.
  Both requests carried `X-Billing-Event-Id: {eventId}` — please confirm
  you're checking for duplicates on that header."

### 2b. If they DID see two different `event_id`s

Then this is a duplicate at the *source* — the upstream billing service
called `submit()` twice with different `event_id`s for the same logical
event. Not a dispatcher problem. Escalate to billing.

## 3. Delays — "events are arriving late or in bursts"

Most often endpoint-specific: their endpoint slowed down and other
events queued up behind it (but ONLY for that endpoint, thanks to
per-endpoint isolation). The legacy service's failure mode #2 — where
one customer's slowness affected everyone — does not apply here.

### 3a. Look at endpoint health and queue depth for the affected endpoint

```
GET /api/v1/endpoints/{endpointId}/status
GET /api/v1/queues/{endpointId}/depth
```

- `DEGRADED` or `TRIPPED` with high queue depth → their endpoint is the
  bottleneck. Share their own status code + latency data from recent
  `DeliveryView`s.
- `HEALTHY` with high queue depth → unusual; check dispatcher logs.
- `HEALTHY` with low queue depth → the delay was earlier (transient
  burst that drained). Confirm with timestamps from a specific
  `event_id`.

### 3b. Look for slow attempts in the time window

Use the diagnostic mindset that produced the original `tools/diagnose.py`
(specifically Q6 — dispatch latency bucketed by time). The production
equivalent for this rebuilt service is the `DeliveryAttempt` table:

```sql
SELECT date_trunc('minute', started_at)              AS bucket,
       percentile_cont(0.5)  WITHIN GROUP (ORDER BY latency_ms) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95,
       count(*)
FROM delivery_attempts
WHERE endpoint_id = ?
  AND started_at BETWEEN ? AND ?
GROUP BY bucket
ORDER BY bucket;
```

A latency spike in this output, scoped to one endpoint, is the
fingerprint of an endpoint-side problem. A latency spike across multiple
endpoints points at our dispatcher or the network in between.

## 4. Recovery operations

### Replay a dead-lettered event

The behavioral spec doesn't require an admin API for this; for a quick
replay, manually transition a dead-lettered row back to PENDING:

```sql
UPDATE deliveries
SET status='PENDING',
    next_attempt_at = now(),
    dead_lettered_at = null,
    dead_letter_reason = null,
    lease_holder = null,
    lease_expires_at = null
WHERE event_id = ?
  AND status = 'DEAD_LETTERED';
```

Reset `attempt_count` too if you want a fresh retry budget:

```sql
UPDATE deliveries SET attempt_count = 0 WHERE event_id = ?;
```

The scheduler will pick it up on the next tick.

### Force-trip an endpoint (planned maintenance)

If a customer tells you ahead of time they're doing maintenance:

```sql
UPDATE endpoints
SET health = 'TRIPPED',
    tripped_until = now() + interval '1 hour'
WHERE endpoint_id = ?;
```

(In a future version this would be a proper admin endpoint; for now the
DB write is acceptable for the on-call workflow.)

## 5. What changed vs. the legacy debug flow

Before this rebuild, "we're not getting webhooks" meant:

- grep yesterday's log for `event_submitted` lines mentioning the
  customer's endpoint
- correlate with `dispatch_started` to see what happened
- if no `delivery_*` follow-up line, no answer
- if `delivery_abandoned`, no detail beyond `reason=non_2xx`
- if the orphan pattern, complete silence

The rebuild gives every event a queryable row with full attempt history,
a typed terminal state, an endpoint health signal, and a structured DLQ.
Every step in this runbook is a single HTTP call rather than a grep
against a log file.
