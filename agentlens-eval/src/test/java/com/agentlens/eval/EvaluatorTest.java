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
    void toolPolicyWithoutAllowlistDoesNotFlagUnknownTools() throws Exception {
        // demo-style task: no allowedTools declared anywhere -> the unknown-tool
        // check is not applicable, the single probe tool must not fail the run
        AgentTask task = new AgentTask("t-allowless", "inspect service", Map.of(), 5);
        var run = new MockAgentAdapter().run(task);
        var result = new ToolPolicyEvaluator(Set.of(), Set.of()).evaluate(task, run);
        assertTrue(result.passed(), result.reason() + " | " + result.explanation());
        assertTrue(result.evidence().get("unknown") instanceof java.util.Set<?> u && u.isEmpty());
    }

    @Test
    void toolPolicyForbiddenStillEnforcedWithoutAllowlist() throws Exception {
        // an empty allowlist removes the unknown check but never the forbidden one
        AgentTask task = new AgentTask("t-forbidden", "inspect service", Map.of(), 5);
        var run = new MockAgentAdapter().run(task);
        var result = new ToolPolicyEvaluator(Set.of(), Set.of("query_prometheus"))
                .evaluate(task, run);
        assertTrue(!result.passed(), "forbidden tool must fail even with no allowlist");
        assertTrue(result.evidence().get("forbidden") instanceof java.util.Set<?> f
                && f.contains("query_prometheus"));
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
