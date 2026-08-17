package com.agentlens.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreModelTest {
    @Test
    void taskDefaultsToTenSteps() {
        assertEquals(10, new AgentTask("t1", "inspect", Map.of(), 0).maxSteps());
    }

    @Test
    void successfulRunRequiresAnswer() {
        AgentRun run = new AgentRun("r1", "t1", RunStatus.SUCCESS, "ok", List.of(), null, 1, 0, null);
        assertTrue(TraceValidator.validate(run).isEmpty());
    }

    @Test
    void diagnosisRejectsInvalidConfidence() {
        assertThrows(IllegalArgumentException.class, () ->
                new Diagnosis("tool_selection", "bad", 1.2, 0.5, List.of(), "fix", Map.of()));
    }

    @Test
    void runExposesOperationalQualitySignals() {
        var events = List.of(
                new AgentEvent(EventType.TOOL_CALL, "query", Map.of(), null, 1),
                new AgentEvent(EventType.TOOL_CALL, "query", Map.of(), null, 1),
                new AgentEvent(EventType.ERROR, "timeout", Map.of(), null, 1));
        var run = new AgentRun("r1", "t1", RunStatus.FAILED, null, events, null, 3, 12, "timeout");
        assertEquals(2, run.toolCallCount());
        assertEquals(1, run.errorCount());
        assertEquals(1, run.repeatedToolCallCount());
    }

    @Test
    void experienceCandidateStartsAsReviewableCandidate() {
        var candidate = new ExperienceCandidate("exp-1", "execution", "timeout", "use bounded retry", List.of(), .8, 1, 0, null, Map.of());
        assertEquals("CANDIDATE", candidate.status());
    }
}
