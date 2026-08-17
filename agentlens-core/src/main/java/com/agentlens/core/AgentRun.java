package com.agentlens.core;

import java.time.Instant;
import java.util.List;

public record AgentRun(
        String runId,
        String taskId,
        RunStatus status,
        String finalAnswer,
        List<AgentEvent> events,
        Instant startedAt,
        long durationMs,
        int totalTokens,
        String error
) {
    public AgentRun {
        events = events == null ? List.of() : List.copyOf(events);
        status = status == null ? RunStatus.FAILED : status;
        if (durationMs < 0 || totalTokens < 0) throw new IllegalArgumentException("invalid run metrics");
    }

    public long toolCallCount() {
        return events.stream().filter(event -> event.type() == EventType.TOOL_CALL).count();
    }

    public long errorCount() {
        return events.stream().filter(event -> event.type() == EventType.ERROR).count();
    }

    /** Counts repeated calls to the same tool, a useful proxy for avoidable retries. */
    public long repeatedToolCallCount() {
        String previous = null;
        long repeated = 0;
        for (AgentEvent event : events) {
            if (event.type() != EventType.TOOL_CALL) continue;
            if (event.name() != null && event.name().equals(previous)) repeated++;
            previous = event.name();
        }
        return repeated;
    }
}
