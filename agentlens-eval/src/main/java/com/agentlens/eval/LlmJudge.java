package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

public interface LlmJudge {
    EvaluationResult judge(AgentTask task, AgentRun run, String criterion) throws Exception;

    /**
     * P1-B: free-form review call (gate review, narrative summaries); plain
     * text in, plain text out - no JSON verdict schema. Default throws so
     * stub judges only implement what they support; callers must fail open.
     */
    default String review(String prompt) throws Exception {
        throw new UnsupportedOperationException("this judge does not support free-form review");
    }
}
