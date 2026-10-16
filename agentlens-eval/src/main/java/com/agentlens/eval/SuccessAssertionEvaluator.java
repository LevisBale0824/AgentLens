package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P1 断言代数 evaluator: executes the scenario's {@code success_assertions}
 * (see {@link SuccessAssertions} for the DSL) against the final answer.
 *
 * <p>Scenarios without assertions short-circuit to a passing "not declared"
 * result so the evaluator can sit in the default registry everywhere without
 * changing existing corpora. An invalid spec FAILS the evaluation instead of
 * being ignored - a broken corpus must fail loudly, not pass vacuously.
 */
public final class SuccessAssertionEvaluator implements Evaluator {

    /** Task-context key carrying the raw spec strings (set by Scenario.toTask). */
    public static final String CONTEXT_KEY = "successAssertions";

    @Override
    public String name() {
        return "success-assertions";
    }

    @Override
    @SuppressWarnings("unchecked")
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        Object raw = task.context().get(CONTEXT_KEY);
        if (!(raw instanceof List<?> specs) || specs.isEmpty()) {
            return new EvaluationResult(name(), true, 1.0,
                    "no success assertions declared (optional evaluator)",
                    Map.of("declared", 0));
        }
        List<SuccessAssertions.Assertion> assertions;
        try {
            assertions = SuccessAssertions.parse((List<String>) specs);
        } catch (IllegalArgumentException e) {
            return new EvaluationResult(name(), false, 0.0, e.getMessage(),
                    Map.of("declared", specs.size()));
        }
        SuccessAssertions.Result result = SuccessAssertions.evaluate(assertions, run.finalAnswer());
        List<Map<String, Object>> outcomeEvidence = new ArrayList<>();
        result.outcomes().forEach(o -> outcomeEvidence.add(Map.of(
                "spec", o.spec(), "satisfied", o.satisfied())));
        String firstFailure = result.outcomes().stream()
                .filter(o -> !o.satisfied())
                .map(o -> (o.negated() ? "must not contain: " : "missing: ") + o.spec())
                .findFirst()
                .orElse(null);
        String summary = result.passed()
                ? "all " + assertions.size() + " success assertions satisfied"
                : firstFailure;
        String explanation = result.outcomes().stream()
                .map(o -> (o.satisfied() ? "PASS " : "FAIL ") + o.spec())
                .reduce((a, b) -> a + "; " + b)
                .orElse("no assertions");
        return new EvaluationResult(name(), result.passed(), result.score(), summary,
                Map.of("declared", assertions.size(),
                        "weightedScore", result.score(),
                        "outcomes", outcomeEvidence),
                EvaluationResult.excerpt(run.finalAnswer(), 160), explanation);
    }
}
