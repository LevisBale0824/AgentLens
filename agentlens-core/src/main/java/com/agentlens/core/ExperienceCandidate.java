package com.agentlens.core;

import java.util.List;
import java.util.Map;

/** A reviewable, versionable piece of operational experience mined from runs. */
public record ExperienceCandidate(
        String id,
        String layer,
        String trigger,
        String guidance,
        List<String> evidence,
        double confidence,
        int supportCount,
        int successCount,
        String status,
        Map<String, Object> metadata
) {
    public ExperienceCandidate {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("experience id is required");
        if (layer == null || layer.isBlank()) throw new IllegalArgumentException("experience layer is required");
        if (trigger == null || trigger.isBlank()) throw new IllegalArgumentException("experience trigger is required");
        if (guidance == null || guidance.isBlank()) throw new IllegalArgumentException("experience guidance is required");
        if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be between 0 and 1");
        if (supportCount < 0 || successCount < 0 || successCount > supportCount) {
            throw new IllegalArgumentException("invalid experience support counters");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        status = status == null || status.isBlank() ? "CANDIDATE" : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
