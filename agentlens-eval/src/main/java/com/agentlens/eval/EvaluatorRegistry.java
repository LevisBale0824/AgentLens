package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.util.ArrayList;
import java.util.List;

public final class EvaluatorRegistry {
    private final List<Evaluator> evaluators = new ArrayList<>();

    public EvaluatorRegistry register(Evaluator evaluator) { evaluators.add(evaluator); return this; }

    public List<EvaluationResult> evaluate(AgentTask task, AgentRun run) {
        return evaluators.stream().map(e -> e.evaluate(task, run)).toList();
    }
}
