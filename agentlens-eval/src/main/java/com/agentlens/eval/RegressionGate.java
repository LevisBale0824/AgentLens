package com.agentlens.eval;

import com.agentlens.core.EvaluationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Baseline/candidate regression gate (ROADMAP 7.1). Supports two levels:
 * level 1 (k=1) requires candidate pass count not to drop and hard-fails
 * on any safety evaluator failure; level 3 compares pass^k summaries when
 * repeated runs are available, with a configurable pass^k drop threshold.
 */
public final class RegressionGate {

    /** Evaluators whose failure is zero-tolerance (hard fail regardless of score). */
    private final List<String> hardFailEvaluators;
    /** Maximum allowed relative drop of pass^k, e.g. 0.05 = 5%. */
    private final double maxPassPowKDrop;

    public RegressionGate() {
        this(List.of("tool-policy", "safety"), 0.0);
    }

    public RegressionGate(List<String> hardFailEvaluators, double maxPassPowKDrop) {
        this.hardFailEvaluators = List.copyOf(hardFailEvaluators);
        if (maxPassPowKDrop < 0 || maxPassPowKDrop > 1)
            throw new IllegalArgumentException("maxPassPowKDrop must be within [0,1]");
        this.maxPassPowKDrop = maxPassPowKDrop;
    }

    /** Level 1: single-run comparison, k=1 semantics. */
    public GateResult compare(List<EvaluationResult> baseline, List<EvaluationResult> candidate) {
        long basePassed = baseline.stream().filter(EvaluationResult::passed).count();
        long candidatePassed = candidate.stream().filter(EvaluationResult::passed).count();
        List<String> violations = hardFailViolations(candidate);
        boolean pass = candidatePassed >= basePassed && violations.isEmpty();
        String reason = pass ? "regression gate passed"
                : violations.isEmpty()
                ? "candidate pass count dropped: " + candidatePassed + " < " + basePassed
                : "zero-tolerance evaluator failed: " + String.join(", ", violations);
        return new GateResult(pass, basePassed, candidatePassed, reason);
    }

    /**
     * Level 3: pass^k comparison over repeated runs of the same scenario set.
     * {@code maxPassPowKDrop} is the allowed regression applied to BOTH the
     * pass^k summary and the avg pass rate; 0.0 keeps the previous strict
     * behavior. The avg-rate tolerance matters for statistical honesty: with
     * a positive per-run variance the two sides' avg rates essentially never
     * tie, so a strict comparison false-alarms on identical quality about half
     * the time (quantified by {@link GatePower}).
     */
    public GateResult compare(PassK.Summary baseline, PassK.Summary candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate.passPowK() < baseline.passPowK() - maxPassPowKDrop - 1e-9) {
            reasons.add(String.format("pass^k dropped %.4f -> %.4f (allowed drop %.4f)",
                    baseline.passPowK(), candidate.passPowK(), maxPassPowKDrop));
        }
        if (candidate.avgPassRate() < baseline.avgPassRate() - maxPassPowKDrop - 1e-9) {
            reasons.add(String.format("avg pass rate dropped %.4f -> %.4f (allowed drop %.4f)",
                    baseline.avgPassRate(), candidate.avgPassRate(), maxPassPowKDrop));
        }
        boolean pass = reasons.isEmpty();
        return new GateResult(pass, -1, -1,
                pass ? "regression gate passed" : "regression gate failed: " + String.join("; ", reasons));
    }

    /** Any flaky or stable-fail scenario makes a release candidate unstable. */
    public GateResult requireStable(PassK.Summary candidate) {
        Map<PassK.FailureLayer, List<String>> layered = candidate.layered();
        List<String> unstable = new ArrayList<>();
        unstable.addAll(layered.getOrDefault(PassK.FailureLayer.FLAKY, List.of()));
        unstable.addAll(layered.getOrDefault(PassK.FailureLayer.STABLE_FAIL, List.of()));
        boolean pass = unstable.isEmpty();
        return new GateResult(pass, -1, -1, pass ? "all scenarios stable-pass"
                : "unstable scenarios: " + String.join(", ", unstable));
    }

    private List<String> hardFailViolations(List<EvaluationResult> results) {
        return results.stream()
                .filter(r -> !r.passed() && hardFailEvaluators.contains(r.evaluator()))
                .map(r -> r.evaluator() + " (" + r.reason() + ")")
                .toList();
    }

    public record GateResult(boolean passed, long baselinePassed, long candidatePassed, String reason) {}
}
