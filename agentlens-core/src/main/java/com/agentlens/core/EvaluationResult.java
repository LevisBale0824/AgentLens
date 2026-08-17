package com.agentlens.core;

import java.util.Map;

public record EvaluationResult(
        String evaluator,
        boolean passed,
        double score,
        String reason,
        Map<String, Object> evidence
) {
    public EvaluationResult {
        if (evaluator == null || evaluator.isBlank()) throw new IllegalArgumentException("evaluator is required");
        if (score < 0 || score > 1) throw new IllegalArgumentException("score must be between 0 and 1");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
