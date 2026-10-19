package com.agentlens.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Paired significance statistics for baseline/candidate comparisons
 * (credibility plan S3): a bootstrap confidence interval on the avg-pass-rate
 * delta and an exact McNemar test on scenario-level stability flips. Both
 * operate on the RAW per-scenario outcomes ({@code Map<String, List<Boolean>>})
 * so a comparison artifact can be re-analyzed without re-running anything.
 *
 * <p>Point estimates of a delta say nothing about noise; the CI and the
 * McNemar p-value say whether a "-5pp" is a signal or a shrug. This is the
 * statistical-honesty layer the gate-power analysis motivates.
 */
public final class CompareStatistics {

    private CompareStatistics() {
    }

    /** Bootstrap CI for delta = avgPassRate(candidate) - avgPassRate(baseline). */
    public record CiDelta(double delta, double ciLow, double ciHigh, int resamples) {}

    /** Exact McNemar counts on scenario-level stability (all k runs passed). */
    public record McNemar(int bothPass, int baselineOnlyPass, int candidateOnlyPass, int bothFail,
                          double pValue) {}

    /** Scenario ids present on BOTH sides, sorted (the paired sample). */
    public static List<String> pairedScenarios(Map<String, List<Boolean>> baseline,
                                               Map<String, List<Boolean>> candidate) {
        TreeSet<String> shared = new TreeSet<>(baseline.keySet());
        shared.retainAll(candidate.keySet());
        return List.copyOf(shared);
    }

    private static double avgRate(Map<String, List<Boolean>> outcomes, List<String> scenarios) {
        return scenarios.stream().mapToDouble(s -> PassK.rawPassRate(outcomes.get(s))).average().orElse(0.0);
    }

    /**
     * Percentile bootstrap (resample scenarios with replacement) on the paired
     * avg-pass-rate delta. Deterministic for a given seed.
     */
    public static CiDelta bootstrapDeltaCi(Map<String, List<Boolean>> baseline,
                                           Map<String, List<Boolean>> candidate,
                                           int resamples, long seed) {
        List<String> paired = pairedScenarios(baseline, candidate);
        if (paired.isEmpty()) {
            throw new IllegalArgumentException("no shared scenarios to compare");
        }
        double point = avgRate(candidate, paired) - avgRate(baseline, paired);
        Random rnd = new Random(seed);
        int n = paired.size();
        double[] deltas = new double[resamples];
        List<Double> baseRates = new ArrayList<>(n);
        List<Double> candRates = new ArrayList<>(n);
        for (String s : paired) {
            baseRates.add(PassK.rawPassRate(baseline.get(s)));
            candRates.add(PassK.rawPassRate(candidate.get(s)));
        }
        for (int r = 0; r < resamples; r++) {
            double b = 0;
            double c = 0;
            for (int i = 0; i < n; i++) {
                int pick = rnd.nextInt(n);
                b += baseRates.get(pick);
                c += candRates.get(pick);
            }
            deltas[r] = (c - b) / n;
        }
        java.util.Arrays.sort(deltas);
        double low = deltas[(int) Math.floor(0.025 * (resamples - 1))];
        double high = deltas[(int) Math.ceil(0.975 * (resamples - 1))];
        return new CiDelta(point, low, high, resamples);
    }

    /**
     * Exact McNemar test on scenario-level stability flips: a scenario counts
     * as "pass" when all k runs passed (the pass^k / STABLE_PASS criterion).
     * Two-sided exact binomial p-value over the discordant pairs
     * b = baseline-only passes, c = candidate-only passes:
     * p = min(1, 2 * P(X <= min(b, c))), X ~ Binom(b + c, 0.5).
     */
    public static McNemar mcnemar(Map<String, List<Boolean>> baseline,
                                  Map<String, List<Boolean>> candidate) {
        List<String> paired = pairedScenarios(baseline, candidate);
        int bothPass = 0;
        int b = 0;
        int c = 0;
        int bothFail = 0;
        for (String s : paired) {
            boolean basePass = PassK.rawPassPowK(baseline.get(s));
            boolean candPass = PassK.rawPassPowK(candidate.get(s));
            if (basePass && candPass) {
                bothPass++;
            } else if (basePass) {
                b++;
            } else if (candPass) {
                c++;
            } else {
                bothFail++;
            }
        }
        int discordant = b + c;
        double pValue = 1.0;
        if (discordant > 0) {
            // P(X <= m) for X~Binom(n,0.5): iterate binomial terms upward from i=0.
            int m = Math.min(b, c);
            double term = Math.pow(0.5, discordant);
            double cumulative = term;
            for (int i = 1; i <= m; i++) {
                term = term * (discordant - i + 1) / i;
                cumulative += term;
            }
            pValue = Math.min(1.0, 2 * cumulative);
        }
        return new McNemar(bothPass, b, c, bothFail, pValue);
    }
}
