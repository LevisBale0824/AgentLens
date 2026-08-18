package com.agentlens.eval;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluatorTest {
    @Test
    void mockAgentPassesBasicEvaluators() throws Exception {
        AgentTask task = new AgentTask("t1", "inspect service", Map.of(
                "allowedTools", java.util.List.of("query_prometheus")), 5);
        var run = new MockAgentAdapter().run(task);
        var results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of("dangerous")))
                .evaluate(task, run);
        assertTrue(results.stream().allMatch(r -> r.passed()));
    }

    @Test
    void diagnosticsCanBecomeReviewableExperience() {
        var diagnosis = new com.agentlens.core.Diagnosis("tool_selection", "wrong tool", .8, .9,
                java.util.List.of("tool-policy"), "select the allowed tool", Map.of());
        var candidates = new DiagnosisEngine().mineExperience(java.util.List.of(diagnosis));
        assertEquals(1, candidates.size());
        assertEquals("CANDIDATE", candidates.get(0).status());
        assertEquals("select the allowed tool", candidates.get(0).guidance());
    }
}
