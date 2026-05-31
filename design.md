# Design — webhook dispatcher rebuild

## One-paragraph summary

A single bounded context, *Webhook Delivery*, with three aggregates
(`Delivery`, `DeliveryAttempt`, `Endpoint`) backed by a relational outbox.
`submit(event)` is a durable database write that returns immediately. A
scheduler polls the outbox for due deliveries, claims them per-endpoint
under a lease, and fires HTTP requests through Java's async `HttpClient`
so one slow endpoint cannot block another (the S4 gate). Each request
carries an `X-Billing-Event-Id` header that gives receivers a stable
idempotency key — that header is the S6 contract. Transient failures
retry on an exponential-backoff-with-jitter schedule; permanent failures
and exhausted retries terminate to a dead-letter state that is queryable
by timestamp. Endpoint health is a sliding-window signal
(`HEALTHY` / `UNHEALTHY`) updated as side effects of attempts.

## Bounded context and aggregates

```
┌──────────────────────────────────────────────────────────────┐
│                       Webhook Delivery                       │
│                                                              │
│   Aggregate: Delivery (root)                                 │
│     - identity = event_id  (caller-supplied; idempotency)    │
│     - status = PENDING | IN_FLIGHT | SUCCEEDED | DEAD_LETTER │
│     - attempts: List<DeliveryAttempt>  (history)             │
│     - schedule: next_attempt_at + lease (holder + expires)   │
│                                                              │
│   Aggregate: Endpoint (root)                                 │
│     - identity = endpoint_id                                 │
│     - url, health                                            │
│                                                              │
│   Pure-domain policies (no Spring imports):                  │
│     - RetryPolicy    (backoff schedule + jitter)             │
│     - HealthPolicy   (window → HEALTHY/UNHEALTHY)            │
└──────────────────────────────────────────────────────────────┘
```

The package tree — organised by responsibility, not by DDD layer name:

```
com.zenskar.billing
├── config/        BillingConfig (bean wiring) + BillingProperties (nested records)
├── domain/        Delivery, DeliveryAttempt, Endpoint + enums + RetryPolicy + HealthPolicy
│                  — framework-free; no Spring imports
├── events/        DeliverySucceeded, DeliveryFailed, DeadLettered, HealthChanged
├── http/          BillingHttpClient (JDK async) + HttpDeliveryResult
├── repository/    Spring Data repository interfaces
├── security/      UrlPolicy (SSRF guard for outbound delivery targets)
├── service/       API-facing services called from the controller:
│                  Submit, Endpoint, Query + their command records
├── pipeline/      Background runtime (no controller ever calls these directly):
│                  DeliveryScheduler (@Scheduled poll loop) + DispatchService
│                  (start/record attempt) + DispatchTask + RecoveryService
│                  (stale-lease sweep) + EndpointHealthListener
│                  (AFTER_COMMIT listener that maintains endpoint health)
└── web/           BillingController (single REST controller) + ApiExceptions
                   + GlobalExceptionHandler + dto/ for request and view records
```

The split between `service/` and `pipeline/` is operational, not theoretical:
anything in `service/` runs synchronously when an HTTP request arrives;
anything in `pipeline/` runs on its own schedule (poll tick, lease expiry,
domain-event listener). Tests interact almost exclusively with `service/`;
`pipeline/` is exercised end-to-end via the scheduler's regular ticks.

## Queueing strategy

The queue is the `deliveries` table. There is **no in-memory queue.**
`submit()` is a single `INSERT` (or no-op if the `event_id` is already
present), and that INSERT is the durability boundary. Everything else
operates against the row.

Two scheduling-relevant indexes:

```
(endpoint_id, status, next_attempt_at)  -- "what's due for endpoint X now"
(status, lease_expires_at)              -- "what leases have expired"
```

The scheduler runs every 200ms (configurable). Each tick:

```
1. SELECT DISTINCT endpoint_id WHERE status=PENDING AND next_attempt_at <= now
   -> list of endpoints with due work

2. For each endpoint with due work:
     slots = max_in_flight_per_endpoint - in_flight_counter[endpoint]
     if slots <= 0: skip                 -- back-pressure per endpoint
     SELECT * FROM deliveries
       WHERE endpoint_id=? AND status=PENDING AND next_attempt_at <= now
       ORDER BY next_attempt_at, submitted_at
       LIMIT slots
     for each candidate:
       atomic claim()                    -- UPDATE...WHERE status=PENDING
       if claimed: fire HTTP async; in_flight_counter[endpoint] += 1
```

