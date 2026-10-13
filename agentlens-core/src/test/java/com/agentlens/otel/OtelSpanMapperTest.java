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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the OTLP/JSON mapping (ROADMAP P2-3): structure, deterministic
 * ids, parent linkage, nano timestamps, attribute value variants, statuses.
 */
class OtelSpanMapperTest {

    private static AgentRun sampleRun(RunStatus status, String error) {
        return new AgentRun("run-abc", "task-1", status, "done: fixed",
                List.of(
                        new AgentEvent(EventType.MODEL_CALL, "m1", Map.of("model", "glm"),
                                Instant.parse("2026-01-01T00:00:00Z"), 120),
                        new AgentEvent(EventType.TOOL_CALL, "read",
                                Map.of("file_path", "a.txt", "nested", List.of("x")),
                                Instant.parse("2026-01-01T00:00:00.100Z"), 40),
                        new AgentEvent(EventType.ERROR, "boom", Map.of("error", "exploded"),
                                Instant.parse("2026-01-01T00:00:00.200Z"), 5)),
                Instant.parse("2026-01-01T00:00:00Z"), 250, 7, error);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> spans(Map<String, Object> payload) {
        List<Object> resourceSpans = (List<Object>) payload.get("resourceSpans");
        Map<String, Object> rs = (Map<String, Object>) resourceSpans.get(0);
        List<Object> scopeSpans = (List<Object>) rs.get("scopeSpans");
        Map<String, Object> ss = (Map<String, Object>) scopeSpans.get(0);
        return (List<Map<String, Object>>) (Object) ss.get("spans");
    }

    private static Map<String, Object> span(Map<String, Object> payload, int index) {
        return spans(payload).get(index);
    }

    @Test
    void oneRunBecomesOneTraceWithRootPlusEventSpans() {
        Map<String, Object> payload = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        assertEquals(4, spans(payload).size()); // 1 root + 3 events
        assertEquals("agentlens.run", span(payload, 0).get("name"));
    }

    @Test
    void idsAreDeterministicHexWithCorrectLength() {
        Map<String, Object> first = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        Map<String, Object> second = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        assertEquals(first, second);
        String traceId = (String) span(first, 0).get("traceId");
        assertEquals(32, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{32}"));
        assertTrue(((String) span(first, 1).get("spanId")).matches("[0-9a-f]{16}"));
    }

    @Test
    void childrenShareTraceIdAndPointAtRoot() {
        Map<String, Object> payload = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        Map<String, Object> root = span(payload, 0);
        assertFalse(root.containsKey("parentSpanId"));
        assertEquals(2, root.get("kind")); // SERVER
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> child = span(payload, i);
            assertEquals(root.get("traceId"), child.get("traceId"));
            assertEquals(root.get("spanId"), child.get("parentSpanId"));
        }
    }

    @Test
    void toolAndModelCallsAreClientKindOthersInternal() {
        Map<String, Object> payload = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        assertEquals(3, span(payload, 1).get("kind")); // MODEL_CALL
        assertEquals(3, span(payload, 2).get("kind")); // TOOL_CALL
        assertEquals(1, span(payload, 3).get("kind")); // ERROR
    }

    @Test
    void timestampsAreStringNanosAndDurationsSurvive() {
        Map<String, Object> payload = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        Map<String, Object> modelCall = span(payload, 1);
        assertEquals("1767225600000000000", modelCall.get("startTimeUnixNano"));
        long start = Long.parseLong((String) modelCall.get("startTimeUnixNano"));
        long end = Long.parseLong((String) modelCall.get("endTimeUnixNano"));
        assertEquals(120_000_000L, end - start);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrValue(Map<String, Object> span, String key) {
        List<Object> attributes = (List<Object>) span.get("attributes");
        for (Object o : attributes) {
            Map<String, Object> attr = (Map<String, Object>) o;
            if (key.equals(attr.get("key"))) return (Map<String, Object>) attr.get("value");
        }
        return null;
    }

    @Test
    void attributeVariantsMapToOtlpOneOfKeys() {
        Map<String, Object> payload = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        Map<String, Object> root = span(payload, 0);
        // int64 numbers are string-encoded per proto3 JSON
        assertEquals("7", attrValue(root, "agentlens.total_tokens").get("intValue"));
        assertEquals("done: fixed", attrValue(root, "agentlens.final_answer").get("stringValue"));
        // event attributes carry the agentlens. prefix
        Map<String, Object> toolCall = span(payload, 2);
        assertEquals("a.txt", attrValue(toolCall, "agentlens.file_path").get("stringValue"));
        // non-scalar values are stringified, not dropped
        assertEquals("[x]", attrValue(toolCall, "agentlens.nested").get("stringValue"));
    }

    @Test
    void successRunIsOkFailedRunIsErrorWithMessage() {
        Map<String, Object> ok = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        assertEquals(1, ((Map<String, Object>) span(ok, 0).get("status")).get("code"));

        Map<String, Object> failed = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.FAILED, "boom"));
        Map<String, Object> status = (Map<String, Object>) span(failed, 0).get("status");
        assertEquals(2, status.get("code"));
        assertEquals("boom", status.get("message"));
    }

    @Test
    void errorEventSpanCarriesErrorStatus() {
        Map<String, Object> payload = OtelSpanMapper.toOtlpJson(sampleRun(RunStatus.SUCCESS, null));
        Map<String, Object> status = (Map<String, Object>) span(payload, 3).get("status");
        assertEquals(2, status.get("code"));
        assertEquals("exploded", status.get("message"));
    }
}
