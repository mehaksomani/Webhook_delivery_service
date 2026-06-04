# Webhook delivery service — Zenskar take-home

Two-part submission:

1. **Diagnosis** — root-cause analysis of the legacy service from one day of
   production logs and three customer tickets. See [`diagnosis.md`](diagnosis.md).
2. **Rebuild** — a Spring Boot dispatcher that fixes each of the eight failure
   modes and passes the nine behavioral scenarios S1–S9. See [`design.md`](design.md).

Operational guide for on-call: [`runbook.md`](runbook.md).

## Build and run

Requirements: JDK 17. The Gradle wrapper handles the rest.

```sh
./gradlew test          # scenario + unit tests across S1–S9 + RetryPolicy
./gradlew bootRun       # http://localhost:8080 with H2 console at /h2-console
```

H2 in-memory persistence; restart wipes state. To target Postgres swap
`spring.datasource.url` in
[`src/main/resources/application.properties`](src/main/resources/application.properties).

## Testing the service

Three complementary layers — automated tests, an end-to-end simulator, and
manual `curl`.

### 1. Automated tests (no external setup)

```sh
./gradlew test
```

`src/test/java/.../scenarios/` holds one test class per behavioral scenario
S1–S9, plus `RetryPolicyTest`. Each scenario boots the full Spring context and
points the dispatcher at an in-process **WireMock** server, so the real polling,
leasing, retry, and recovery code paths run against canned HTTP responses. DB
state is reset between tests. This is the authoritative pass/fail gate.

### 2. End-to-end simulation (against a running service)

[`tools/simulate.py`](tools/simulate.py) drives a **live** service through every
scenario over its real HTTP API and asserts the observable outcome of each
(status, attempt count, queue depth, endpoint health, dead-letter listing). It
starts its own zero-dependency mock receiver (Python stdlib only — same spirit
as `diagnose.py`) whose per-path behavior is driven by the
`X-Billing-Delivery-Attempt` header.

Start the service with the **`sim` profile** in one terminal — it permits a
`127.0.0.1` receiver (the SSRF guard blocks private addresses by default) and
compresses retry/lease timings so dead-lettering and crash recovery happen in
seconds:

```sh
./gradlew bootRun --args='--spring.profiles.active=sim'
```

Then, from the repo root, in another terminal:

```sh
python3 tools/simulate.py
```

```text
S1 — Happy path: a healthy endpoint delivers on the first attempt
  [PASS] S1: status=SUCCEEDED attempts=1 (want SUCCEEDED/1)
S2 — Retry with backoff: two transient 500s, then success on attempt 3
  [PASS] S2: status=SUCCEEDED attempts=3 (want SUCCEEDED/3)
...
S6 — Crash recovery: a hung attempt past the lease is re-dispatched as the same event
  [PASS] S6: status=SUCCEEDED attempts=2 outcomes=['CRASH', 'SUCCESS'] (want SUCCEEDED/2 incl CRASH)
...
Result: 10/10 checks passed
```

| Scenario | What the simulator drives | Asserted outcome |
| --- | --- | --- |
| S1 | event to a 200 endpoint | `SUCCEEDED`, 1 attempt |
| S2 | endpoint 500s twice then 200 | `SUCCEEDED`, 3 attempts |
| S3 | a 400 endpoint and an always-500 endpoint | `DEAD_LETTERED` (1 attempt / permanent, 4 attempts / exhausted) |
| S4 | a backed-up slow endpoint + one fast event | fast event `SUCCEEDED` in < 5s |
| S5 | same `event_id` submitted twice | one delivery, receiver hit once |
| S6 | first attempt hangs past the lease | re-dispatched same event, attempts `[CRASH, SUCCESS]` |
| S7 | a batch to a slow endpoint | `queue_depth > 0` |
| S8 | repeated failures to one endpoint | endpoint `UNHEALTHY` |
| S9 | dead-letters from S3 | listed by `?since=` |

The simulator exits non-zero if any check fails. Override targets with the
`BILLING_BASE_URL`, `RECEIVER_HOST`, and `RECEIVER_PORT` env vars.

### 3. Manual smoke test with curl

The `sim` profile (or any running instance reachable from your shell) also lets
you poke the API directly. Note request bodies are **camelCase**; query
responses are snake_case.

