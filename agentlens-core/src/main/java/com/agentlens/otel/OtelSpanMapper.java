package com.agentlens.otel;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ROADMAP P2-3 (phase 1): maps a unified {@link AgentRun} onto OpenTelemetry
 * spans and renders the OTLP/JSON trace-export payload. One run = one trace:
 * the run itself is the root span, every {@link AgentEvent} becomes a child
 * span in trace order. Any OTLP/HTTP collector (Jaeger, Tempo, Grafana,
 * Phoenix/Langfuse OTLP endpoints) can then display AgentLens traces while
 * AgentLens itself stays free of the OpenTelemetry SDK.
 *
 * <p>Encoding follows the proto3 JSON mapping for OTLP: 16-byte trace ids and
 * 8-byte span ids as hex strings, int64 nanosecond timestamps as strings,
 * enum fields (kind, status code) as numbers.
 *
 * <p>Determinism: traceId/spanIds are derived from the run id via SHA-256, so
 * re-exporting a persisted run always yields the identical trace - exports are
 * as reproducible and auditable as the evaluations themselves.
 */
public final class OtelSpanMapper {
    public static final String SCOPE_NAME = "agentlens";
    public static final String SCOPE_VERSION = "0.1.0";

    private static final int KIND_INTERNAL = 1;
    private static final int KIND_SERVER = 2;
    private static final int KIND_CLIENT = 3;
    private static final int STATUS_OK = 1;
    private static final int STATUS_ERROR = 2;
    private static final int ANSWER_ATTR_LIMIT = 512;

    private OtelSpanMapper() {}

    /** Builds the full OTLP/JSON payload: {"resourceSpans":[{resource, scopeSpans:[{spans}]}]}. */
    public static Map<String, Object> toOtlpJson(AgentRun run) {
        String traceId = traceId(run.runId());
        String rootSpanId = spanId(run.runId(), -1);

        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(rootSpan(run, traceId, rootSpanId));
        for (int i = 0; i < run.events().size(); i++) {
            spans.add(eventSpan(run.events().get(i), i, run.runId(), traceId, rootSpanId));
        }

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scope", pairs("name", SCOPE_NAME, "version", SCOPE_VERSION));
        scope.put("spans", spans);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("attributes", List.of(
                attr("service.name", "agentlens"),
                attr("agentlens.task_id", run.taskId()),
                attr("agentlens.run_id", run.runId()),
                attr("agentlens.status", run.status().name())));

        Map<String, Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put("resource", resource);
        resourceSpans.put("scopeSpans", List.of(scope));
        return Map.of("resourceSpans", List.of(resourceSpans));
    }

    private static Map<String, Object> rootSpan(AgentRun run, String traceId, String spanId) {
        long startNanos = toNanos(run.startedAt());
        Map<String, Object> span = baseSpan(traceId, spanId, null,
                "agentlens.run", KIND_SERVER, startNanos, startNanos + run.durationMs() * 1_000_000L);
        span.put("attributes", List.of(
                attr("agentlens.total_tokens", run.totalTokens()),
                attr("agentlens.tool_calls", run.toolCallCount()),
                attr("agentlens.event_count", run.events().size()),
                attr("agentlens.final_answer", abbreviate(run.finalAnswer(), ANSWER_ATTR_LIMIT))));
        span.put("status", run.status() == RunStatus.SUCCESS
                ? pairs("code", STATUS_OK)
                : pairs("code", STATUS_ERROR, "message",
                        run.error() == null ? run.status().name() : run.error()));
        return span;
    }

    private static Map<String, Object> eventSpan(AgentEvent event, int index, String runId,
                                                 String traceId, String parentSpanId) {
        // Tool and model invocations are outbound calls: CLIENT reads best in traces.
        int kind = event.type() == EventType.TOOL_CALL || event.type() == EventType.MODEL_CALL
                ? KIND_CLIENT : KIND_INTERNAL;
        long startNanos = toNanos(event.timestamp());
        Map<String, Object> span = baseSpan(traceId, spanId(runId, index), parentSpanId,
                event.type().name().toLowerCase(), kind,
                startNanos, startNanos + event.durationMs() * 1_000_000L);

        List<Map<String, Object>> attributes = new ArrayList<>();
        attributes.add(attr("agentlens.event_index", index));
        if (event.name() != null && !event.name().isBlank())
            attributes.add(attr("agentlens.event.name", event.name()));
        event.attributes().forEach((key, value) -> attributes.add(attr("agentlens." + key, value)));
        span.put("attributes", attributes);

        if (event.type() == EventType.ERROR) {
            Object message = event.attributes().get("error");
            span.put("status", pairs("code", STATUS_ERROR, "message",
                    abbreviate(message == null ? event.name() : String.valueOf(message), ANSWER_ATTR_LIMIT)));
        }
        return span;
    }

    private static Map<String, Object> baseSpan(String traceId, String spanId, String parentSpanId,
                                                String name, int kind, long startNanos, long endNanos) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", traceId);
        span.put("spanId", spanId);
        if (parentSpanId != null) span.put("parentSpanId", parentSpanId);
        span.put("name", name);
        span.put("kind", kind);
        span.put("startTimeUnixNano", String.valueOf(startNanos));
        span.put("endTimeUnixNano", String.valueOf(Math.max(startNanos, endNanos)));
        return span;
    }

    /** OTLP attribute with the proto3 JSON oneof value mapping. */
    static Map<String, Object> attr(String key, Object value) {
        Map<String, Object> attr = new LinkedHashMap<>();
        attr.put("key", key);
        Map<String, Object> v = new LinkedHashMap<>();
        if (value instanceof Boolean b) v.put("boolValue", b);
        else if (value instanceof Double || value instanceof Float)
            v.put("doubleValue", ((Number) value).doubleValue());
        else if (value instanceof Number) v.put("intValue", String.valueOf(value)); // int64 -> string
        else v.put("stringValue", String.valueOf(value));
        attr.put("value", v);
        return attr;
    }

    /** 16-byte trace id (32 hex chars), deterministically derived from the run id. */
    static String traceId(String runId) {
        return hashHex(runId, "", 16);
    }

    /** 8-byte span id (16 hex chars); index -1 is the root span. */
    static String spanId(String runId, int index) {
        return hashHex(runId, ":" + index, 8);
    }

    private static String hashHex(String runId, String suffix, int bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((runId + suffix).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes * 2);
            for (int i = 0; i < bytes; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static long toNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "...";
    }

    private static Map<String, Object> pairs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return map;
    }
}
