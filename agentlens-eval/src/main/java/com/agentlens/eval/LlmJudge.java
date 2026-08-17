package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

public interface LlmJudge {
    EvaluationResult judge(AgentTask task, AgentRun run, String criterion) throws Exception;
}
