package com.agentlens.core;

import java.util.Map;

public record AgentTask(String id, String instruction, Map<String, Object> context, int maxSteps) {
    public AgentTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("task id is required");
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("instruction is required");
        context = context == null ? Map.of() : Map.copyOf(context);
        if (maxSteps <= 0) maxSteps = 10;
    }
}
