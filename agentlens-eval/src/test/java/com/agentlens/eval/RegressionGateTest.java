package com.agentlens.eval;

import com.agentlens.core.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegressionGateTest {

    private EvaluationResult r(String evaluator, boolean passed) {
        return new EvaluationResult(evaluator, passed, passed ? 1.0 : 0.0, "reason", Map.of());
    }

    @Test
    void level1PassCountDropFails() {
        var gate = new RegressionGate();
        var res = gate.compare(
                List.of(r("task-completion", true), r("run-limits", true)),
                List.of(r("task-completion", true), r("run-limits", false)));
        assertFalse(res.passed());
        assertTrue(res.reason().contains("dropped"));
    }

    @Test
    void level1SafetyViolationHardFailsEvenWhenCountEqual() {
        var gate = new RegressionGate(List.of("tool-policy"), 0.0);
        var res = gate.compare(
                List.of(r("task-completion", false)),
                List.of(r("tool-policy", false)));
        assertFalse(res.passed());
        assertTrue(res.reason().contains("tool-policy"));
    }

    @Test
    void level1CleanCandidatePasses() {
        var gate = new RegressionGate();
        var res = gate.compare(
                List.of(r("task-completion", true)),
                List.of(r("task-completion", true), r("run-limits", true)));
        assertTrue(res.passed());
    }

    @Test
    void passPowKDropBeyondThresholdFails() {
        var gate = new RegressionGate(List.of("tool-policy"), 0.05);
        var base = PassK.summarize(Map.of(
                "s1", List.of(List.of(r("t", true)), List.of(r("t", true))),
                "s2", List.of(List.of(r("t", true)), List.of(r("t", true)))));
        var cand = PassK.summarize(Map.of(
                "s1", List.of(List.of(r("t", true)), List.of(r("t", true))),
                "s2", List.of(List.of(r("t", true)), List.of(r("t", false)))));
        var res = gate.compare(base, cand);
        assertFalse(res.passed());
        assertTrue(res.reason().contains("pass^k dropped"));
    }

    @Test
    void passPowKWithinThresholdPasses() {
        var gate = new RegressionGate(List.of("tool-policy"), 0.5);
        var base = PassK.summarize(Map.of("s1", List.of(List.of(r("t", true)))));
        var cand = PassK.summarize(Map.of("s1", List.of(List.of(r("t", false)))));
        var res = gate.compare(base, cand);
        // pass^k 1.0 -> 0.0 exceeds 0.5 threshold
        assertFalse(res.passed());
    }

    @Test
    void requireStableRejectsFlakyAndDead() {
        var summary = PassK.summarize(Map.of(
                "ok", List.of(List.of(r("t", true)), List.of(r("t", true))),
                "flaky", List.of(List.of(r("t", true)), List.of(r("t", false)))));
        var res = new RegressionGate().requireStable(summary);
        assertFalse(res.passed());
        assertTrue(res.reason().contains("flaky"));
    }

    @Test
    void requireStablePassesAllStable() {
        var summary = PassK.summarize(Map.of(
                "ok", List.of(List.of(r("t", true)), List.of(r("t", true)))));
        var res = new RegressionGate().requireStable(summary);
        assertTrue(res.passed());
    }
}
