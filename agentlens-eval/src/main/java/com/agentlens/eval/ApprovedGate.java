package com.agentlens.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-D: release gate against the frozen approved-baseline artifact.
 * Semantics follow the Langfuse approved-baseline contract: the dataset and
 * evaluator set must be identical to approval time, missing results fail the
 * gate (a silently skipped case is a regression, not a pass), and neither a
 * per-case nor an overall pass^k drop is tolerated.
 */
public final class ApprovedGate {

    private ApprovedGate() {}

    public static RegressionGate.GateResult check(ApprovedBaseline.Baseline approved,
                                                  String datasetSha256,
                                                  List<String> evaluatorNames,
                                                  PassK.Summary candidate) {
        List<String> violations = new ArrayList<>();

        if (!approved.datasetSha256().equals(datasetSha256))
            violations.add(String.format("dataset changed since approval (sha %s -> %s)",
                    approved.datasetSha256(), datasetSha256));

        var expected = new java.util.TreeSet<>(approved.evaluatorNames());
        var actual = new java.util.TreeSet<>(evaluatorNames);
        if (!expected.equals(actual))
            violations.add(String.format("evaluator set changed since approval %s -> %s",
                    expected, actual));

        Map<String, Double> candidateRates = new HashMap<>();
        candidate.perScenario().forEach(s -> candidateRates.put(s.scenarioId(), s.passRate()));
        for (var caseEntry : approved.casePassRates().entrySet()) {
            Double rate = candidateRates.get(caseEntry.getKey());
            if (rate == null) {
                violations.add("missing result for case " + caseEntry.getKey()
                        + " (missing results fail the gate)");
            } else if (rate < caseEntry.getValue() - 1e-9) {
                violations.add(String.format("case %s regressed: passRate %.3f -> %.3f",
                        caseEntry.getKey(), caseEntry.getValue(), rate));
            }
        }
        if (candidate.passPowK() < approved.passPowK() - 1e-9)
            violations.add(String.format("pass^k dropped %.4f -> %.4f",
                    approved.passPowK(), candidate.passPowK()));

        boolean pass = violations.isEmpty();
        return new RegressionGate.GateResult(pass, -1, -1, pass
                ? "approved-baseline gate passed (dataset+evaluators pinned, no per-case or overall drop)"
                : "approved-baseline gate failed: " + String.join("; ", violations));
    }
}
