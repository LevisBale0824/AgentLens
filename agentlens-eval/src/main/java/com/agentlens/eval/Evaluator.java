package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

public interface Evaluator {
    String name();

    EvaluationResult evaluate(AgentTask task, AgentRun run);
}
