package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.util.Map;

public final class RunLimitEvaluator implements Evaluator {
    @Override public String name() { return "run-limits"; }

    @Override
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        // one step = one agent decision (MODEL_CALL); tool results are part of a step
        long steps = run.events().stream()
                .filter(e -> e.type() == com.agentlens.core.EventType.MODEL_CALL
                        || e.type() == com.agentlens.core.EventType.TOOL_CALL)
                .count();
        boolean passed = steps <= task.maxSteps();
        return new EvaluationResult(name(), passed, passed ? 1.0 : 0.0,
                passed ? "Run stayed within step limit" : "Run exceeded step limit",
                Map.of("steps", steps, "maxSteps", task.maxSteps(), "status", run.status().name()),
                null,
                "used " + steps + " of " + task.maxSteps() + " allowed steps (status="
                        + run.status() + ")");
    }
}
