package com.agentlens.core;

import java.util.Map;

/**
 * Scorer contract (P1): every score attached to a run speaks the same shape.
 *
 * <ul>
 *   <li>{@code value}  - the numeric score in [0,1] (field {@code score})</li>
 *   <li>{@code reason} - short deterministic verdict code / one-liner</li>
 *   <li>{@code answer} - what the agent actually answered (bounded excerpt)</li>
 *   <li>{@code explanation} - human-readable justification (judge verdict
 *       detail, assertion outcomes, limit math); optional, evaluators that
 *       have nothing beyond the reason may leave it null</li>
 * </ul>
 *
 * The 5-arg constructor stays source-compatible for deterministic evaluators
 * without an answer/explanation dimension.
 */
public record EvaluationResult(
        String evaluator,
        boolean passed,
        double score,
        String reason,
        Map<String, Object> evidence,
        String answer,
        String explanation
) {
    public EvaluationResult {
        if (evaluator == null || evaluator.isBlank()) throw new IllegalArgumentException("evaluator is required");
        if (score < 0 || score > 1) throw new IllegalArgumentException("score must be between 0 and 1");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        answer = normalize(answer);
        explanation = normalize(explanation);
    }

    /** Backward-compatible constructor: no answer/explanation dimension. */
    public EvaluationResult(String evaluator, boolean passed, double score,
                            String reason, Map<String, Object> evidence) {
        this(evaluator, passed, score, reason, evidence, null, null);
    }

    private static String normalize(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Bounded single-line excerpt used to fill the answer dimension. */
    public static String excerpt(String text, int maxChars) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars - 1) + "…";
    }
}
