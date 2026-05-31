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
./gradlew test          # 16 tests across S1–S9 + RetryPolicy
./gradlew bootRun       # http://localhost:8080 with H2 console at /h2-console
```

H2 in-memory persistence; restart wipes state. To target Postgres swap
`spring.datasource.url` in
[`src/main/resources/application.properties`](src/main/resources/application.properties).

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
