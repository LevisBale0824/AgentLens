package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.RunStatus;

import java.util.Map;

public final class TaskCompletionEvaluator implements Evaluator {
    @Override
    public String name() { return "task-completion"; }

    @Override
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        boolean passed = RunStatus.SUCCESS == run.status()
                && run.finalAnswer() != null && !run.finalAnswer().isBlank();
        return new EvaluationResult(name(), passed, passed ? 1.0 : 0.0,
                passed ? "Agent completed the task" : "Agent did not produce a successful final result",
                Map.of("status", run.status().name(), "eventCount", run.events().size()),
                EvaluationResult.excerpt(run.finalAnswer(), 160),
                "status=" + run.status() + ", finalAnswer="
                        + (run.finalAnswer() == null ? "absent" : "present ("
                        + run.finalAnswer().strip().length() + " chars)"));
    }
}
