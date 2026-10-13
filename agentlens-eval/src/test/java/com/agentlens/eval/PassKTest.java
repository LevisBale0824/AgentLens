package com.agentlens.eval;

import com.agentlens.core.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassKTest {

    private EvaluationResult pass() {
        return new EvaluationResult("task-completion", true, 1.0, "ok", Map.of());
    }

    private EvaluationResult fail() {
        return new EvaluationResult("task-completion", false, 0.0, "no", Map.of());
    }

    @Test
    void allPassYieldsPassPowKOne() {
        var s = PassK.aggregate("scn", List.of(List.of(pass()), List.of(pass()), List.of(pass())));
        assertEquals(1.0, s.passPowK());
        assertEquals(1.0, s.passAtK());
        assertEquals(1.0, s.passRate());
        assertEquals(PassK.FailureLayer.STABLE_PASS, s.layer());
    }

    @Test
    void mixedRunsAreFlaky() {
        // 2/3 passed: pass^k=0, pass@k=1, flaky layer
        var s = PassK.aggregate("scn", List.of(List.of(pass()), List.of(fail()), List.of(pass())));
        assertEquals(0.0, s.passPowK());
        assertEquals(1.0, s.passAtK());
        assertEquals(2.0 / 3, s.passRate(), 1e-9);
        assertEquals(PassK.FailureLayer.FLAKY, s.layer());
    }

    @Test
    void allFailIsStableFail() {
        var s = PassK.aggregate("scn", List.of(List.of(fail()), List.of(fail())));
        assertEquals(0.0, s.passPowK());
        assertEquals(0.0, s.passAtK());
        assertEquals(PassK.FailureLayer.STABLE_FAIL, s.layer());
    }

    @Test
    void anyEvaluatorFailureFailsTheRun() {
        var safetyFail = new EvaluationResult("tool-policy", false, 0.0, "violation", Map.of());
        var s = PassK.aggregate("scn", List.of(
                List.of(pass(), pass()),
                List.of(pass(), safetyFail)));
        assertEquals(1.0, s.passAtK());
        assertEquals(0.0, s.passPowK());
        assertEquals(PassK.FailureLayer.FLAKY, s.layer());
    }

    @Test
    void summarizeAggregatesScenarios() {
        var summary = PassK.summarize(Map.of(
                "stable", List.of(List.of(pass()), List.of(pass())),
                "flaky", List.of(List.of(pass()), List.of(fail())),
                "dead", List.of(List.of(fail()), List.of(fail()))));
        assertEquals(3, summary.scenarios());
        assertEquals(1.0 / 3, summary.passPowK(), 1e-9);
        assertEquals((1.0 + 0.5 + 0.0) / 3, summary.avgPassRate(), 1e-9);
        assertEquals(List.of("stable"), summary.layered().get(PassK.FailureLayer.STABLE_PASS));
        assertEquals(List.of("flaky"), summary.layered().get(PassK.FailureLayer.FLAKY));
        assertEquals(List.of("dead"), summary.layered().get(PassK.FailureLayer.STABLE_FAIL));
    }

    @Test
    void emptyRunsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PassK.aggregate("scn", List.of()));
    }
}
