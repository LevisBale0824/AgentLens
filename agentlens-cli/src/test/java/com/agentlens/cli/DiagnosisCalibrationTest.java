package com.agentlens.cli;

import com.agentlens.eval.DiagnosisEngine;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 (credibility plan): ground-truth-by-construction calibration of the
 * diagnosis engine over the deterministic mock-behavior corpus.
 */
class DiagnosisCalibrationTest {

    @Test
    void defaultCorpusFullyMatchesDefaultEngine() {
        var report = DiagnosisCalibration.calibrate(
                DiagnosisCalibration.defaultCorpus(), new DiagnosisEngine());
        assertEquals(0, report.falsePositives().size(),
                "empty-expectation behaviors (correct/answer-layer defects) must produce zero diagnoses");
        assertEquals(report.totalCases(), report.matchedCases(),
                () -> "mismatched: " + report.cases().stream().filter(c -> !c.match()).toList());
        assertEquals(1.0, report.caseAccuracy(), 1e-9);
    }

    @Test
    void ablationRemovingRuleCausesTargetedMisses() {
        var noSafety = DiagnosisCalibration.calibrate(
                DiagnosisCalibration.defaultCorpus(), new DiagnosisEngine(Set.of("forbiddenToolCalls")));
        assertTrue(noSafety.cases().stream()
                        .filter(c -> c.behavior().equals("forbidden_action"))
                        .allMatch(c -> !c.match()),
                "disabling forbiddenToolCalls must break the forbidden_action case");
        assertTrue(noSafety.falsePositives().isEmpty(),
                "ablation may only cause misses, never false positives");

        var noSelection = DiagnosisCalibration.calibrate(
                DiagnosisCalibration.defaultCorpus(), new DiagnosisEngine(Set.of("unknownToolCalls")));
        assertTrue(noSelection.cases().stream()
                        .filter(c -> c.behavior().equals("wrong_tool")
                                || c.behavior().equals("cheat_tamper")
                                || c.behavior().equals("cheat_hardcode"))
                        .allMatch(c -> !c.match()),
                "disabling unknownToolCalls must break the selection-layer cases");
    }

    @Test
    void reportCountsConsistentAndUnknownRuleRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisEngine(Set.of("bogus")));
        var report = DiagnosisCalibration.calibrate(
                DiagnosisCalibration.defaultCorpus(), new DiagnosisEngine());
        assertEquals(report.cases().size(), report.totalCases());
        assertTrue(report.layers().stream().map(DiagnosisCalibration.LayerMetric::layer)
                        .anyMatch(l -> l.equals("tool_selection")),
                "wrong_tool + both cheat behaviors should give tool_selection metrics");
        assertTrue(report.confusion().containsKey("safety"));
        assertTrue(report.confusion().containsKey("execution"));
        assertTrue(report.confusion().containsKey("final_answer"));
    }
}
