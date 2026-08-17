package com.agentlens.core;

import java.util.ArrayList;
import java.util.List;

public final class TraceValidator {
    private TraceValidator() {}

    public static List<String> validate(AgentRun run) {
        List<String> errors = new ArrayList<>();
        if (run == null) return List.of("run is required");
        if (run.runId() == null || run.runId().isBlank()) errors.add("runId is required");
        if (run.taskId() == null || run.taskId().isBlank()) errors.add("taskId is required");
        if (run.events().stream().anyMatch(e -> e.type() == null)) errors.add("event type is required");
        if (run.status() == RunStatus.SUCCESS && (run.finalAnswer() == null || run.finalAnswer().isBlank())) {
            errors.add("successful run must have finalAnswer");
        }
        return List.copyOf(errors);
    }
}
