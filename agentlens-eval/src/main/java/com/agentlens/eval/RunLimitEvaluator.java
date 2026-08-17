package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.util.Map;

public final class RunLimitEvaluator implements Evaluator {
    @Override public String name() { return "run-limits"; }

    @Override
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        long steps = run.events().size();
        boolean passed = steps <= task.maxSteps();
        return new EvaluationResult(name(), passed, passed ? 1.0 : 0.0,
                passed ? "Run stayed within step limit" : "Run exceeded step limit",
                Map.of("steps", steps, "maxSteps", task.maxSteps(), "status", run.status().name()));
    }
}
