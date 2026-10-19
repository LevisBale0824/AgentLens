package com.agentlens.eval;

import com.agentlens.core.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 (credibility plan S2): mathematical verification of the PassK reducers -
 * ordering properties on random inputs, brute-force cross-check on small
 * exhaustive enumeration, and closed-form p^k convergence via Monte Carlo.
 * Fixed seeds keep the "statistical" tests deterministic.
 */
class PassKPropertiesTest {

    private static EvaluationResult result(boolean passed) {
        return new EvaluationResult("task-completion", passed, passed ? 1.0 : 0.0, "auto", Map.of());
    }

    /** Property 1: per-scenario pass^k <= passRate <= pass@k, and summary pass^k <= avgPassRate. */
    @Test
    void orderingHoldsOnRandomInputs() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 1000; trial++) {
            int scenarios = 1 + rnd.nextInt(5);
            Map<String, List<List<EvaluationResult>>> runs = new LinkedHashMap<>();
            for (int s = 0; s < scenarios; s++) {
                int k = 1 + rnd.nextInt(5);
                List<List<EvaluationResult>> reps = new ArrayList<>();
                for (int rep = 0; rep < k; rep++) {
                    reps.add(List.of(result(rnd.nextBoolean()), result(rnd.nextBoolean())));
                }
                runs.put("s" + s, reps);
            }
            PassK.Summary summary = PassK.summarize(runs);
            assertTrue(summary.passPowK() <= summary.avgPassRate() + 1e-12,
                    "summary pass^k must not exceed avg pass rate");
            for (PassK.ScenarioPassK sc : summary.perScenario()) {
                assertTrue(sc.passPowK() <= sc.passRate() + 1e-12,
                        "pass^k is a lower bound of the pass rate");
                assertTrue(sc.passRate() <= sc.passAtK() + 1e-12,
                        "pass@k (at-least-one) is an upper bound of the pass rate");
            }
        }
    }

    /**
     * Property 2: exhaustive small enumeration - aggregate() (EvaluationResult
     * path) and summarizeRaw() (raw outcomes path) must agree with each other
     * and with brute force on every k x n outcome matrix, k,n <= 3.
     */
    @Test
    void bruteForceAgreesAcrossBothReducerPaths() {
        int matrices = 0;
        for (int k = 1; k <= 3; k++) {
            List<List<Boolean>> outcomes = new ArrayList<>();
            for (int rep = 0; rep < k; rep++) {
                outcomes.add(List.of(false, true));
            }
            matrices += enumerate(outcomes, 0, new ArrayList<>());
        }
        assertTrue(matrices >= 2 + 4 + 8, "must cover all 2^k outcome shapes for k=1..3 (14 total)");
    }

    /** Recurses over all 2^k outcome rows of one scenario and checks every path. */
    private static int enumerate(List<List<Boolean>> domain, int depth, List<Boolean> current) {
        if (depth == domain.size()) {
            int k = current.size();
            String id = "case";
            Map<String, List<Boolean>> raw = Map.of(id, List.copyOf(current));
            PassK.Summary fromRaw = PassK.summarizeRaw(raw);
            Map<String, List<List<EvaluationResult>>> structured = new LinkedHashMap<>();
            List<List<EvaluationResult>> reps = new ArrayList<>();
            for (Boolean pass : current) {
                reps.add(List.of(result(pass)));
            }
            structured.put(id, reps);
            PassK.Summary fromResults = PassK.summarize(structured);

            long passedRuns = current.stream().filter(Boolean::booleanValue).count();
            PassK.FailureLayer expectedLayer = passedRuns == k ? PassK.FailureLayer.STABLE_PASS
                    : passedRuns == 0 ? PassK.FailureLayer.STABLE_FAIL : PassK.FailureLayer.FLAKY;

            assertEquals(expectedLayer, fromRaw.perScenario().get(0).layer(), "raw layer, outcomes=" + current);
            assertEquals(fromRaw.perScenario().get(0).layer(), fromResults.perScenario().get(0).layer(),
                    "both paths agree, outcomes=" + current);
            assertEquals(passedRuns == k ? 1.0 : 0.0, fromRaw.perScenario().get(0).passPowK());
            assertEquals(passedRuns >= 1 ? 1.0 : 0.0, fromRaw.perScenario().get(0).passAtK());
            assertEquals((double) passedRuns / k, fromRaw.perScenario().get(0).passRate());

            // at-least-m boundaries: m=k equals pass^k, m=1 equals pass@k
            assertEquals(passedRuns == k, PassK.rawPassAtLeast(current, k));
            assertEquals(passedRuns >= 1, PassK.rawPassAtLeast(current, 1));
            return 1;
        }
        int count = 0;
        for (Boolean value : domain.get(depth)) {
            current.add(value);
            count += enumerate(domain, depth + 1, current);
            current.remove(current.size() - 1);
        }
        return count;
    }

    /**
     * Property 3: for a scenario with constant per-run pass probability p,
     * the empirical share of STABLE_PASS outcomes converges to the closed
     * form p^k (Bernoulli product) within 4 standard errors.
     */
    @Test
    void constantProbabilityConvergesToClosedForm() {
        Random rnd = new Random(42);
        int trials = 200_000;
        double[] ps = {0.3, 0.7, 0.9};
        int[] ks = {3, 5};
        for (double p : ps) {
            for (int k : ks) {
                long stablePass = 0;
                for (int t = 0; t < trials; t++) {
                    boolean all = true;
                    for (int i = 0; i < k && all; i++) {
                        all = rnd.nextDouble() < p;
                    }
                    if (all) {
                        stablePass++;
                    }
                }
                double empirical = (double) stablePass / trials;
                double closedForm = Math.pow(p, k);
                double sigma = Math.sqrt(closedForm * (1 - closedForm) / trials);
                assertTrue(Math.abs(empirical - closedForm) <= 4 * sigma,
                        () -> String.format("p=%.1f k=%d: empirical %.4f vs closed form %.4f (4sigma=%.4f)",
                                p, k, empirical, closedForm, 4 * sigma));
            }
        }
    }
}
