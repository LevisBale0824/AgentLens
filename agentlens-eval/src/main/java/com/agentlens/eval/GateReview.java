package com.agentlens.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-B 口径对齐: assembles the release-gate review payload - gate verdict,
 * both sides' pass^k / avg pass rate, per-case rates, and the evidence chain
 * (failing-run judge reasons / diagnosis notes per case) - as a deterministic
 * prompt for an LLM reviewer. The prompt building is pure and unit-tested;
 * the LLM call itself is {@code LlmJudge.review} (fail-open at the call site).
 */
public final class GateReview {

    private GateReview() {
    }

    /**
     * @param failingEvidence caseId -> why it failed (judge reason, diagnosis
     *                        evidence, ...); keyed by the candidate side's cases
     */
    public static String buildPrompt(String baselineLabel, String candidateLabel,
                                     PassK.Summary baseline, PassK.Summary candidate,
                                     RegressionGate.GateResult gate,
                                     Map<String, String> failingEvidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Release gate review request\n\n")
                .append("Gate verdict: ").append(gate.passed() ? "PASSED" : "FAILED").append("\n")
                .append("Gate reason: ").append(gate.reason()).append("\n\n")
                .append("## Aggregate metrics\n")
                .append(sideLine("baseline", baselineLabel, baseline))
                .append(sideLine("candidate", candidateLabel, candidate));
        if (candidate != null) {
            sb.append("\n## Per-case pass rates\n");
            candidate.perScenario().stream()
                    .sorted(java.util.Comparator.comparing(PassK.ScenarioPassK::scenarioId))
                    .forEach(s -> sb.append("- ").append(s.scenarioId())
                            .append(": k=").append(s.k())
                            .append(" passed=").append(s.passedRuns())
                            .append(" rate=").append(String.format("%.3f", s.passRate()))
                            .append(" layer=").append(s.layer()).append('\n'));
        }
        if (failingEvidence != null && !failingEvidence.isEmpty()) {
            sb.append("\n## Evidence chain (candidate failures)\n");
            failingEvidence.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                            .append(e.getValue()).append('\n'));
        }
        sb.append("""

                Review the gate decision as a release manager. In at most 8 sentences: \
                state whether the candidate should ship, cite the concrete failing cases and \
                their evidence, and call out flakiness (cases neither stable-pass nor \
                stable-fail) explicitly. Do not invent facts that are not in the data.""");
        return sb.toString();
    }

    /** Deterministic fallback review when no LLM judge is configured (fail-open). */
    public static String fallbackReview(String baselineLabel, String candidateLabel,
                                        PassK.Summary baseline, PassK.Summary candidate,
                                        RegressionGate.GateResult gate,
                                        Map<String, String> failingEvidence) {
        String header = gate.passed()
                ? "Gate PASSED - candidate may ship."
                : "Gate FAILED - candidate must not ship (" + gate.reason() + ").";
        StringBuilder sb = new StringBuilder(header).append('\n');
        if (candidate != null) {
            List<String> flaky = candidate.layered()
                    .getOrDefault(PassK.FailureLayer.FLAKY, List.of());
            if (!flaky.isEmpty()) {
                sb.append("Flaky cases (neither stable-pass nor stable-fail): ")
                        .append(String.join(", ", flaky)).append(".\n");
            }
        }
        if (failingEvidence != null && !failingEvidence.isEmpty()) {
            sb.append("Failing cases with evidence:\n");
            failingEvidence.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(8)
                    .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                            .append(e.getValue()).append('\n'));
        }
        sb.append("(LLM review skipped: no judge configured - add --judge for a narrative review.)");
        return sb.toString();
    }

    private static String sideLine(String side, String label, PassK.Summary summary) {
        if (summary == null) return "- " + side + ": " + label + " (frozen baseline, no live metrics)\n";
        return "- " + side + ": " + label + " scenarios=" + summary.scenarios()
                + " pass^k=" + String.format("%.3f", summary.passPowK())
                + " avgPassRate=" + String.format("%.3f", summary.avgPassRate()) + '\n';
    }

    /**
     * Convenience used by the CLI: collects one evidence line per failing case
     * from the raw k-times results (first failing evaluator's reason wins).
     */
    public static Map<String, String> firstFailureReasons(
            Map<String, List<List<com.agentlens.core.EvaluationResult>>> resultsByCase) {
        Map<String, String> out = new LinkedHashMap<>();
        if (resultsByCase == null) return out;
        for (var e : resultsByCase.entrySet()) {
            e.getValue().stream()
                    .flatMap(List::stream)
                    .filter(r -> !r.passed())
                    .findFirst()
                    .ifPresent(r -> out.put(e.getKey(), r.evaluator() + ": " + r.reason()));
        }
        return out;
    }
}
