package com.agentlens.core;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        EventType type,
        String name,
        Map<String, Object> attributes,
        Instant timestamp,
        long durationMs
) {
    public AgentEvent {
        if (type == null) throw new IllegalArgumentException("event type is required");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        timestamp = timestamp == null ? Instant.now() : timestamp;
        if (durationMs < 0) throw new IllegalArgumentException("duration cannot be negative");
    }
}
