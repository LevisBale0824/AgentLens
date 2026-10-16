package com.agentlens.eval;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1 断言代数: the evaluator wires the DSL into the registry, context-driven. */
class SuccessAssertionEvaluatorTest {

    private static AgentTask task(Map<String, Object> context) {
        return new AgentTask("case-a", "diagnose", context, 10);
    }

    @Test
    void evaluatesDeclaredAssertionsAgainstFinalAnswer() {
        AgentTask task = task(Map.of(SuccessAssertionEvaluator.CONTEXT_KEY,
                List.of("root cause", "not-crashloop", "w2:identified")));
        AgentRun run = new MockAgentAdapter("correct").run(task);
        var result = new SuccessAssertionEvaluator().evaluate(task, run);
        assertEquals("success-assertions", result.evaluator());
        assertTrue(result.passed(), result.reason());
        assertEquals(1.0, result.score(), 1e-9);
        assertEquals(3, result.evidence().get("declared"));
    }

    @Test
    void failingAssertionCarriesMissingEvidenceAndPartialScore() {
        AgentTask task = task(Map.of(SuccessAssertionEvaluator.CONTEXT_KEY,
                List.of("w3:quota", "not-oomkilled")));
        AgentRun run = new MockAgentAdapter("correct").run(task);
        var result = new SuccessAssertionEvaluator().evaluate(task, run);
        // mock "correct" answers never mention quota -> weight-3 assertion fails;
        // not-oomkilled passes (weight 1) -> partial credit 1/4
        assertFalse(result.passed());
        assertEquals(0.25, result.score(), 1e-9);
        assertTrue(result.reason().startsWith("missing:"), result.reason());
    }

    @Test
    void scenariosWithoutAssertionsShortCircuitToPass() {
        AgentTask task = task(Map.of());
        AgentRun run = new MockAgentAdapter("correct").run(task);
        var result = new SuccessAssertionEvaluator().evaluate(task, run);
        assertTrue(result.passed());
        assertEquals(1.0, result.score(), 1e-9);
        assertEquals(0, result.evidence().get("declared"));
    }

    @Test
    void invalidSpecFailsLoudlyInsteadOfPassingVacuously() {
        AgentTask task = task(Map.of(SuccessAssertionEvaluator.CONTEXT_KEY, List.of("3-of:a|b")));
        AgentRun run = new MockAgentAdapter("correct").run(task);
        var result = new SuccessAssertionEvaluator().evaluate(task, run);
        assertFalse(result.passed());
        assertTrue(result.reason().contains("k-of"), result.reason());
    }

    @Test
    void scenarioToTaskCarriesAssertionsIntoContext() {
        Scenario scenario = new Scenario("s1", "domain", "alert", List.of(), "cause",
                List.of(), List.of(), List.of("root cause stated", "not-blame"), 8,
                "correct", "mock", null, "instruction", null);
        var context = scenario.toTask().context();
        assertEquals(List.of("root cause stated", "not-blame"),
                context.get(SuccessAssertionEvaluator.CONTEXT_KEY));
        // assertion-less scenarios keep their context free of the key
        Scenario bare = new Scenario("s2", "domain", "alert", List.of(), "cause",
                List.of(), List.of(), List.of(), 8, "correct", "mock", null, null, null);
        assertFalse(bare.toTask().context().containsKey(SuccessAssertionEvaluator.CONTEXT_KEY));
    }
}
