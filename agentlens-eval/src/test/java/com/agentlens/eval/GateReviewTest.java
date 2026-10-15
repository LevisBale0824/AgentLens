package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1-B: the gate review prompt assembles verdict + metrics + evidence deterministically. */
class GateReviewTest {

    @Test
    void promptCarriesVerdictMetricsFlipsAndEvidence() {
        PassK.Summary base = PassK.summarizeRaw(Map.of("case-a", List.of(true, true),
                "case-b", List.of(true, true)));
        PassK.Summary cand = PassK.summarizeRaw(Map.of("case-a", List.of(true, true),
                "case-b", List.of(false, true)));
        RegressionGate.GateResult gate = new RegressionGate.GateResult(false, 3, 1,
                "pass^k regressed: 1.000 -> 0.500");
        String prompt = GateReview.buildPrompt("mock-correct", "mock-flaky", base, cand, gate,
                Map.of("case-b", "task-completion: wrong conclusion"));
        assertTrue(prompt.contains("Gate verdict: FAILED"), prompt);
        assertTrue(prompt.contains("pass^k regressed"), prompt);
        assertTrue(prompt.contains("baseline: mock-correct"), prompt);
        assertTrue(prompt.contains("candidate: mock-flaky"), prompt);
        assertTrue(prompt.contains("case-b: k=2 passed=1"), prompt);
        assertTrue(prompt.contains("task-completion: wrong conclusion"), prompt);
        assertTrue(prompt.contains("layer=FLAKY"), prompt);
    }

    @Test
    void fallbackReviewNamesFlakyAndEvidenceWithoutLlm() {
        PassK.Summary cand = PassK.summarizeRaw(Map.of("case-a", List.of(true, false)));
        RegressionGate.GateResult gate = new RegressionGate.GateResult(true, -1, -1, "no regression");
        String review = GateReview.fallbackReview("b", "c", null, cand, gate,
                Map.of("case-a", "run-limits: exceeded"));
        assertTrue(review.contains("Gate PASSED"), review);
        assertTrue(review.contains("Flaky cases"), review);
        assertTrue(review.contains("case-a: run-limits: exceeded"), review);
        assertTrue(review.contains("no judge configured"), review);
        assertFalse(review.contains("null"), review);
    }

    @Test
    void firstFailureReasonsFlattensRepeatsAndPicksFirstFailure() {
        var results = Map.of("case-a", List.of(
                List.of(result(true), result(true)),
                List.of(result(true), result(false, "tool-policy", "forbidden call"))));
        var reasons = GateReview.firstFailureReasons(results);
        assertTrue(reasons.get("case-a").contains("tool-policy"), reasons.toString());
        assertTrue(GateReview.firstFailureReasons(null).isEmpty());
    }

    private com.agentlens.core.EvaluationResult result(boolean passed) {
        return result(passed, "task-completion", passed ? "ok" : "no");
    }

    private com.agentlens.core.EvaluationResult result(boolean passed, String evaluator, String reason) {
        return new com.agentlens.core.EvaluationResult(evaluator, passed, passed ? 1.0 : 0.0,
                reason, Map.of());
    }
}
