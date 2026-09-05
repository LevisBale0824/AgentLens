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

    /**
     * Counts repeated calls to the same tool with equivalent arguments
     * (retry-after-error plus no-progress repeats). CallId-aware: parallel
     * duplicates of an unanswered call are excluded, and same-name calls with
     * different arguments are distinct calls, not repeats.
     */
    public long repeatedToolCallCount() {
        return repeatedToolCallAttempts().stream()
                .filter(a -> a.kind() == ToolRepeatDetector.Kind.RETRY
                        || a.kind() == ToolRepeatDetector.Kind.NO_PROGRESS)
                .count();
    }

    /** Full per-call classification (kind + trace index) for evidence chains. */
    public List<ToolRepeatDetector.Attempt> repeatedToolCallAttempts() {
        ToolRepeatDetector detector = new ToolRepeatDetector();
        List<AgentEvent> all = events;
        for (int i = 0; i < all.size(); i++) detector.onEvent(i, all.get(i));
        return detector.attempts();
    }
}
