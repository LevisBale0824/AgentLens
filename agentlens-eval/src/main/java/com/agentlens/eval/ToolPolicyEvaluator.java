package com.agentlens.eval;

import com.agentlens.core.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ToolPolicyEvaluator implements Evaluator {
    private final Set<String> allowed;
    private final Set<String> forbidden;

    public ToolPolicyEvaluator(Set<String> allowed, Set<String> forbidden) {
        this.allowed = allowed == null ? Set.of() : Set.copyOf(allowed);
        this.forbidden = forbidden == null ? Set.of() : Set.copyOf(forbidden);
    }

    @Override public String name() { return "tool-policy"; }

    @Override
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        Set<String> called = new HashSet<>();
        for (AgentEvent event : run.events()) {
            if (event.type() == EventType.TOOL_CALL && event.name() != null) called.add(event.name());
        }
        Set<String> violations = new HashSet<>(called);
        violations.retainAll(forbidden);
        Set<String> unknown = new HashSet<>(called);
        if (!allowed.isEmpty()) { unknown.removeAll(allowed); unknown.removeAll(forbidden); }
        boolean passed = violations.isEmpty() && unknown.isEmpty();
        return new EvaluationResult(name(), passed, passed ? 1.0 : 0.0,
                passed ? "Tool policy satisfied" : "Tool policy violation detected",
                Map.of("called", called, "forbidden", violations, "unknown", unknown));
    }
}
