package com.agentlens.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Monte Carlo power analysis for the Level-3 regression gate (credibility
 * plan S3). Simulates paired baseline/candidate experiments through the REAL
 * product reducers ({@link PassK#summarizeRaw}) and the REAL gate
 * ({@link RegressionGate}), so the measured false-alarm and detection rates
 * describe the shipped behavior, not a parallel model of it.
 *
 * <p>Core quantity: with default tolerance 0 and any per-run variance, the
 * candidate's avg pass rate almost never ties the baseline's, so two
 * identical-quality versions false-alarm about half the time. Tolerance
 * trades a little detection power for a controlled false-alarm rate.
 */
public final class GatePower {

    private GatePower() {
    }

    /**
     * Simulates one paired experiment: returns true when the gate FAILS.
     * candidateShift subtracts from the candidate's per-run pass probability
     * (0 = identical quality, the false-alarm condition).
     */
    static boolean gateFails(double p, double candidateShift, int scenarios, int k,
                             double tolerance, Random rnd) {
        Map<String, List<Boolean>> base = new LinkedHashMap<>();
        Map<String, List<Boolean>> cand = new LinkedHashMap<>();
        for (int s = 0; s < scenarios; s++) {
            Boolean[] baseRuns = new Boolean[k];
            Boolean[] candRuns = new Boolean[k];
            for (int i = 0; i < k; i++) {
                baseRuns[i] = rnd.nextDouble() < p;
                candRuns[i] = rnd.nextDouble() < (p - candidateShift);
            }
            base.put("s" + s, List.of(baseRuns));
            cand.put("s" + s, List.of(candRuns));
        }
        PassK.Summary baseSummary = PassK.summarizeRaw(base);
        PassK.Summary candSummary = PassK.summarizeRaw(cand);
        return !new RegressionGate(List.of(), tolerance).compare(baseSummary, candSummary).passed();
    }

    /**
     * Share of failed gates when baseline and candidate have IDENTICAL
     * quality (the false-alarm rate). Uses per-trial reseeding from a master
     * seed so results are deterministic for a given parameter set.
     */
    public static double falseAlarmRate(double p, int scenarios, int k,
                                        double tolerance, int trials, long seed) {
        Random master = new Random(seed);
        long fails = 0;
        for (int t = 0; t < trials; t++) {
            if (gateFails(p, 0.0, scenarios, k, tolerance, new Random(master.nextLong()))) {
                fails++;
            }
        }
        return (double) fails / trials;
    }

    /**
     * Share of failed gates when the candidate is WORSE by {@code delta}
     * (per-run pass probability p-delta) - the detection power.
     */
    public static double detectionPower(double p, double delta, int scenarios, int k,
                                        double tolerance, int trials, long seed) {
        Random master = new Random(seed);
        long fails = 0;
        for (int t = 0; t < trials; t++) {
            if (gateFails(p, delta, scenarios, k, tolerance, new Random(master.nextLong()))) {
                fails++;
            }
        }
        return (double) fails / trials;
    }

    /** One standard error of the standard error of a rate estimated from n trials. */
    public static double sigma(double rate, int trials) {
        return Math.sqrt(Math.max(rate * (1 - rate), 1e-12) / trials);
    }
}
