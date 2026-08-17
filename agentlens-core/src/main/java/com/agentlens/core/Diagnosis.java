package com.agentlens.core;

import java.util.List;
import java.util.Map;

public record Diagnosis(
        String layer,
        String issue,
        double severity,
        double confidence,
        List<String> evidence,
        String recommendation,
        Map<String, Object> metadata
) {
    public Diagnosis {
        if (layer == null || layer.isBlank()) throw new IllegalArgumentException("diagnosis layer is required");
        if (issue == null || issue.isBlank()) throw new IllegalArgumentException("diagnosis issue is required");
        if (severity < 0 || severity > 1 || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("severity and confidence must be between 0 and 1");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
