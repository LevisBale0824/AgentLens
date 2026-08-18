package com.agentlens.eval;

import com.agentlens.core.*;

import java.util.List;
import java.util.Map;

public final class DiagnosisEngine {
    public List<Diagnosis> diagnose(AgentTask task, AgentRun run, List<EvaluationResult> results) {
        java.util.ArrayList<Diagnosis> out = new java.util.ArrayList<>();
        for (EvaluationResult result : results) if (!result.passed()) {
            String layer = switch (result.evaluator()) {
                case "tool-policy" -> "tool_selection";
                case "run-limits" -> "execution";
                case "task-completion" -> "final_answer";
                default -> "unknown";
            };
            out.add(new Diagnosis(layer, result.reason(), 1.0 - result.score(), 0.8,
                    List.of("evaluator:" + result.evaluator()), "Inspect " + layer + " behavior", Map.of("evidence", result.evidence())));
        }
        if (run.status() == RunStatus.TIMEOUT) out.add(new Diagnosis("execution", "agent timeout", 1.0, 1.0,
                List.of("status:TIMEOUT"), "Reduce steps or increase timeout", Map.of()));
        return List.copyOf(out);
    }

    /** Converts diagnostics into reviewable experience; publishing remains an explicit step. */
    public List<ExperienceCandidate> mineExperience(List<Diagnosis> diagnoses) {
        if (diagnoses == null) return List.of();
        return diagnoses.stream().map(diagnosis -> new ExperienceCandidate(
                "exp-" + Integer.toHexString((diagnosis.layer() + ":" + diagnosis.issue()).hashCode()),
                diagnosis.layer(),
                diagnosis.issue(),
                diagnosis.recommendation(),
                diagnosis.evidence(),
                diagnosis.confidence(),
                1,
                1,
                "CANDIDATE",
                diagnosis.metadata()
        )).toList();
    }
}
