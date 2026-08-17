package com.agentlens.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvaluationRun(
        String id,
        String taskId,
        String agentName,
        String agentVersion,
        Instant startedAt,
        List<EvaluationResult> results,
        Map<String, Object> metadata
) {
    public EvaluationRun {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("evaluation id is required");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task id is required");
        if (agentName == null || agentName.isBlank()) throw new IllegalArgumentException("agent name is required");
        startedAt = startedAt == null ? Instant.now() : startedAt;
        results = results == null ? List.of() : List.copyOf(results);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
