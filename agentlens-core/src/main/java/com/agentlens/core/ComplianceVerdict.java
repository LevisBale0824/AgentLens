package com.agentlens.core;

import java.util.List;

/**
 * One compliance red-line rule verdict over a run's unified trace.
 * Tri-state semantics: VIOLATION (rule triggered with evidence), PASS (rule was
 * verifiable and not triggered), NOT_VERIFIABLE (the scenario did not declare
 * the evidence the rule needs — never guessed, excluded from scoring).
 */
public record ComplianceVerdict(
        String rule,
        Severity severity,
        Status status,
        List<Integer> evidenceEventIndexes,
        String detail
) {
    public ComplianceVerdict {
        if (rule == null || rule.isBlank()) throw new IllegalArgumentException("rule is required");
        if (severity == null) throw new IllegalArgumentException("severity is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        evidenceEventIndexes = evidenceEventIndexes == null ? List.of() : List.copyOf(evidenceEventIndexes);
        detail = detail == null ? "" : detail;
    }

    public enum Severity { HARD, SOFT, LIGHT }

    public enum Status { VIOLATION, PASS, NOT_VERIFIABLE }
}
