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
    void flagsConsecutiveRepeatCallsButNotParallelBursts() {
        var evaluator = new LiveEvaluator(Set.of(), 100, 3);
        // Interleaved different tools: not a loop.
        evaluator.onEvent(0, toolCall("read"));
        evaluator.onEvent(1, toolCall("grep"));
        evaluator.onEvent(2, toolCall("read"));
        assertTrue(evaluator.verdicts().isEmpty());
        // Three identical consecutive calls: loop signal.
        evaluator.onEvent(3, toolCall("pwsh"));
        evaluator.onEvent(4, toolCall("pwsh"));
        evaluator.onEvent(5, toolCall("pwsh"));
        assertTrue(evaluator.verdicts().stream().anyMatch(v -> v.rule().equals("repeated-calls")));
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
}
