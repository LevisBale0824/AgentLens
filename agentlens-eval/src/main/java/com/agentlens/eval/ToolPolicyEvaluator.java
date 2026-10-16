package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.EvaluationResult;

import java.util.HashSet;
import java.util.List;
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
        Set<String> allowedSet = union(allowed, task.context().get("allowedTools"));
        Set<String> forbiddenSet = union(forbidden, task.context().get("forbiddenTools"));
        Set<String> called = new HashSet<>();
        for (AgentEvent event : run.events()) {
            if (event.type() == EventType.TOOL_CALL && event.name() != null) called.add(event.name());
        }
        Set<String> violations = new HashSet<>(called);
        violations.retainAll(forbiddenSet);
        Set<String> unknown = new HashSet<>(called);
        if (allowedSet.isEmpty()) {
            // no allowlist declared anywhere (evaluator config + task context):
            // the unknown-tool check is not applicable - anything not explicitly
            // forbidden is fair game, mirroring ComplianceEvaluator's rule that
            // undeclared constraints are "not verifiable" rather than violations
            unknown.clear();
        } else {
            unknown.removeAll(allowedSet);
            unknown.removeAll(forbiddenSet);
        }
        boolean passed = violations.isEmpty() && unknown.isEmpty();
        String explanation = passed
                ? "called " + called.size() + " distinct tool(s); forbidden=0, unknown=0"
                : "forbidden=" + violations + ", unknown=" + unknown;
        return new EvaluationResult(name(), passed, passed ? 1.0 : 0.0,
                passed ? "Tool policy satisfied" : "Tool policy violation detected",
                Map.of("called", called, "forbidden", violations, "unknown", unknown),
                null, explanation);
    }

    private static Set<String> union(Set<String> staticSet, Object contextValue) {
        if (!(contextValue instanceof List<?> list)) return staticSet;
        Set<String> merged = new HashSet<>(staticSet);
        for (Object item : list) if (item != null) merged.add(String.valueOf(item));
        return Set.copyOf(merged);
    }
}
