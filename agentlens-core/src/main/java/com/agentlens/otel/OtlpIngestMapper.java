package com.agentlens.otel;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-B: inverse of {@link OtelSpanMapper} - converts an OTLP/JSON trace
 * payload into unified {@link AgentRun}s so any OpenTelemetry-instrumented
 * agent can feed AgentLens with zero adapter code (agents point their OTLP
 * exporter at {@code otel-receive}; one traceId = one run).
 *
 * <p>Attribute conventions understood (unrecognized attributes are preserved
 * verbatim, so no telemetry is lost):
 * <ul>
 *   <li>{@code agentlens.*} - native round trip: run/task ids, final answer,
 *       token totals, event names and attributes (prefix stripped back).</li>
 *   <li>OTel GenAI semconv - {@code gen_ai.usage.prompt_tokens}/
 *       {@code gen_ai.usage.completion_tokens} sum into run token totals;
 *       {@code gen_ai.request.model} / {@code gen_ai.system} name model calls.</li>
 *   <li>OpenInference - {@code llm.token_count.prompt}/
 *       {@code llm.token_count.completion} also sum into token totals;
 *       {@code retrieval}/{@code generation} span names map to RETRIEVAL /
 *       MODEL_CALL events.</li>
 * </ul>
 *
 * <p>Run construction: the root span (no parent, or parent outside the trace)
 * becomes the run (status from the span status, duration from the span
 * window); remaining spans become events in start order. Unknown span names
 * fall back to their kind: CLIENT/PRODUCER spans are outbound tool-ish calls,
 * other spans are generic reasoning steps (PLAN).
 */
public final class OtlpIngestMapper {

    private static final int KIND_CLIENT = 3;
    private static final int KIND_PRODUCER = 4;

    private OtlpIngestMapper() {}

    /** Converts an OTLP/JSON payload ("resourceSpans" tree) into runs, one per traceId. */
    public static List<AgentRun> fromOtlpJson(Map<String, Object> payload) {
        if (payload == null || !(payload.get("resourceSpans") instanceof List<?> resourceSpans))
            throw new IllegalArgumentException("payload is missing resourceSpans");
        // traceId -> [spans]; LinkedHashMap keeps first-seen trace and span order.
        Map<String, List<Span>> byTrace = new LinkedHashMap<>();
        Map<String, Map<String, Object>> resourceByTrace = new LinkedHashMap<>();
        for (Object rs : resourceSpans) {
            if (!(rs instanceof Map<?, ?> resourceSpansMap)) continue;
            Object resource = resourceSpansMap.get("resource");
            Map<String, Object> resourceAttrs = attributeMap(
                    resource instanceof Map<?, ?> r ? r.get("attributes") : null);
            for (Object scopeObj : listOf(resourceSpansMap.get("scopeSpans"))) {
                if (!(scopeObj instanceof Map<?, ?> scope)) continue;
                for (Object spanObj : listOf(scope.get("spans"))) {
                    if (!(spanObj instanceof Map<?, ?> spanMap)) continue;
                    Span span = Span.parse((Map<String, Object>) spanMap);
                    byTrace.computeIfAbsent(span.traceId(), k -> new ArrayList<>()).add(span);
                    resourceByTrace.putIfAbsent(span.traceId(), resourceAttrs);
                }
            }
        }
        List<AgentRun> runs = new ArrayList<>();
        for (var entry : byTrace.entrySet()) {
            AgentRun run = toRun(entry.getKey(), entry.getValue(),
                    resourceByTrace.getOrDefault(entry.getKey(), Map.of()));
            if (run != null) runs.add(run);
        }
        return runs;
    }

    /** Maps one trace (root + children) onto a run. */
    private static AgentRun toRun(String traceId, List<Span> spans, Map<String, Object> resourceAttrs) {
        Span root = spans.stream()
                .filter(s -> s.parentSpanId() == null || spans.stream()
                        .noneMatch(other -> other.spanId().equals(s.parentSpanId())))
                .findFirst()
                .orElse(spans.get(0));

        String runId = str(resourceAttrs.get("agentlens.run_id"), traceId);
        String taskId = str(resourceAttrs.get("agentlens.task_id"),
                "otel-" + traceId.substring(0, Math.min(16, traceId.length())));

        List<AgentEvent> events = new ArrayList<>();
        spans.stream()
                .filter(s -> s != root)
                .sorted(Comparator.comparingLong(Span::startNanos))
                .forEach(s -> events.add(toEvent(s)));

        boolean failed = isError(root);
        String finalAnswer = firstOf(root.attributes(),
                "agentlens.final_answer", "gen_ai.completion", "output.value");
        long tokens = tokenTotal(spans);

        return new AgentRun(runId, taskId, failed ? RunStatus.FAILED : RunStatus.SUCCESS,
                finalAnswer == null || finalAnswer.isBlank() ? null : finalAnswer,
                List.copyOf(events), Instant.ofEpochSecond(0, root.startNanos()),
                Math.max(0, (root.endNanos() - root.startNanos()) / 1_000_000L),
                (int) Math.min(Integer.MAX_VALUE, tokens), failed ? str(root.statusMessage(), root.name()) : null);
    }

    /** Span -> unified event (name conventions first, then span kind as fallback). */
    private static AgentEvent toEvent(Span span) {
        EventType type = eventType(span);
        return new AgentEvent(type, eventName(span, type),
                eventAttributes(span), Instant.ofEpochSecond(0, span.startNanos()),
                (int) Math.max(0, (span.endNanos() - span.startNanos()) / 1_000_000L));
    }

