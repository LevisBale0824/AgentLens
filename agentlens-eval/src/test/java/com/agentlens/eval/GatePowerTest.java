package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 (credibility plan): Monte Carlo power analysis of the Level-3 gate and
 * paired significance statistics. The headline claim under test: with default
 * tolerance 0, two identical-quality versions false-alarm at a measurable,
 * non-trivial rate, because the candidate's avg pass rate essentially never
 * ties the baseline's.
 */
class GatePowerTest {

    private static final long SEED = 20260917L;

    @Test
    void zeroToleranceFalseAlarmsOnIdenticalQuality() {
        // p=0.9, k=5, 20 scenarios: avg rates are continuous -> P(candidate < baseline) ~ large.
        double falseAlarm = GatePower.falseAlarmRate(0.9, 20, 5, 0.0, 4000, SEED);
        assertTrue(falseAlarm > 0.25, "identical-quality pairs must false-alarm often, got " + falseAlarm);
        assertTrue(falseAlarm < 0.75, "sanity bound, got " + falseAlarm);
    }

    @Test
    void positiveToleranceControlsFalseAlarm() {
        // The pass^k summary term dominates the variance: with 20 scenarios at
        // p=0.9,k=5 the STABLE_PASS share has sigma ~ sqrt(q(1-q)/S) ~ 0.11, so a
        // tolerance must be a couple of SUMMARY-level sigmas (0.3 ~ 2.7 sigma),
        // and the two gate terms are jointly evaluated so headroom is needed.
        double falseAlarm = GatePower.falseAlarmRate(0.9, 20, 5, 0.30, 4000, SEED);
        assertTrue(falseAlarm < 0.02, "tolerance 0.30 must suppress false alarms, got " + falseAlarm);
    }

    @Test
    void detectionPowerDropsRealRegression() {
        // candidate 20pp worse per run: a sane tolerance still catches it almost always.
        double power = GatePower.detectionPower(0.9, 0.20, 20, 5, 0.05, 4000, SEED);
        assertTrue(power > 0.95, "a 20pp regression must be detected, got " + power);
        // and more tolerance costs some power (monotone direction).
        double powerStrict = GatePower.detectionPower(0.9, 0.20, 20, 5, 0.0, 4000, SEED);
        assertTrue(powerStrict >= power - 0.02, "looser tolerance cannot add power");
    }

    @Test
    void discreteSingleRunCaseHasClosedFormFalseAlarm() {
        // k=1, 1 scenario: gate fails iff baseline passes AND candidate fails -> p*(1-p).
        double p = 0.5;
        double expected = p * (1 - p);
        double falseAlarm = GatePower.falseAlarmRate(p, 1, 1, 0.0, 40_000, SEED);
        assertTrue(Math.abs(falseAlarm - expected) <= 4 * GatePower.sigma(expected, 40_000),
                () -> "k=1 closed form p(1-p)=" + expected + ", got " + falseAlarm);
    }
}
