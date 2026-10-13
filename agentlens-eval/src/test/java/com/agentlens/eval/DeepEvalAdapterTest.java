package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepEval-style metrics adapter (board T7 phase 1): metric selection, verdict
 * merging, fail-open degradation and rubric routing - via a stub judge, no LLM.
 */
class DeepEvalAdapterTest {

    private static AgentRun run() {
        return new AgentRun("r-1", "t-1", com.agentlens.core.RunStatus.SUCCESS, "answer",
                List.of(), Instant.now(), 10, 100, null);
    }

    private static AgentTask task() {
        return new AgentTask("t-1", "inspect", Map.of(), 10);
    }

    /** Stub judge: returns canned results by rubric prefix; can throw on demand. */
    private static LlmJudge stubJudge(Map<String, EvaluationResult> byMetric, AtomicInteger calls) {
        return (task, run, criterion) -> {
            if (calls != null) calls.incrementAndGet();
            for (var entry : byMetric.entrySet()) {
                if (criterion.startsWith("deepeval." + entry.getKey())) return entry.getValue();
            }
            throw new IllegalStateException("backend down for " + criterion);
        };
    }

    @Test
    void allMetricsPassMergeIntoPassWithMeanScore() {
        AtomicInteger calls = new AtomicInteger();
        var judge = stubJudge(Map.of(
                "faithfulness", new EvaluationResult("llm-judge", true, 1.0, "grounded", Map.of()),
                "answer_relevancy", new EvaluationResult("llm-judge", true, 0.8, "relevant", Map.of()),
                "hallucination", new EvaluationResult("llm-judge", true, 0.9, "clean", Map.of())), calls);
        var result = new DeepEvalAdapter(judge).evaluate(task(), run());
        assertTrue(result.passed());
        assertEquals(0.9, result.score(), 1e-9); // mean of 1.0 / 0.8 / 0.9
        assertEquals("deepeval", result.evaluator());
        assertEquals("ok", result.evidence().get("judge.status"));
        assertEquals(1.0, ((Number) result.evidence().get("metric.faithfulness.score")).doubleValue());
        assertEquals(3, calls.get()); // one judge call per metric
    }

    @Test
    void oneFailingMetricFailsTheWholeResult() {
        var judge = stubJudge(Map.of(
                "faithfulness", new EvaluationResult("llm-judge", true, 1.0, "grounded", Map.of()),
                "answer_relevancy", new EvaluationResult("llm-judge", false, 0.3, "dodged", Map.of()),
                "hallucination", new EvaluationResult("llm-judge", true, 1.0, "clean", Map.of())), null);
        var result = new DeepEvalAdapter(judge).evaluate(task(), run());
        assertFalse(result.passed());
        assertTrue(result.reason().contains("answer_relevancy=0.30(FAIL)"));
    }

    @Test
    void backendFailureDegradesMetricButNotTheRun() {
        var judge = stubJudge(Map.of(
                "faithfulness", new EvaluationResult("llm-judge", true, 1.0, "grounded", Map.of())),
                null); // answer_relevancy / hallucination rubrics throw
        var result = new DeepEvalAdapter(judge).evaluate(task(), run());
        // fail-open: the surviving metric decides; degraded ones are recorded, not fatal
        assertTrue(result.passed());
        assertEquals("partial", result.evidence().get("judge.status"));
        assertTrue(result.reason().contains("degraded: answer_relevancy=unavailable"));
    }

    @Test
    void allMetricsDegradedYieldsFailOpenPassWithZeroScore() {
        LlmJudge deadJudge = (task, run, criterion) -> { throw new IllegalStateException("down"); };
        var result = new DeepEvalAdapter(deadJudge).evaluate(task(), run());
        assertTrue(result.passed());
        assertEquals(0.0, result.score(), 1e-9);
        assertEquals("unavailable", result.evidence().get("judge.status"));
    }

    @Test
    void metricSubsetRunsOnlyTheSelectedMetrics() {
        AtomicInteger calls = new AtomicInteger();
        var judge = stubJudge(Map.of(
                "faithfulness", new EvaluationResult("llm-judge", true, 1.0, "grounded", Map.of()),
                "hallucination", new EvaluationResult("llm-judge", true, 1.0, "clean", Map.of())), calls);
        var adapter = new DeepEvalAdapter(judge, List.of("faithfulness", "hallucination"));
        var result = adapter.evaluate(task(), run());
        assertEquals(2, calls.get());
        assertTrue(result.evidence().containsKey("metric.faithfulness.score"));
        assertFalse(result.evidence().containsKey("metric.answer_relevancy.score"));
    }

    @Test
    void unknownMetricIsRejectedWithAvailableList() {
        var judge = stubJudge(Map.of(), null);
        var e = assertThrows(IllegalArgumentException.class,
                () -> new DeepEvalAdapter(judge, List.of("made_up")));
        assertTrue(e.getMessage().contains("unknown deepeval metric: made_up"));
        assertTrue(e.getMessage().contains("available"));
    }
}
