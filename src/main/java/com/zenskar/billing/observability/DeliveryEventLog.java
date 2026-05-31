package com.zenskar.billing.observability;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Emits the delivery lifecycle as machine-readable JSONL — one line per event,
 * schema-compatible with the legacy {@code webhook_delivery.log.jsonl} so the
 * Part-1 diagnostic ({@code tools/diagnose.py}) runs against the rebuild's own
 * output. Each line is {@code {"ts","level","msg", …context}} where the
 * {@code msg} token is one of the legacy lifecycle names.
 * <p>
 * Routed to a dedicated {@code delivery-events} logger (see logback-spring.xml)
 * with {@code additivity=false}, so this stream stays pure JSON and never mixes
 * with the framework's plain-text operational logs.
 */
@Component
@RequiredArgsConstructor
public class DeliveryEventLog {

    private static final Logger log = LoggerFactory.getLogger("delivery-events");

    private final Clock clock;

    public void eventSubmitted(String eventId, String eventType, String endpointId) {
        emit("INFO", "event_submitted", fields(
                "event_id", eventId, "event_type", eventType, "endpoint_id", endpointId));
    }

    public void dispatchStarted(String eventId, String endpointId, int attempt) {
        emit("INFO", "dispatch_started", fields(
                "event_id", eventId, "endpoint_id", endpointId, "attempt", attempt));
    }

    public void httpRequestSent(String eventId, String endpointId, int attempt, long latencyMs) {
        emit("INFO", "http_request_sent", fields(
                "event_id", eventId, "endpoint_id", endpointId,
                "method", "POST", "attempt", attempt, "latency_ms", latencyMs));
    }

    public void httpResponseReceived(String eventId, String endpointId, int status, long latencyMs) {
        emit("INFO", "http_response_received", fields(
                "event_id", eventId, "endpoint_id", endpointId, "status", status, "latency_ms", latencyMs));
    }

    public void deliverySucceeded(String eventId, String endpointId, int totalAttempts) {
        emit("INFO", "delivery_succeeded", fields(
                "event_id", eventId, "endpoint_id", endpointId, "total_attempts", totalAttempts));
    }

    public void retryScheduled(String eventId, String endpointId, int attempt, String reason, Integer status) {
        emit("INFO", "retry_scheduled", fields(
                "event_id", eventId, "endpoint_id", endpointId,
                "attempt", attempt, "reason", reason, "status", status));
    }

    /** Terminal failure — kept under the legacy {@code delivery_abandoned} token. */
    public void deliveryAbandoned(String eventId, String endpointId, int attempt, String reason, Integer status) {
        emit("WARN", "delivery_abandoned", fields(
                "event_id", eventId, "endpoint_id", endpointId,
                "attempt", attempt, "reason", reason, "status", status));
    }

    public void workerCrashed(String eventId, String endpointId, int attempt, String reason) {
        emit("ERROR", "worker_crashed", fields(
                "event_id", eventId, "endpoint_id", endpointId, "attempt", attempt, "reason", reason));
    }

    public void endpointHealthChanged(String endpointId, String from, String to) {
        emit("WARN", "endpoint_health_changed", fields(
                "endpoint_id", endpointId, "from", from, "to", to));
    }

    // ---- internals ----

    private static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void emit(String level, String msg, Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder(160).append('{');
        writeString(sb, "ts", DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock)));
        sb.append(',');
        writeString(sb, "level", level);
        sb.append(',');
        writeString(sb, "msg", msg);
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue; // omit absent fields, matching the legacy schema
            sb.append(',');
            if (v instanceof Number) {
                sb.append('"').append(e.getKey()).append("\":").append(v);
            } else {
                writeString(sb, e.getKey(), v.toString());
            }
        }
        String line = sb.append('}').toString();
        switch (level) {
            case "ERROR" -> log.error(line);
            case "WARN" -> log.warn(line);
            default -> log.info(line);
        }
    }

    private static void writeString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
