package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S3 (credibility plan): paired significance statistics - hand-checked examples. */
class CompareStatisticsTest {

    @Test
    void mcnemarMatchesHandComputation() {
        // 3 scenarios, k=1: S1 baseline-only pass, S2 baseline-only pass, S3 both pass.
        Map<String, List<Boolean>> base = Map.of(
                "s1", List.of(true), "s2", List.of(true), "s3", List.of(true));
        Map<String, List<Boolean>> cand = Map.of(
                "s1", List.of(false), "s2", List.of(false), "s3", List.of(true));
        var m = CompareStatistics.mcnemar(base, cand);
        assertEquals(1, m.bothPass());
        assertEquals(2, m.baselineOnlyPass());
        assertEquals(0, m.candidateOnlyPass());
        assertEquals(0, m.bothFail());
        // exact two-sided: p = 2 * P(X<=0 | Binom(2,0.5)) = 2 * 0.25 = 0.5
        assertEquals(0.5, m.pValue(), 1e-12);
    }

    @Test
    void mcnemarIdenticalSidesGivesPValueOne() {
        Map<String, List<Boolean>> base = Map.of(
                "s1", List.of(true, true), "s2", List.of(false, false));
        Map<String, List<Boolean>> cand = Map.of(
                "s1", List.of(true, true), "s2", List.of(false, true));
        var m = CompareStatistics.mcnemar(base, cand);
        // stability is all-k-pass: s1 passes both sides, s2 (still not all-pass on
        // either side) fails both -> no discordant pairs even though raw runs differ
        assertEquals(1, m.bothPass());
        assertEquals(1, m.bothFail());
        assertEquals(0, m.baselineOnlyPass() + m.candidateOnlyPass());
        assertEquals(1.0, m.pValue(), 1e-12);
    }

    @Test
    void bootstrapCiCoversKnownDelta() {
        // baseline: 5 scenarios all passing; candidate: 3 of 5 passing (k=1 clarity).
        Map<String, List<Boolean>> base = Map.of(
                "s1", List.of(true), "s2", List.of(true), "s3", List.of(true),
                "s4", List.of(true), "s5", List.of(true));
        Map<String, List<Boolean>> cand = Map.of(
                "s1", List.of(true), "s2", List.of(true), "s3", List.of(true),
                "s4", List.of(false), "s5", List.of(false));
        var ci = CompareStatistics.bootstrapDeltaCi(base, cand, 10_000, 42L);
        assertEquals(-0.4, ci.delta(), 1e-12);
        assertTrue(ci.ciLow() <= ci.delta() && ci.delta() <= ci.ciHigh(),
                "point estimate must lie inside its CI");
        // with only 5 scenarios the resampled delta takes 6 distinct values -0.4..0.4; CI spans 0
        assertTrue(ci.ciLow() <= 0 && 0 <= ci.ciHigh(),
                "a 2/5 drop on 5 scenarios is not significant - CI must include 0");
    }

    @Test
    void pairedScenariosAreTheIntersection() {
        Map<String, List<Boolean>> base = Map.of("a", List.of(true), "b", List.of(true), "c", List.of(true));
        Map<String, List<Boolean>> cand = Map.of("b", List.of(true), "c", List.of(false), "d", List.of(true));
        assertEquals(List.of("b", "c"), CompareStatistics.pairedScenarios(base, cand));
    }
}