The claim is an `UPDATE ... WHERE status=PENDING` query — atomic at the DB
level, no `SELECT FOR UPDATE` needed. If two workers race, one wins (rows
updated = 1), the other gets zero and moves on.

**Why per-endpoint:** the legacy service had one global queue and one
worker. A single slow request from one endpoint stalled deliveries for
*every* endpoint (failure mode #2 in `diagnosis.md`). By iterating
endpoints at the top of the loop, every endpoint gets fair access on
every tick. The per-endpoint in-flight cap then prevents one endpoint
from monopolizing the I/O thread pool.

**Why the scheduler stays unblocked:** the HTTP request itself uses
`HttpClient.sendAsync(...)`, which returns a `CompletableFuture`. The
scheduler thread fires the request and immediately moves to the next
candidate. Slow responses sit in the JDK HTTP I/O loop; they never block
the scheduler from servicing the next endpoint. **This is why the spec's
"must pass even if your service is single-threaded" note works:
concurrency comes from non-blocking I/O, not from threads.**

## Retry policy

Configured via `webhook.retry.*` in `application.properties`. Production
defaults:

```
max-attempts        : 5      (attempt 1 + 4 retries)
schedule-seconds    : 1, 5, 25, 120, 600     -- after attempts 1..4 respectively
jitter-ratio        : 0.2                    -- multiplicative ± 20%
```

This is a hard-coded schedule, not a formula. Hard-coded is more
defensible: you can read it and know exactly what's going to happen for a
given event. The schedule deliberately stretches to 10 minutes because
endpoint outages typically last minutes, not seconds; retrying every
second is just noise.

`RetryPolicy.nextDelay(completedAttemptNo)` returns:

- `Optional<Duration>` of the delay when there is still budget
- `Optional.empty()` when retries are exhausted — that signals dead-letter

Jitter is **multiplicative ± 20%**. If the base delay is 5s, the actual
delay is uniformly between 4s and 6s. This breaks up the synchronized
retry storm when many subscribers' endpoints come back up at the same
moment after a shared upstream outage.

**Outcome classification** (in `AttemptOutcome.fromStatus`):

| HTTP outcome | Classification | Retry behavior |
|---|---|---|
| 2xx | SUCCESS | terminal — SUCCEEDED |
| 408, 429 | RETRIABLE_FAILURE | retry on schedule |
| Other 4xx | PERMANENT_FAILURE | immediate DEAD_LETTERED, retry budget untouched |
| 5xx | RETRIABLE_FAILURE | retry on schedule |
| timeout, connect/IO error | RETRIABLE_FAILURE | retry on schedule |

The "permanent 4xx goes straight to DLQ" path is the direct fix for
failure mode #5 in `diagnosis.md`: a malformed payload never succeeds, so
it should not consume retry budget that could have been spent on a 5xx.

## Idempotency contract

Two distinct idempotency contracts. Both matter.

### Server-side: `submit()` is idempotent on `event_id`

The `Delivery.event_id` column is the primary key. `SubmitService.submit`:

1. Looks up by `event_id`. If found → return existing record, no-op.
2. If not found, INSERTs. A concurrent submit that races on the same
   `event_id` will hit a `DataIntegrityViolationException`; the catch
   block reads back the surviving row.

The race is real — two HTTP submits hitting two app instances at the
exact same moment can both pass the lookup and both attempt INSERT. The
DB's unique constraint makes exactly one win; the loser converts its
exception into a read of the survivor and returns that. This passes S5
even under concurrency (covered by `IdempotentResubmitTest`).

### Receiver-side: `X-Billing-Event-Id` header

Every outbound POST carries:

```
X-Billing-Event-Id:          <event_id>
X-Billing-Event-Type:        <event_type>
X-Billing-Delivery-Attempt:  <n>
```

**The contract published to subscribers (would live in customer-facing
docs):**

> Each webhook delivery carries an `X-Billing-Event-Id` HTTP header. This
> value is stable across retries and recovery from worker crashes — if
> the same `event_id` arrives twice, those are the same logical event
> and must be deduplicated on your side. Your endpoint MUST be idempotent
> on `X-Billing-Event-Id`.

This is the direct fix for Initech's ticket (#4845, failure mode #3).
Without this header, a receiver has no way to distinguish a fresh
delivery from a re-delivery after our worker crashed mid-send. With it,
the receiver checks an `event_id` table on their side and rejects
duplicates.

The `X-Billing-Delivery-Attempt` header is for the runbook: when a
customer asks "is this attempt 1 or a retry?", the answer is in their
own request log.

## Endpoint state model

Each `Endpoint` aggregate caches a health value: `HEALTHY` or `UNHEALTHY`.
The `query("endpoint_status", endpoint_id)` call returns this cached value
in O(1) — no aggregation at query time.

The cache is maintained by `EndpointHealthListener`, which listens for
`DeliverySucceededEvent` and `DeliveryFailedEvent` and runs in a separate
transaction (`@Async @TransactionalEventListener` style, here as
`@Async @EventListener @Transactional(REQUIRES_NEW)`). On each event:

```
1. Load the last N completed attempts for this endpoint (N = window_size)
2. Count failures in that window + count trailing consecutive failures
3. Ask HealthPolicy.evaluate(failures, consecutive) → HEALTHY | UNHEALTHY
4. Apply transition, save endpoint
5. If state changed, publish EndpointHealthChangedEvent
```

`HealthPolicy` is pure domain — no Spring imports. Production thresholds:

```
window-size            : 20
failure-threshold      : 10     -- "≥10 failures in last 20 → UNHEALTHY"
consecutive-threshold  : 5      -- "≥5 in a row failures → UNHEALTHY"
```

The two `UNHEALTHY` triggers are deliberate: a high-volume endpoint may
accumulate 10 failures of 20; a low-volume endpoint may only see 5 attempts
but all 5 fail. Either signal marks the endpoint unhealthy.

A future enhancement is "respect health during scheduling": if an endpoint
is `UNHEALTHY`, the scheduler could defer its pending events for a cooldown
and then allow a single probe. The cached signal is already there; the
scheduler-side check is small. Left out of v1 to keep test timing
deterministic.

## Crash safety (S6)

Three properties, each load-bearing:

1. **Durable claim.** Before the HTTP call goes out, the `Delivery` row
   has been committed as `IN_FLIGHT` with a lease (`lease_holder` and
   `lease_expires_at`). No other worker will claim the same row because
   the claim's `WHERE status=PENDING` clause won't match.

2. **Lease expiry.** If the worker dies (crash, hang, OOM, network
   partition) the lease is just a timestamp — it doesn't care that the
   process is gone. The `RecoveryService`:
   - runs on `ApplicationReadyEvent` so a fresh start sweeps prior
     in-flight rows;
   - runs again on the dispatcher's poll interval as a steady-state
     safety net for hung workers.

   For each stale-leased delivery, it marks the in-progress attempt as
   `outcome=CRASH` (so the attempt history is honest, not a missing line)
   and transitions the delivery back to `PENDING` with
   `next_attempt_at=now`. The next scheduler tick picks it up as
   attempt N+1.

3. **Receiver header.** The crash recovery just described re-dispatches
   with the same `event_id`. The receiver may have processed the first
   attempt (the network already happened). They use the
   `X-Billing-Event-Id` header to dedupe on their side.

**Why this is stronger than the legacy supervisor.** The legacy
supervisor only fires on a *detected* crash (process exit, segfault).
Failure mode #4 in `diagnosis.md` shows three events that hung silently
with no crash signal — those slip through the legacy recovery. The lease
mechanism is timestamp-based: a hung worker's lease expires just like a
crashed worker's lease expires.

## Query API

Per the spec, the function-level interface is `query(...)`. I model that
as a small Java service interface, exposed externally via REST:

| Spec | Java | REST |
|---|---|---|
| `submit(event)` | `SubmitService.submit(SubmitEventCommand)` | `POST /api/v1/events` |
| `query("queue_depth", endpoint_id)` | `QueryService.queueDepth(String)` | `GET /api/v1/queues/{endpointId}/depth` |
| `query("endpoint_status", endpoint_id)` | `QueryService.endpointStatus(String)` | `GET /api/v1/endpoints/{endpointId}/status` |
| `query("dead_letters", since=...)` | `QueryService.deadLettersSince(Instant)` | `GET /api/v1/dead-letters?since=…` |
| (runbook helper, not in spec) | `QueryService.findDelivery(String)` | `GET /api/v1/deliveries/{eventId}` |

Endpoint registration (`POST /api/v1/endpoints`) is also exposed; the
spec is silent on this but tests need it.

## Trade-offs I made on purpose

- **JPA + H2 in-memory for local/test.** Simpler than Testcontainers,
  matches the reference repo. H2 in PostgreSQL compatibility mode keeps
  the schema portable. For production, swap the JDBC URL and you have
  Postgres.

- **`@Scheduled` poll loop, not a message broker.** A real broker (SQS,
  RabbitMQ, Kafka) would handle the per-endpoint claim, the lease, and
  the visibility timeout natively. But the spec says the replacement
  doesn't need to be a network service, and "I built a broker" is the
  wrong shape of work for a take-home. The DB-as-queue pattern scales
  meaningfully (Postgres can sustain thousands of poll-and-claim
  operations per second on a modest box). The 100x section below covers
  what would change.

- **One `Delivery` row per `event_id` regardless of retries.** I keep the
  Delivery as the durable identity; attempts are children. This makes the
  S6 "same Delivery vs new Delivery" contract clean: it's the same row,
  with an honest attempt history.

- **Optimistic locking (`@Version`) on `Delivery` and `Endpoint`.** Cheap
  insurance against the rare race where the scheduler and the recovery
  sweep both try to update the same delivery. The loser's transaction
  retries; no data corruption.

- **Lombok for entity boilerplate, named methods for state transitions.**
  Lombok generates getters and no-arg constructors for JPA. State changes
  go through named methods (`succeed`, `scheduleRetry`, `deadLetter`,
  `releaseLease`) — these document the legal lifecycle and stop callers
  from setting `status` directly.

- **SSRF guard on delivery targets.** Because the dispatcher POSTs to
  caller-supplied URLs, `UrlPolicy` (in `security/`) restricts targets to
  `http`/`https` and — with `billing.security.block-private-addresses=true`
  — rejects hosts that resolve to loopback, link-local (incl. the
  `169.254.169.254` cloud-metadata endpoint), or RFC-1918 ranges. It runs at
  registration (reject early) and again at delivery time (DNS rebinding
  defense). Tests disable address-blocking so WireMock on `127.0.0.1` stays a
  valid target.

- **Structured JSONL delivery log alongside the query API.** Observability is
  not only the DB/query API: `DeliveryEventLog` emits the delivery lifecycle as
  JSONL (`logs/webhook_delivery.jsonl`) in the same schema as the legacy log, so
  `tools/diagnose.py` runs against the rebuild's own output. The query API is for
  live "what's the state now?"; the log is for post-hoc "what happened?" forensics
  when the app may be down. It's a dedicated file-only stream so it stays pure JSON.

- **No authentication on the API yet.** The inbound REST surface is
  currently open. In production this needs an API key / mTLS in front of it,
  and the read endpoints (`/dead-letters`, `/deliveries/{id}`) must be
  operator-only since they expose payloads. Called out as the top remaining
  security gap.

- **No HMAC payload signing in v1.** A real production webhook service
  should HMAC-sign every payload so receivers can verify origin. Out of
  scope here; it's a header + a shared secret per endpoint, plus a
  worked example in customer docs. Two-hour follow-up, not redesign — and
  lower priority than the SSRF guard above and inbound auth.

- **No unhealthy-endpoint scheduling skip in v1.** Health *tracking* is in;
  health-based *throttling* is one if-statement at the top of
  `dispatchEndpoint`. Left out to keep test timing deterministic; called
  out explicitly in design.

## What changes at 100x scale

Three things break first. **(1)** Poll-and-claim on a single Postgres
gets contentious past a few thousand claims/second; you replace the
scheduler with a real broker (SQS for managed simplicity, Kafka for
ordering guarantees) and the `deliveries` table becomes the durable
record-of-truth rather than the work queue. **(2)** Per-endpoint
isolation breaks if a few endpoints dominate volume — you partition the
work across multiple dispatcher instances keyed on `endpoint_id` so one
instance's slow endpoints never starve another's healthy traffic.
**(3)** The receiver-side idempotency burden grows: at high volume,
customers cannot keep an unbounded `event_id` table, so the header gains
a `Date` component and the contract becomes "dedupe on `event_id` within
the last 24 hours". HMAC signing, payload encryption at rest, and
per-endpoint rate limits all become table-stakes at this scale rather
than v2 features.
