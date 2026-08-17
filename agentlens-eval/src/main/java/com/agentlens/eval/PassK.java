package com.agentlens.eval;

import com.agentlens.core.EvaluationResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates repeated runs of the same scenario into stability metrics
 * (ROADMAP 7.1): pass^k (all k passed), pass@k (at least one passed)
 * and the plain per-run pass rate, plus a layered failure view that
 * separates stable-pass / flaky / stable-fail scenarios.
 */
public final class PassK {

    /**
     * @param runs one list of results per repetition of the same scenario;
     *             a run "passed" when every evaluator result passed.
     */
    public static ScenarioPassK aggregate(String scenarioId, List<List<EvaluationResult>> runs) {
        if (runs == null || runs.isEmpty()) throw new IllegalArgumentException("at least one run is required");
        int k = runs.size();
        long passedRuns = runs.stream().filter(PassK::runPassed).count();
        double passRate = (double) passedRuns / k;
        double passPowK = passedRuns == k ? 1.0 : 0.0;
        double passAtK = passedRuns >= 1 ? 1.0 : 0.0;
        FailureLayer layer = layerOf(passedRuns, k);
        return new ScenarioPassK(scenarioId, k, passedRuns, passRate, passPowK, passAtK, layer);
    }

    /** Aggregates a full experiment: each entry is one scenario's repeated runs. */
    public static Summary summarize(Map<String, List<List<EvaluationResult>>> scenarioRuns) {
        List<ScenarioPassK> perScenario = scenarioRuns.entrySet().stream()
                .map(e -> aggregate(e.getKey(), e.getValue()))
                .toList();
        int scenarios = perScenario.size();
        double passPowK = perScenario.stream().filter(s -> s.layer() == FailureLayer.STABLE_PASS).count()
                / (double) scenarios;
        double avgPassRate = perScenario.stream().mapToDouble(ScenarioPassK::passRate).average().orElse(0.0);
        Map<FailureLayer, List<String>> layered = perScenario.stream()
                .collect(Collectors.groupingBy(ScenarioPassK::layer,
                        Collectors.mapping(ScenarioPassK::scenarioId, Collectors.toList())));
        return new Summary(perScenario, scenarios, passPowK, avgPassRate, layered);
    }

    static boolean runPassed(List<EvaluationResult> results) {
        return results != null && !results.isEmpty() && results.stream().allMatch(EvaluationResult::passed);
    }

    private static FailureLayer layerOf(long passedRuns, int k) {
        if (passedRuns == k) return FailureLayer.STABLE_PASS;
        if (passedRuns == 0) return FailureLayer.STABLE_FAIL;
        return FailureLayer.FLAKY;
    }

    public enum FailureLayer { STABLE_PASS, FLAKY, STABLE_FAIL }

    public record ScenarioPassK(
            String scenarioId, int k, long passedRuns,
            double passRate, double passPowK, double passAtK, FailureLayer layer) {}

    public record Summary(
            List<ScenarioPassK> perScenario, int scenarios,
            double passPowK, double avgPassRate,
            Map<FailureLayer, List<String>> layered) {}
}
