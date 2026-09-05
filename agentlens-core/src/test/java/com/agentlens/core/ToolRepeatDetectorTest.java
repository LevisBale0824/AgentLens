package com.agentlens.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ROADMAP 7.2: callId-aware distinction of retries, no-progress repeats and parallel calls. */
class ToolRepeatDetectorTest {

    private static AgentEvent call(String tool, String callId, String args) {
        Map<String, Object> attrs = args == null ? Map.of()
                : (callId == null ? Map.of("arguments", args) : Map.of("callId", callId, "arguments", args));
        return new AgentEvent(EventType.TOOL_CALL, tool, attrs, Instant.now(), 0);
    }

    private static AgentEvent result(String tool, String callId, String status) {
        Map<String, Object> attrs = callId == null ? Map.of("status", status)
                : Map.of("callId", callId, "status", status);
        return new AgentEvent(EventType.TOOL_RESULT, tool, attrs, Instant.now(), 0);
    }

    @Test
    void reCallAfterErrorIsTrueRetry() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("read", "c1", "a.txt"));
        d.onEvent(1, result("read", "c1", "error"));
        d.onEvent(2, call("read", "c2", "a.txt"));
        assertEquals(2, d.attempts().size());
        assertEquals(ToolRepeatDetector.Kind.FIRST, d.attempts().get(0).kind());
        var attempt = d.attempts().get(1);
        assertEquals(ToolRepeatDetector.Kind.RETRY, attempt.kind());
        assertEquals("error", attempt.previousOutcome());
        assertEquals(1, d.repeatedCallCount());
        assertEquals(1, d.retryCount());
    }

    @Test
    void sameNameDifferentArgumentsIsDistinctCallNotRepeat() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("read", "c1", "fileA"));
        d.onEvent(1, result("read", "c1", "ok"));
        d.onEvent(2, call("read", "c2", "fileB"));
        d.onEvent(3, result("read", "c2", "ok"));
        assertEquals(ToolRepeatDetector.Kind.FIRST, d.attempts().get(1).kind());
        assertEquals(0, d.repeatedCallCount());
    }

    @Test
    void unansweredDuplicateIsParallelNotLoop() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("pwsh", "c1", "build"));
        d.onEvent(1, call("pwsh", "c2", "build"));
        d.onEvent(2, call("pwsh", "c3", "build"));
        assertEquals(ToolRepeatDetector.Kind.FIRST, d.attempts().get(0).kind());
        assertEquals(ToolRepeatDetector.Kind.PARALLEL, d.attempts().get(1).kind());
        assertEquals(ToolRepeatDetector.Kind.PARALLEL, d.attempts().get(2).kind());
        assertEquals(0, d.repeatedCallCount());
        assertEquals(2, d.parallelCount());
    }

    @Test
    void interleavedParallelCallsAttributedByCallId() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("read", "c1", "a.txt"));
        d.onEvent(1, call("grep", "c2", "TODO"));
        d.onEvent(2, call("read", "c3", "a.txt"));
        d.onEvent(3, result("read", "c1", "ok"));
        d.onEvent(4, result("grep", "c2", "ok"));
        d.onEvent(5, result("read", "c3", "ok"));
        assertEquals(ToolRepeatDetector.Kind.PARALLEL, d.attempts().get(2).kind());
        assertEquals(0, d.repeatedCallCount());
    }

    @Test
    void sequentialIdenticalCallsWithOkResultsAreNoProgress() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("query_prometheus", null, "up"));
        d.onEvent(1, result("query_prometheus", null, "ok"));
        d.onEvent(2, call("query_prometheus", null, "up"));
        d.onEvent(3, result("query_prometheus", null, "ok"));
        assertEquals(ToolRepeatDetector.Kind.NO_PROGRESS, d.attempts().get(1).kind());
        assertEquals("ok", d.attempts().get(1).previousOutcome());
        assertEquals(1, d.repeatedCallCount());
        assertEquals(0, d.retryCount());
    }

    @Test
    void volatileBookkeepingAttributesDoNotChangeSignature() {
        // mock loop shape: identical call, only the attempt counter differs
        var d = new ToolRepeatDetector();
        for (int i = 0; i < 3; i++) {
            d.onEvent(i * 2, new AgentEvent(EventType.TOOL_CALL, "query_prometheus",
                    Map.of("attempt", i), Instant.now(), 0));
            d.onEvent(i * 2 + 1, new AgentEvent(EventType.TOOL_RESULT, "query_prometheus",
                    Map.of("status", "ok"), Instant.now(), 0));
        }
        assertEquals(ToolRepeatDetector.Kind.NO_PROGRESS, d.attempts().get(1).kind());
        assertEquals(ToolRepeatDetector.Kind.NO_PROGRESS, d.attempts().get(2).kind());
        assertEquals(2, d.repeatedCallCount());
    }

    @Test
    void errorThenDifferentArgumentsIsNotRetry() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("read", "c1", "a.txt"));
        d.onEvent(1, result("read", "c1", "error"));
        d.onEvent(2, call("read", "c2", "b.txt"));
        assertEquals(ToolRepeatDetector.Kind.FIRST, d.attempts().get(1).kind());
        assertEquals(0, d.repeatedCallCount());
    }

    @Test
    void resultAttributionFallsBackToToolNameWithoutCallId() {
        var d = new ToolRepeatDetector();
        d.onEvent(0, call("read", null, "a.txt"));
        d.onEvent(1, result("read", null, "error"));
        d.onEvent(2, call("read", null, "a.txt"));
        assertEquals(ToolRepeatDetector.Kind.RETRY, d.attempts().get(1).kind());
    }
}
