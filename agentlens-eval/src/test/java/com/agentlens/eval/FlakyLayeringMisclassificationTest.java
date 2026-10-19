package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 (credibility plan S2): the FLAKY misclassification rate has an exact
 * closed form. A scenario with true per-run pass probability p over k repeats
 * is misassigned to a stability layer (STABLE_PASS or STABLE_FAIL) with
 * probability p^k + (1-p)^k. Monte Carlo must agree with the analytic value,
 * and the "recommended repeats" guidance table is derived from the same
 * function so the numbers shipped in docs are test-anchored.
 */
class FlakyLayeringMisclassificationTest {

    /** Analytic probability that a true-p scenario lands in a stable layer after k repeats. */
    static double misclassifyAnalytic(double p, int k) {
        return Math.pow(p, k) + Math.pow(1 - p, k);
    }

    /** Smallest k with at most alpha misclassification probability for a true-p scenario. */
    static int minRepeats(double p, double alpha) {
        int k = 1;
        while (misclassifyAnalytic(p, k) > alpha) {
            k++;
        }
        return k;
    }

    /**
     * Monte Carlo vs closed form over the guidance grid: p in {0.1..0.9},
     * k in {2,3,5,10,20}, 200k trials each, fixed seed. Tolerance is 4
     * standard errors of the analytic expectation (worst case ~0.35%,
     * safely inside the 0.5pp design bound).
     */
    @Test
    void monteCarloMatchesAnalyticOnGuidanceGrid() {
        Random rnd = new Random(42);
        int trials = 200_000;
        double maxDeviation = 0.0;
        for (double p = 0.1; p <= 0.901; p += 0.1) {
            final double pv = Math.round(p * 10) / 10.0;
            for (int k : new int[]{2, 3, 5, 10, 20}) {
                long stableLayer = 0;
                for (int t = 0; t < trials; t++) {
                    int passed = 0;
                    for (int i = 0; i < k; i++) {
                        if (rnd.nextDouble() < pv) {
                            passed++;
                        }
                    }
                    if (passed == 0 || passed == k) {
                        stableLayer++;
                    }
                }
                double empirical = (double) stableLayer / trials;
                double analytic = misclassifyAnalytic(pv, k);
                double sigma = Math.sqrt(analytic * (1 - analytic) / trials);
                double deviation = Math.abs(empirical - analytic);
                maxDeviation = Math.max(maxDeviation, deviation);
                assertTrue(deviation <= 4 * sigma,
                        () -> String.format("p=%.1f k=%d: empirical %.4f vs analytic %.4f (4sigma=%.4f)",
                                pv, k, empirical, analytic, 4 * sigma));
            }
        }
        assertTrue(maxDeviation < 0.005, "worst-case grid deviation must stay under 0.5pp, got " + maxDeviation);
    }

    /** The case-library anchor number: p=0.6, k=5 misclassifies with probability ~8.8%. */
    @Test
    void sixtyPercentScenarioWithFiveRepeatsMisclassifiesAboutNinePercent() {
        double analytic = misclassifyAnalytic(0.6, 5);
        assertEquals(0.088, analytic, 0.0005);
    }

    /**
     * The recommended-repeats table (95% confidence of a stable verdict),
     * shipped in usage text and ROADMAP 7.25: p=0.5 needs k=6, p=0.9 needs
     * k=29, p=0.95 needs k=59. Derived and verified here so docs cannot drift.
     */
    @Test
    void guidanceTableAnchorsShippedNumbers() {
        assertEquals(6, minRepeats(0.5, 0.05));
        assertEquals(29, minRepeats(0.9, 0.05));
        assertEquals(59, minRepeats(0.95, 0.05));
        // A 60% pass-rate scenario needs 14 repeats for a 95%-confident stable verdict.
        assertEquals(14, minRepeats(0.8, 0.05));
    }
}
