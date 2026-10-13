package com.agentlens.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judge calibration math (ROADMAP P2-1 follow-up / board T6): agreement metrics
 * between a judge's verdicts and ground-truth labels over a labeled corpus.
 * Pure functions - no LLM access - so the numbers are unit-testable and the
 * corpus runner can live in the CLI.
 *
 * <p>Cohen's kappa measures agreement beyond chance, the headline number for
 * judge calibration: kappa = (po - pe) / (1 - pe) where po is observed
 * agreement and pe the agreement expected from the marginals alone. A judge
 * that says "pass" to everything can score high accuracy on a positive-heavy
 * corpus while kappa exposes it.
 */
public final class JudgeCalibration {

    private JudgeCalibration() {}

    /** One judged corpus sample: expected verdict is ground truth, judged is the model's. */
    public record Sample(String behavior, boolean expectedPass, boolean judgedPass,
                         double score, String reason) {}

    /** Confusion matrix plus derived agreement metrics. */
    public record Metrics(int truePositive, int trueNegative, int falsePositive, int falseNegative,
                          double observedAgreement, double expectedAgreement,
                          double accuracy, double precision, double recall, double f1, double kappa) {}

    public static Metrics metrics(List<Sample> samples) {
        int tp = 0, tn = 0, fp = 0, fn = 0;
        for (Sample s : samples) {
            if (s.expectedPass() && s.judgedPass()) tp++;
            else if (!s.expectedPass() && !s.judgedPass()) tn++;
            else if (!s.expectedPass()) fp++;
            else fn++;
        }
        int total = samples.size();
        double accuracy = total == 0 ? 0.0 : (double) (tp + tn) / total;
        double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);

        // Cohen's kappa over the binary pass/fail coding.
        double expectedPassRate = total == 0 ? 0.0 : (double) (tp + fn) / total;
        double judgedPassRate = total == 0 ? 0.0 : (double) (tp + fp) / total;
        double po = accuracy;
        double pe = expectedPassRate * judgedPassRate + (1 - expectedPassRate) * (1 - judgedPassRate);
        double kappa = pe >= 1.0 ? 0.0 : (po - pe) / (1 - pe);

        return new Metrics(tp, tn, fp, fn, po, pe, accuracy, precision, recall, f1, kappa);
    }

    /** Per-behavior breakdown, preserving first-seen order (stable reports). */
    public static Map<String, Metrics> perBehavior(List<Sample> samples) {
        Map<String, List<Sample>> byBehavior = new LinkedHashMap<>();
        for (Sample s : samples) byBehavior.computeIfAbsent(s.behavior(), b -> new ArrayList<>()).add(s);
        Map<String, Metrics> out = new LinkedHashMap<>();
        byBehavior.forEach((behavior, group) -> out.put(behavior, metrics(group)));
        return out;
    }
}