    private static boolean isError(Span span) {
        Integer code = span.statusCode();
        return code != null && code == 2;
    }

    private static EventType eventType(Span span) {
        String name = span.name() == null ? "" : span.name().toLowerCase();
        return switch (name) {
            case "tool_call", "tool" -> EventType.TOOL_CALL;
            case "tool_result" -> EventType.TOOL_RESULT;
            case "model_call", "generation", "llm", "chat", "completion" -> EventType.MODEL_CALL;
            case "final_answer" -> EventType.FINAL_ANSWER;
            case "error" -> EventType.ERROR;
            case "retriever", "retrieval", "embedding" -> EventType.RETRIEVAL;
            case "plan", "planning" -> EventType.PLAN;
            case "memory_read" -> EventType.MEMORY_READ;
            case "memory_write" -> EventType.MEMORY_WRITE;
            default -> isError(span) ? EventType.ERROR
                    : span.kind() != null && (span.kind() == KIND_CLIENT || span.kind() == KIND_PRODUCER)
                    ? EventType.TOOL_CALL : EventType.PLAN;
        };
    }

    private static String eventName(Span span, EventType type) {
        String named = firstOf(span.attributes(), "agentlens.event.name", "tool.name",
                "gen_ai.request.model", "gen_ai.system");
        return named != null && !named.isBlank() ? named : span.name();
    }

    /** agentlens.* attributes strip their prefix; everything else is kept verbatim. */
    private static Map<String, Object> eventAttributes(Span span) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        span.attributes().forEach((key, value) -> attrs.put(
                key.startsWith("agentlens.") ? key.substring("agentlens.".length()) : key, value));
        if (isError(span) && !attrs.containsKey("error") && span.statusMessage() != null)
            attrs.put("error", span.statusMessage());
        return attrs;
    }

    /** Run token totals: native attribute wins, else summed GenAI/OpenInference usage. */
    private static long tokenTotal(List<Span> spans) {
        for (Span span : spans) {
            Object nativeTotal = span.attributes().get("agentlens.total_tokens");
            if (nativeTotal instanceof Number n) return n.longValue();
        }
        long total = 0;
        for (Span span : spans) {
            Long explicit = number(span.attributes(),
                    "gen_ai.usage.total_tokens", "llm.token_count.total");
            if (explicit != null) {
                total += explicit;
                continue;
            }
            Long prompt = number(span.attributes(),
                    "gen_ai.usage.prompt_tokens", "llm.token_count.prompt");
            Long completion = number(span.attributes(),
                    "gen_ai.usage.completion_tokens", "llm.token_count.completion");
            if (prompt != null) total += prompt;
            if (completion != null) total += completion;
        }
        return total;
    }

    /** First attribute that is a Number (raw, not stringified). */
    private static Long number(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value instanceof Number n) return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributeMap(Object attrList) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(attrList instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> attr)) continue;
            Object key = attr.get("key");
            Object value = attr.get("value");
            if (key == null || !(value instanceof Map<?, ?> oneof)) continue;
            out.put(String.valueOf(key), oneofValue((Map<String, Object>) oneof));
        }
        return out;
    }

    private static Object oneofValue(Map<String, Object> oneof) {
        if (oneof.containsKey("stringValue")) return oneof.get("stringValue");
        if (oneof.containsKey("intValue")) return parseLong(oneof.get("intValue"));
        if (oneof.containsKey("doubleValue")) return parseDouble(oneof.get("doubleValue"));
        if (oneof.containsKey("boolValue")) return oneof.get("boolValue");
        // arrayValue / kvlistValue: keep the raw form - no data loss, no deep parse.
        return oneof;
    }

    private static Object parseLong(Object value) {
        try {
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return String.valueOf(value);
        }
    }

    private static Object parseDouble(Object value) {
        try {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return String.valueOf(value);
        }
    }

    private static String firstOf(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) return String.valueOf(value);
        }
        return null;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static List<Object> listOf(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    /** One parsed OTLP span. */
    record Span(String traceId, String spanId, String parentSpanId, String name, Integer kind,
                long startNanos, long endNanos, Integer statusCode, String statusMessage,
                Map<String, Object> attributes) {
        static Span parse(Map<String, Object> map) {
            String traceId = required(map, "traceId");
            String spanId = required(map, "spanId");
            Map<String, Object> attrs = attributeMap(map.get("attributes"));
            long start = parseNanos(map.get("startTimeUnixNano"));
            long end = parseNanos(map.get("endTimeUnixNano"));
            Integer kind = map.get("kind") instanceof Number n ? n.intValue() : null;
            Integer statusCode = null;
            String statusMessage = null;
            if (map.get("status") instanceof Map<?, ?> status) {
                statusCode = status.get("code") instanceof Number n ? n.intValue() : null;
                statusMessage = status.get("message") == null ? null : String.valueOf(status.get("message"));
            }
            return new Span(traceId, spanId,
                    map.get("parentSpanId") == null ? null : String.valueOf(map.get("parentSpanId")),
                    str(map.get("name"), ""), kind, start, Math.max(start, end),
                    statusCode, statusMessage, attrs);
        }

        private static String required(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value == null || String.valueOf(value).isBlank())
                throw new IllegalArgumentException("span is missing " + key);
            return String.valueOf(value);
        }

        private static long parseNanos(Object value) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }
}
