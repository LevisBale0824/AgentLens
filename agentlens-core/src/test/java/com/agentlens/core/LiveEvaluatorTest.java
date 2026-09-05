package com.agentlens.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LiveEvaluatorTest {

    @Test
    void flagsForbiddenToolImmediatelyAsHardViolation() {
        var fired = new CopyOnWriteArrayList<LiveEvaluator.Verdict>();
        var evaluator = new LiveEvaluator(Set.of("web_search"), 10, 0, fired::add);

        evaluator.onEvent(0, toolCall("read"));
        assertTrue(fired.isEmpty());
        evaluator.onEvent(1, toolCall("web_search"));

        assertEquals(1, fired.size());
        var verdict = fired.get(0);
        assertEquals("safety", verdict.layer());
        assertTrue(verdict.hardViolation());
        assertEquals(1, verdict.eventIndex());
        assertTrue(evaluator.hardViolationSeen());
    }

    @Test
    void flagsStepLimitWhenExceeded() {
        var evaluator = new LiveEvaluator(Set.of(), 2, 0);
        evaluator.onEvent(0, toolCall("read"));
        evaluator.onEvent(1, toolCall("read"));
        assertTrue(evaluator.verdicts().isEmpty());
        evaluator.onEvent(2, toolCall("read"));
        assertEquals(1, evaluator.verdicts().size());
        assertEquals("step-limit", evaluator.verdicts().get(0).rule());
        assertFalse(evaluator.verdicts().get(0).hardViolation());
    }

    @Test
    void flagsConsecutiveIdenticalRepeatsOnce() {
        var evaluator = new LiveEvaluator(Set.of(), 100, 3);
        // three identical completed calls: loop signal fires exactly at the threshold
        for (int i = 0; i < 3; i++) {
            evaluator.onEvent(i * 2, toolCall("pwsh"));
            evaluator.onEvent(i * 2 + 1, toolResult("pwsh", "ok"));
        }
        var repeats = evaluator.verdicts().stream()
                .filter(v -> v.rule().equals("repeated-calls")).toList();
        assertEquals(1, repeats.size());
        assertFalse(repeats.get(0).hardViolation());
        assertTrue(repeats.get(0).message().contains("no progress"));
    }

    @Test
    void retryAfterErrorNamesTheErrorInVerdict() {
        var evaluator = new LiveEvaluator(Set.of(), 100, 2);
        evaluator.onEvent(0, toolCall("read"));
        evaluator.onEvent(1, toolResult("read", "error"));
        evaluator.onEvent(2, toolCall("read"));
        var repeats = evaluator.verdicts().stream()
                .filter(v -> v.rule().equals("repeated-calls")).toList();
        assertEquals(1, repeats.size());
        assertTrue(repeats.get(0).message().contains("re-called after error"));
    }

    @Test
    void parallelBurstDoesNotFireLoopVerdict() {
        var evaluator = new LiveEvaluator(Set.of(), 100, 3);
        // three identical calls with no results in between: parallel burst, not a loop
        evaluator.onEvent(0, toolCall("pwsh"));
        evaluator.onEvent(1, toolCall("pwsh"));
        evaluator.onEvent(2, toolCall("pwsh"));
        assertTrue(evaluator.verdicts().stream().noneMatch(v -> v.rule().equals("repeated-calls")));
    }

    @Test
    void sameToolWithDifferentArgumentsIsNotALoop() {
        var evaluator = new LiveEvaluator(Set.of(), 100, 3);
        evaluator.onEvent(0, new AgentEvent(EventType.TOOL_CALL, "read",
                java.util.Map.of("arguments", "a.txt"), Instant.now(), 0));
        evaluator.onEvent(1, toolResult("read", "ok"));
        evaluator.onEvent(2, new AgentEvent(EventType.TOOL_CALL, "read",
                java.util.Map.of("arguments", "b.txt"), Instant.now(), 0));
        evaluator.onEvent(3, toolResult("read", "ok"));
        evaluator.onEvent(4, new AgentEvent(EventType.TOOL_CALL, "read",
                java.util.Map.of("arguments", "c.txt"), Instant.now(), 0));
        assertTrue(evaluator.verdicts().isEmpty());
    }

    @Test
    void interleavedDifferentToolsAreNotFlagged() {
        var evaluator = new LiveEvaluator(Set.of(), 100, 3);
        evaluator.onEvent(0, toolCall("read"));
        evaluator.onEvent(1, toolResult("read", "ok"));
        evaluator.onEvent(2, toolCall("grep"));
        evaluator.onEvent(3, toolResult("grep", "ok"));
        evaluator.onEvent(4, toolCall("read"));
        assertTrue(evaluator.verdicts().stream().noneMatch(v -> v.rule().equals("repeated-calls")));
    }

    @Test
    void ignoresNonToolEvents() {
        var evaluator = new LiveEvaluator(Set.of("web_search"), 1, 8);
        evaluator.onEvent(0, new AgentEvent(EventType.MODEL_CALL, "dsh.model", java.util.Map.of(), Instant.now(), 0));
        evaluator.onEvent(1, new AgentEvent(EventType.TOOL_RESULT, "read", java.util.Map.of(), Instant.now(), 0));
        assertTrue(evaluator.verdicts().isEmpty());
        assertFalse(evaluator.hardViolationSeen());
    }

    private static AgentEvent toolCall(String name) {
        return new AgentEvent(EventType.TOOL_CALL, name, java.util.Map.of(), Instant.now(), 0);
    }

    private static AgentEvent toolResult(String name, String status) {
        return new AgentEvent(EventType.TOOL_RESULT, name, java.util.Map.of("status", status),
                Instant.now(), 0);
    }
}
