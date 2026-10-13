package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Judge calibration math (board T6): confusion matrix, precision/recall/F1 and
 * Cohen's kappa over labeled judge verdicts - pure functions, no LLM.
 */
class JudgeCalibrationTest {

    private static JudgeCalibration.Sample s(String behavior, boolean expected, boolean judged) {
        return new JudgeCalibration.Sample(behavior, expected, judged, judged ? 1 : 0, "r");
    }

    @Test
    void perfectAgreementGivesKappaOne() {
        var m = JudgeCalibration.metrics(List.of(
                s("correct", true, true), s("correct", true, true),
                s("loop", false, false), s("forbidden_action", false, false)));
        assertEquals(1.0, m.accuracy());
        assertEquals(1.0, m.precision());
        assertEquals(1.0, m.recall());
        assertEquals(1.0, m.f1());
        assertEquals(1.0, m.kappa(), 1e-9);
        assertEquals(2, m.truePositive());
        assertEquals(2, m.trueNegative());
    }

    @Test
    void alwaysPassJudgeScoresHighAccuracyButLowKappa() {
        // 2 true passes, 2 true fails; judge says pass to everything:
        // accuracy 0.5 but kappa 0 - exactly why the headline metric is kappa.
        var m = JudgeCalibration.metrics(List.of(
                s("correct", true, true), s("correct", true, true),
                s("loop", false, true), s("cheat_hardcode", false, true)));
        assertEquals(0.5, m.accuracy(), 1e-9);
        assertEquals(1.0, m.recall(), 1e-9);
        assertEquals(0.5, m.precision(), 1e-9); // 2 true passes out of 4 total "pass" verdicts
        assertTrue(m.kappa() < 0.05, "kappa should collapse to chance: " + m.kappa());
    }

    @Test
    void invertedJudgeGetsNegativeKappa() {
        var m = JudgeCalibration.metrics(List.of(
                s("correct", true, false), s("loop", false, true)));
        assertEquals(0.0, m.accuracy(), 1e-9);
        assertTrue(m.kappa() < 0, "systematic disagreement is worse than chance");
        assertEquals(1, m.falseNegative());
        assertEquals(1, m.falsePositive());
    }

    @Test
    void perBehaviorBreakdownPreservesOrderAndGroups() {
        var by = JudgeCalibration.perBehavior(List.of(
                s("correct", true, true),
                s("loop", false, false), s("loop", false, true),
                s("correct", true, true)));
        // LinkedHashMap keeps first-seen order: correct, loop
        assertEquals(List.of("correct", "loop"), by.keySet().stream().toList());
        assertEquals(1.0, by.get("correct").accuracy(), 1e-9);
        assertEquals(0.5, by.get("loop").accuracy(), 1e-9);
        assertEquals(1, by.get("loop").falsePositive());
    }

    @Test
    void emptyCorpusYieldsZerosWithoutDivisionErrors() {
        var m = JudgeCalibration.metrics(List.of());
        assertEquals(0.0, m.accuracy());
        assertEquals(0.0, m.kappa());
    }
}
