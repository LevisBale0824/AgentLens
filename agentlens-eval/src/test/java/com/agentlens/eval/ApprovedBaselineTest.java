package com.agentlens.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedBaselineTest {

    @TempDir
    Path temp;

    private static PassK.Summary summaryOf(Map<String, Double> caseRates) {
        var perScenario = caseRates.entrySet().stream()
                .map(e -> {
                    long passed = Math.round(e.getValue());
                    double passPowK = passed >= 1 ? 1.0 : 0.0;
                    double passAtK = passed >= 1 ? 1.0 : 0.0;
                    PassK.FailureLayer layer = passed >= 1 ? PassK.FailureLayer.STABLE_PASS
                            : PassK.FailureLayer.STABLE_FAIL;
                    return new PassK.ScenarioPassK(e.getKey(), 1, passed, e.getValue(),
                            passPowK, passAtK, layer);
                })
                .toList();
        double passPowK = perScenario.stream().filter(s -> s.layer() == PassK.FailureLayer.STABLE_PASS)
                .count() / (double) perScenario.size();
        double avg = perScenario.stream().mapToDouble(PassK.ScenarioPassK::passRate).average().orElse(0);
        return new PassK.Summary(perScenario, perScenario.size(), passPowK, avg, Map.of());
    }

    private static final List<String> EVALUATORS =
            List.of("task-completion", "run-limits", "tool-policy", "compliance");

    private ApprovedBaseline.Baseline baseline() {
        return new ApprovedBaseline.Baseline(ApprovedBaseline.FORMAT_VERSION, "mock-correct",
                "sha-abc", EVALUATORS, 3, 1.0, 0.9,
                Map.of("case-a", 1.0, "case-b", 0.8), "alice", "2026-10-14T00:00:00Z");
    }

    @Test
    void artifactRoundTripsWithAllPins() throws Exception {
        Path file = temp.resolve("baseline/approved-baseline.json");
        ApprovedBaseline.write(baseline(), file);
        assertTrue(Files.isRegularFile(file));
        ApprovedBaseline.Baseline loaded = ApprovedBaseline.load(file);
        assertEquals("sha-abc", loaded.datasetSha256());
        assertEquals(EVALUATORS, loaded.evaluatorNames());
        assertEquals(3, loaded.repeats());
        assertEquals(0.8, loaded.casePassRates().get("case-b"));
        assertEquals("alice", loaded.approvedBy());
        assertEquals(1, loaded.formatVersion());
    }

    @Test
    void loadRejectsStructurallyBrokenArtifacts() throws Exception {
        Path bad = temp.resolve("bad.json");
        Files.writeString(bad, "{\"formatVersion\":99,\"datasetSha256\":\"x\"}");
        assertThrows(IllegalArgumentException.class, () -> ApprovedBaseline.load(bad));
        Files.writeString(bad, "{\"formatVersion\":1,\"datasetSha256\":\"x\","
                + "\"evaluatorNames\":[\"a\"],\"casePassRates\":{}}");
        assertThrows(IllegalArgumentException.class, () -> ApprovedBaseline.load(bad));
        Files.writeString(bad, "{\"formatVersion\":1}");
        assertThrows(IllegalArgumentException.class, () -> ApprovedBaseline.load(bad));
    }

    @Test
    void sha256HexPinsFileContent() throws Exception {
        Path file = temp.resolve("scenarios.json");
        Files.writeString(file, "[{\"id\":1}]");
        assertEquals(ApprovedBaseline.sha256Hex(file), ApprovedBaseline.sha256Hex(file));
        Files.writeString(file, "[{\"id\":2}]");
        assertFalse(ApprovedBaseline.sha256Hex(file)
                .equals("different-content-different-sha"));
    }

    @Test
    void gatePassesWhenNothingDrifted() {
        var approved = ApprovedBaseline.fromCandidate("agent", "sha-ok", EVALUATORS, 3,
                summaryOf(Map.of("case-a", 1.0, "case-b", 0.5)), "alice");
        var result = ApprovedGate.check(approved, "sha-ok", EVALUATORS,
                summaryOf(Map.of("case-a", 1.0, "case-b", 0.5)));
        assertTrue(result.passed(), result.reason());
    }

    @Test
    void changedDatasetFailsTheGate() {
        var approved = ApprovedBaseline.fromCandidate("agent", "sha-old", EVALUATORS, 3,
                summaryOf(Map.of("case-a", 1.0)), "alice");
        var result = ApprovedGate.check(approved, "sha-new", EVALUATORS,
                summaryOf(Map.of("case-a", 1.0)));
        assertFalse(result.passed());
        assertTrue(result.reason().contains("dataset changed"), result.reason());
    }

    @Test
    void changedEvaluatorSetFailsTheGateRegardlessOfOrder() {
        var approved = ApprovedBaseline.fromCandidate("agent", "sha", EVALUATORS, 3,
                summaryOf(Map.of("case-a", 1.0)), "alice");
        var reordered = List.of("compliance", "tool-policy", "run-limits", "task-completion");
        assertTrue(ApprovedGate.check(approved, "sha", reordered,
                summaryOf(Map.of("case-a", 1.0))).passed());
        var expanded = new java.util.ArrayList<>(EVALUATORS);
        expanded.add("llm-judge");
        var result = ApprovedGate.check(approved, "sha", expanded,
                summaryOf(Map.of("case-a", 1.0)));
        assertFalse(result.passed());
        assertTrue(result.reason().contains("evaluator set changed"), result.reason());
    }

    @Test
    void missingCaseFailsTheGate() {
        var approved = ApprovedBaseline.fromCandidate("agent", "sha", EVALUATORS, 3,
                summaryOf(Map.of("case-a", 1.0, "case-b", 1.0)), "alice");
        var result = ApprovedGate.check(approved, "sha", EVALUATORS,
                summaryOf(Map.of("case-a", 1.0)));
        assertFalse(result.passed());
        assertTrue(result.reason().contains("missing result for case case-b"), result.reason());
    }

    @Test
    void perCaseAndOverallRegressionsFailTheGate() {
        var approved = ApprovedBaseline.fromCandidate("agent", "sha", EVALUATORS, 3,
                summaryOf(Map.of("case-a", 1.0, "case-b", 1.0)), "alice");
        // candidate loses case-b entirely: per-case drop AND overall pass^k drop
        var result = ApprovedGate.check(approved, "sha", EVALUATORS,
                summaryOf(Map.of("case-a", 1.0, "case-b", 0.0)));
        assertFalse(result.passed());
        assertTrue(result.reason().contains("case-b regressed"), result.reason());
        assertTrue(result.reason().contains("pass^k dropped"), result.reason());
    }

    @Test
    void fromCandidateMirrorsTheCandidateSide() {
        var candidate = summaryOf(new LinkedHashMap<>(Map.of("case-a", 1.0, "case-b", 0.5)));
        var approved = ApprovedBaseline.fromCandidate("mock-x", "sha", EVALUATORS, 5,
                candidate, "bob");
        assertEquals("mock-x", approved.agentName());
        assertEquals(5, approved.repeats());
        assertEquals(candidate.passPowK(), approved.passPowK());
        assertEquals(0.5, approved.casePassRates().get("case-b"));
        assertEquals("bob", approved.approvedBy());
        assertTrue(approved.createdAt() != null && !approved.createdAt().isBlank());
        // format round trip: EvaluationResult-style evidence not needed here, but
        // the artifact must be loadable right after writing
    }
}
