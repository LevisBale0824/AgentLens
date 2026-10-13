package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.RunStatus;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROADMAP P2-1: criteria merging, fail-open degradation, preset validation. */
class LlmJudgeEvaluatorTest {
    private static final AgentTask TASK = new AgentTask("t", "diagnose", Map.of(), 10);
    private static final AgentRun RUN = new AgentRun("r", "t", RunStatus.SUCCESS, "done",
            List.of(new AgentEvent(EventType.FINAL_ANSWER, "a", Map.of(), Instant.now(), 0)),
            Instant.now(), 1, 0, null);

    private static EvaluationResult verdict(String criterion, boolean passed, double score) {
        return new EvaluationResult("llm-judge:" + criterion, passed, score, "auto", Map.of());
    }

    @Test
    void mergesCriterionVerdictsIntoOneResult() {
        LlmJudge judge = (task, run, criterion) ->
                verdict(criterion, criterion.startsWith("plan_sensibility"), 0.8);
        var evaluator = new LlmJudgeEvaluator(judge, List.of("explanation_quality", "plan_sensibility"));
        EvaluationResult merged = evaluator.evaluate(TASK, RUN);
        assertEquals("llm-judge", merged.evaluator());
        assertFalse(merged.passed(), "one failing criterion fails the merged result");
        assertEquals(0.8, merged.score());
        assertEquals("ok", merged.evidence().get("judge.status"));
        assertEquals(0.8, (Double) merged.evidence().get("explanation_quality"));
        assertTrue(merged.reason().contains("explanation_quality=0.80(FAIL)"));
        assertTrue(merged.reason().contains("plan_sensibility=0.80(pass)"));
    }

    @Test
    void allPassingCriteriaMergeToPassed() {
        LlmJudge judge = (task, run, criterion) -> verdict(criterion, true, 0.9);
        var evaluator = new LlmJudgeEvaluator(judge);
        EvaluationResult merged = evaluator.evaluate(TASK, RUN);
        assertTrue(merged.passed());
        assertEquals(0.9, merged.score());
        assertEquals(LlmJudgeEvaluator.CRITERIA.size(), merged.evidence().size() - 1);
    }

    @Test
    void failOpenWhenJudgeBackendUnreachable() {
        // port 1 on loopback: connection refused immediately
        var judge = new OpenAiJudge(URI.create("http://127.0.0.1:1"), "nope", null, 0.0,
                java.time.Duration.ofSeconds(2), 1);
        var evaluator = new LlmJudgeEvaluator(judge, List.of("explanation_quality"));
        EvaluationResult merged = evaluator.evaluate(TASK, RUN);
        assertTrue(merged.passed(), "judge infra failure must not fail the run (fail-open)");
        assertEquals(0.0, merged.score());
        assertEquals("unavailable", merged.evidence().get("judge.status"));
        assertTrue(merged.reason().contains("fail-open"));
    }

    @Test
    void partialDegradationIsRecorded() {
        LlmJudge flaky = (task, run, criterion) -> {
            if (criterion.startsWith("plan_sensibility")) {
                throw new IllegalStateException("endpoint exploded");
            }
            return verdict(criterion, true, 0.7);
        };
        var evaluator = new LlmJudgeEvaluator(flaky, List.of("explanation_quality", "plan_sensibility"));
        EvaluationResult merged = evaluator.evaluate(TASK, RUN);
        assertTrue(merged.passed());
        assertEquals("partial", merged.evidence().get("judge.status"));
        assertTrue(merged.reason().contains("degraded: plan_sensibility"));
        assertTrue(((List<?>) merged.evidence().get("judge.degraded")).size() == 1);
    }

    @Test
    void rejectsUnknownCriterion() {
        LlmJudge any = (task, run, criterion) -> verdict(criterion, true, 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> new LlmJudgeEvaluator(any, List.of("vibes")));
    }
}
