package com.agentlens.core;

import java.util.Map;

public record ToolCall(String name, Map<String, Object> arguments, String result, boolean success) {
    public ToolCall {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name is required");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
