package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepEval-style quality metrics (board T7, phase 1) implemented on the same
 * pluggable {@link LlmJudge} backend as {@link LlmJudgeEvaluator} - so they
 * work against any OpenAI-compatible endpoint or local Ollama with zero Python
 * dependency. A native DeepEval subprocess integration remains a possible
 * phase 2 (requires a Python environment).
 *
 * <p>Three canonical DeepEval metrics mapped onto the trace domain:
 * <ul>
 *   <li><b>faithfulness</b> - every claim in the final answer must be grounded
 *       in the tool results observed in the trace (DeepEval: output vs
 *       retrieval context).</li>
 *   <li><b>answer_relevancy</b> - the answer addresses what the task actually
 *       asked for.</li>
 *   <li><b>hallucination</b> - no statement contradicts the tool results.</li>
 * </ul>
 *
 * <p><b>Fail-open contract</b> (same as {@link LlmJudgeEvaluator}): judge
 * infrastructure problems never fail the run. Metrics that degrade are
 * excluded from the verdict and recorded in reason/evidence; only when every
 * selected metric degrades is the result {@code passed=true, score=0}.
 */
public final class DeepEvalAdapter implements Evaluator {

    public static final String EVALUATOR_NAME = "deepeval";

    /** Metric rubrics in DeepEval's spirit, in stable order. */
    public static final Map<String, String> METRICS;
    static {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("faithfulness",
                "deepeval.faithfulness: every claim made in the final answer is grounded in and "
                        + "supported by the tool calls and results observed in the trace; no invented facts.");
        metrics.put("answer_relevancy",
                "deepeval.answer_relevancy: the final answer directly addresses what the task "
                        + "instruction asked for, without dodging or answering a different question.");
        metrics.put("hallucination",
                "deepeval.hallucination: the final answer contains no statement that contradicts "
                        + "the tool results observed in the trace.");
        METRICS = java.util.Collections.unmodifiableMap(metrics);
    }

    private final LlmJudge judge;
    private final List<String> metrics;

    /** Runs all three canonical metrics. */
    public DeepEvalAdapter(LlmJudge judge) {
        this(judge, List.copyOf(METRICS.keySet()));
    }

    /** Runs the given subset of {@link #METRICS}. */
    public DeepEvalAdapter(LlmJudge judge, List<String> metrics) {
        this.judge = judge;
        for (String metric : metrics) {
            if (!METRICS.containsKey(metric)) {
                throw new IllegalArgumentException("unknown deepeval metric: " + metric
                        + " (available: " + METRICS.keySet() + ")");
            }
        }
        this.metrics = List.copyOf(metrics);
    }

    @Override public String name() { return EVALUATOR_NAME; }

    @Override
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        List<EvaluationResult> judged = new ArrayList<>();
        List<String> judgedMetrics = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (String metric : metrics) {
            try {
                EvaluationResult r = judge.judge(task, run, METRICS.get(metric));
                judged.add(r);
                judgedMetrics.add(metric);
                reasons.add(String.format("%s=%.2f(%s) %s", metric, r.score(),
                        r.passed() ? "PASS" : "FAIL", oneLine(r.reason())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                degraded.add(metric + "=interrupted");
            } catch (Exception e) {
                // fail-open: infrastructure problems degrade the metric, never the run
                degraded.add(metric + "=unavailable");
            }
        }
        if (judged.isEmpty()) {
            return new EvaluationResult(EVALUATOR_NAME, true, 0.0,
                    "all deepeval metrics degraded (fail-open): " + String.join(", ", degraded),
                    Map.of("judge.status", "unavailable"));
        }
        double mean = judged.stream().mapToDouble(EvaluationResult::score).average().orElse(0.0);
        boolean passed = judged.stream().allMatch(EvaluationResult::passed);
        String reason = String.join(" | ", reasons)
                + (degraded.isEmpty() ? "" : " | degraded: " + String.join(", ", degraded));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("judge.status", degraded.isEmpty() ? "ok" : "partial");
        for (int i = 0; i < judged.size(); i++) {
            evidence.put("metric." + judgedMetrics.get(i) + ".score", judged.get(i).score());
            evidence.put("metric." + judgedMetrics.get(i) + ".passed", judged.get(i).passed());
        }
        return new EvaluationResult(EVALUATOR_NAME, passed, mean, reason, evidence);
    }

    private static String oneLine(String reason) {
        if (reason == null) return "";
        String line = reason.replaceAll("\\s+", " ").trim();
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }
}
