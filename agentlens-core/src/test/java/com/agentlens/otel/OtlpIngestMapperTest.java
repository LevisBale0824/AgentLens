package com.agentlens.otel;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpIngestMapperTest {

    private static final Instant BASE = Instant.parse("2026-10-14T08:00:00Z");

    private AgentRun sampleRun() {
        return new AgentRun("run-42", "task-7", RunStatus.SUCCESS, "all good", List.of(
                new AgentEvent(EventType.TOOL_CALL, "query_prometheus",
                        Map.of("phase", "diagnose"), BASE, 5),
                new AgentEvent(EventType.TOOL_RESULT, "query_prometheus",
                        Map.of("status", "ok"), BASE.plusMillis(5), 3),
                new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null,
                        BASE.plusMillis(8), 1)),
                BASE, 9, 333, null);
    }

    @Test
    void nativeRoundTripPreservesRunIdentityAnswerTokensAndEvents() {
        List<AgentRun> runs = OtlpIngestMapper.fromOtlpJson(OtelSpanMapper.toOtlpJson(sampleRun()));
        assertEquals(1, runs.size());
        AgentRun back = runs.get(0);
        assertEquals("run-42", back.runId());
        assertEquals("task-7", back.taskId());
        assertEquals(RunStatus.SUCCESS, back.status());
        assertEquals("all good", back.finalAnswer());
        assertNull(back.error());
        assertEquals(333, back.totalTokens());
        assertEquals(9, back.durationMs());
        assertEquals(BASE, back.startedAt());

        assertEquals(3, back.events().size());
        AgentEvent toolCall = back.events().get(0);
        assertEquals(EventType.TOOL_CALL, toolCall.type());
        assertEquals("query_prometheus", toolCall.name());
        assertEquals("diagnose", toolCall.attributes().get("phase"));
        assertEquals(5, toolCall.durationMs());
        assertEquals(EventType.TOOL_RESULT, back.events().get(1).type());
        assertEquals("ok", back.events().get(1).attributes().get("status"));
        assertEquals(EventType.FINAL_ANSWER, back.events().get(2).type());
    }

    @Test
    void failedRootBecomesFailedRunWithError() {
        AgentRun failed = new AgentRun("run-err", "task-err", RunStatus.FAILED, null, List.of(),
                BASE, 4, 10, "agent gave up");
        List<AgentRun> runs = OtlpIngestMapper.fromOtlpJson(OtelSpanMapper.toOtlpJson(failed));
        AgentRun back = runs.get(0);
        assertEquals(RunStatus.FAILED, back.status());
        assertEquals("agent gave up", back.error());
        assertEquals(0, back.events().size());
    }

    private static Map<String, Object> attr(String key, String stringValue) {
        return Map.of("key", key, "value", Map.of("stringValue", stringValue));
    }

    private static Map<String, Object> attrInt(String key, long value) {
        return Map.of("key", key, "value", Map.of("intValue", String.valueOf(value)));
    }

    private static Map<String, Object> span(String traceId, String spanId, String parentSpanId,
                                            String name, int kind, long startMs, long endMs,
                                            Map<String, Object>... attrs) {
        var span = new java.util.LinkedHashMap<String, Object>();
        span.put("traceId", traceId);
        span.put("spanId", spanId);
        if (parentSpanId != null) span.put("parentSpanId", parentSpanId);
        span.put("name", name);
        span.put("kind", kind);
        span.put("startTimeUnixNano", String.valueOf(startMs * 1_000_000L));
        span.put("endTimeUnixNano", String.valueOf(endMs * 1_000_000L));
        span.put("attributes", List.of(attrs));
        return span;
    }

    @Test
    void foreignTraceWithGenAiConventionsMapsToUnifiedModel() {
        Map<String, Object> payload = Map.of("resourceSpans", List.of(Map.of(
                "resource", Map.of("attributes", List.of(attr("service.name", "my-agent"))),
                "scopeSpans", List.of(Map.of("spans", List.of(
                        span("traceA", "root1", null, "chat.session", 2, 0, 50),
                        // child listed BEFORE the root: root detection must not rely on order
                        span("traceA", "gen1", "root1", "generation", 3, 2, 20,
                                attrInt("gen_ai.usage.prompt_tokens", 100),
                                attrInt("gen_ai.usage.completion_tokens", 30),
                                attr("gen_ai.request.model", "gpt-4o")),
                        span("traceA", "tool1", "root1", "tool browser.click", 3, 22, 30),
                        span("traceA", "step1", "root1", "reasoner.step", 1, 32, 40)))))));

        List<AgentRun> runs = OtlpIngestMapper.fromOtlpJson(payload);
        assertEquals(1, runs.size());
        AgentRun run = runs.get(0);
        // No native ids: trace hash fallback.
        assertEquals("traceA", run.runId());
        assertEquals("otel-" + "traceA", run.taskId());
        assertEquals(RunStatus.SUCCESS, run.status());
        assertEquals(130, run.totalTokens());
        assertEquals(50, run.durationMs());

        assertEquals(3, run.events().size());
        AgentEvent generation = run.events().get(0);
        assertEquals(EventType.MODEL_CALL, generation.type());
        assertEquals("gpt-4o", generation.name());
        AgentEvent tool = run.events().get(1);
        assertEquals(EventType.TOOL_CALL, tool.type());
        assertEquals("tool browser.click", tool.name());
        AgentEvent step = run.events().get(2);
        assertEquals(EventType.PLAN, step.type());
    }

    @Test
    void errorStatusOnForeignSpanBecomesErrorEvent() {
        Map<String, Object> errorSpan = new java.util.LinkedHashMap<>(
                span("traceE", "err1", "rootE", "http POST /api", 3, 1, 5));
        errorSpan.put("status", Map.of("code", 2, "message", "connection refused"));
        Map<String, Object> payload = Map.of("resourceSpans", List.of(Map.of(
                "scopeSpans", List.of(Map.of("spans", List.of(
                        span("traceE", "rootE", null, "session", 2, 0, 10),
                        errorSpan))))));
        AgentRun run = OtlpIngestMapper.fromOtlpJson(payload).get(0);
        assertEquals(EventType.ERROR, run.events().get(0).type());
        assertEquals("connection refused", run.events().get(0).attributes().get("error"));
    }

    @Test
    void malformedPayloadsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OtlpIngestMapper.fromOtlpJson(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> OtlpIngestMapper.fromOtlpJson(null));
        assertTrue(OtlpIngestMapper.fromOtlpJson(
                Map.of("resourceSpans", List.of())).isEmpty());
    }
}
