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
            Map<FailureLayer, List<String>> layered) {
        public Summary {
            perScenario = List.copyOf(perScenario);
            layered = Map.copyOf(layered);
        }
    }

    // ------------------------------------------------------------- raw reducers
    // P1-B 口径对齐: the same metrics recomputed from RAW per-case outcomes, so a
    // stored artifact can answer pass@k / pass^k / at-least-m questions later
    // without re-running the agent (Inspect-epochs-style reducers).

    /** Share of outcomes that passed (the plain per-run pass rate). */
    public static double rawPassRate(List<Boolean> outcomes) {
        return outcomes.isEmpty() ? 0.0
                : (double) outcomes.stream().filter(Boolean::booleanValue).count() / outcomes.size();
    }

    /** pass@k: at least one of the k runs passed. */
    public static boolean rawPassAtK(List<Boolean> outcomes) {
        return outcomes.stream().anyMatch(Boolean::booleanValue);
    }

    /** pass^k: every one of the k runs passed. */
    public static boolean rawPassPowK(List<Boolean> outcomes) {
        return !outcomes.isEmpty() && outcomes.stream().allMatch(Boolean::booleanValue);
    }

    /** at-least-m: at least m of the k runs passed (m = k gives pass^k, m = 1 gives pass@k). */
    public static boolean rawPassAtLeast(List<Boolean> outcomes, int m) {
        return outcomes.stream().filter(Boolean::booleanValue).count() >= m;
    }

    /** Full layering + summary recomputed from raw outcomes (equivalent to aggregate()). */
    public static Summary summarizeRaw(Map<String, List<Boolean>> caseOutcomes) {
        if (caseOutcomes == null || caseOutcomes.isEmpty()) {
            throw new IllegalArgumentException("at least one case is required");
        }
        List<ScenarioPassK> perScenario = caseOutcomes.entrySet().stream()
                .map(e -> new ScenarioPassK(e.getKey(), e.getValue().size(),
                        e.getValue().stream().filter(Boolean::booleanValue).count(),
                        rawPassRate(e.getValue()),
                        rawPassPowK(e.getValue()) ? 1.0 : 0.0,
                        rawPassAtK(e.getValue()) ? 1.0 : 0.0,
                        layerOf(e.getValue().stream().filter(Boolean::booleanValue).count(),
                                e.getValue().size())))
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

    /** Share of cases passing at-least-m of their k runs. */
    public static double rawAtLeastShare(Map<String, List<Boolean>> caseOutcomes, int m) {
        if (caseOutcomes == null || caseOutcomes.isEmpty()) return 0.0;
        return (double) caseOutcomes.values().stream().filter(o -> rawPassAtLeast(o, m)).count()
                / caseOutcomes.size();
    }
}