```sh
# Register an endpoint (any reachable receiver; httpbin used here as an example)
curl -sX POST localhost:8080/api/v1/endpoints \
  -H 'Content-Type: application/json' \
  -d '{"endpointId":"ep-1","url":"https://httpbin.org/post"}'

# Submit an event (returns 202; processed in the background)
curl -sX POST localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"evt-1","eventType":"invoice.created","endpointId":"ep-1","payload":"{\"amount\":100}"}'

# Inspect the delivery + its attempt history
curl -s localhost:8080/api/v1/deliveries/evt-1

# Other queries
curl -s localhost:8080/api/v1/queues/ep-1/depth
curl -s localhost:8080/api/v1/endpoints/ep-1/status
curl -s "localhost:8080/api/v1/dead-letters?since=2020-01-01T00:00:00Z"
```

## Reproducing the diagnosis

The numbers cited in `diagnosis.md` are emitted by the diagnostic script:

```sh
python3 tools/diagnose.py
```

Zero dependencies. Reads [`webhook_delivery.log.jsonl`](webhook_delivery.log.jsonl)
and prints Q1–Q10 — sanity counts, lifecycle shapes, abandonments, orphans,
the crash trace, dispatch-latency windows, per-endpoint rollups, and the slow
HTTP calls behind Acme's reported 12:15–12:50 latency spike.

## Structured delivery log

The rebuild emits its own delivery lifecycle as JSONL to
`logs/webhook_delivery.jsonl`, using the **same schema** as the legacy log
(`event_submitted`, `dispatch_started`, `http_request_sent`,
`http_response_received`, `delivery_succeeded`, `retry_scheduled`,
`delivery_abandoned`, `worker_crashed`, `endpoint_health_changed`). It's a
dedicated stream (`DeliveryEventLog` → a file-only Logback appender), so it
stays pure JSON and never mixes with operational console logs.

Because the schema matches, the same diagnostic script runs against the
rebuild's own output:

```sh
python3 tools/diagnose.py logs/webhook_delivery.jsonl
```

## Repo layout

```
diagnosis.md                     Part 1 — failure-mode writeup
design.md                        Part 2 — design doc
runbook.md                       On-call debugging flow
tools/diagnose.py                Diagnostic script (stdlib only)
webhook_delivery.log.jsonl       Input log (1199 lines, 241 events)

src/main/java/com/zenskar/billing/
├── BillingApplication.java
├── config/                      DispatcherProperties, HttpClient bean, scheduling
├── domain/                      Delivery, DeliveryAttempt, Endpoint, RetryPolicy, HealthPolicy
├── events/                      Domain events
├── http/BillingHttpClient.java  JDK HttpClient.sendAsync I/O loop (S4)
├── observability/               DeliveryEventLog (JSONL lifecycle stream)
├── pipeline/                    DeliveryScheduler (S4), DispatchService, RecoveryService (S6), EndpointHealthListener (S8)
├── repository/                  Spring Data JPA repositories
├── security/                    UrlPolicy (SSRF guard)
├── service/                     Submit, Query, Endpoint registration + commands
└── web/                         REST controller + DTOs + GlobalExceptionHandler

src/test/java/com/zenskar/billing/scenarios/
├── AbstractScenarioTest.java    Shared Spring context + WireMock + DB reset
├── HappyPathTest.java                          S1
├── RetryWithBackoffTest.java                   S2
├── PermanentFailureDeadLetterTest.java         S3
├── EndpointIsolationTest.java                  S4 ⭐
├── IdempotentResubmitTest.java                 S5
├── CrashRecoveryTest.java                      S6 ⭐
├── QueueDepthQueryTest.java                    S7
├── EndpointHealthQueryTest.java                S8
└── DeadLetterListingTest.java                  S9
```

## Idempotency contract for receivers

Every webhook delivery carries:

```
X-Billing-Event-Id:        <event_id>
X-Billing-Event-Type:      <event_type>
X-Billing-Delivery-Attempt: <attempt_no>
```

Receivers MUST dedupe on `X-Billing-Event-Id`. Full contract text and rationale
in [`design.md`](design.md) under "Idempotency contract / Receiver-side".
