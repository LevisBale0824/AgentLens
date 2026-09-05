package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry-facing LLM judge (ROADMAP P2-1). Runs a set of quality criteria
 * (presets from ROADMAP 5.3: explanation quality, evidence sufficiency,
 * recommendation quality, plan sensibility) through a pluggable
 * {@link LlmJudge} backend and merges the verdicts into one
 * {@link EvaluationResult} so the existing pipeline (run persistence,
 * pass^k, report) picks it up unchanged.
 *
 * <p><b>Fail-open contract</b>: deterministic evaluators and safety gates stay
 * authoritative; the judge is a quality signal. When the judge backend is
 * unreachable or repeatedly malformed, the criterion is recorded as degraded
 * in reason + metadata instead of failing the run - infrastructure problems
 * must not block release gates. When every criterion degrades, the result is
 * {@code passed=true, score=0} with {@code judge.status=unavailable}.
 */
public final class LlmJudgeEvaluator implements Evaluator {
    /** Criterion presets with their rubrics (ROADMAP 5.3 dimensions), in stable order. */
    public static final Map<String, String> CRITERIA;
    static {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("explanation_quality",
                "explanation_quality: the final answer explains what happened clearly and correctly, "
                        + "consistent with the tool results observed in the trace.");
        criteria.put("evidence_sufficiency",
                "evidence_sufficiency: the agent gathered enough concrete evidence (tool calls and "
                        + "results in the trace) before concluding, and the answer cites it.");
        criteria.put("recommendation_quality",
                "recommendation_quality: the final answer proposes a concrete, safe, actionable next "
                        + "step appropriate to the task.");
        criteria.put("plan_sensibility",
                "plan_sensibility: the sequence of tool calls is an efficient, sensible plan - no "
                        + "redundant detours, no loops, no ignored tool failures.");
        CRITERIA = java.util.Collections.unmodifiableMap(criteria);
    }

    public static final String EVALUATOR_NAME = "llm-judge";

    private final LlmJudge judge;
    private final List<String> criteria;

    /** Judges the given preset criteria (see {@link #CRITERIA}). */
    public LlmJudgeEvaluator(LlmJudge judge, List<String> criteria) {
        this.judge = judge;
        for (String criterion : criteria) {
            if (!CRITERIA.containsKey(criterion)) {
                throw new IllegalArgumentException("unknown judge criterion: " + criterion
                        + " (available: " + CRITERIA.keySet() + ")");
            }
        }
        this.criteria = List.copyOf(criteria);
    }

    /** Judges all four ROADMAP 5.3 preset criteria. */
    public LlmJudgeEvaluator(LlmJudge judge) {
        this(judge, List.copyOf(CRITERIA.keySet()));
    }

    @Override public String name() { return EVALUATOR_NAME; }

    @Override
    public EvaluationResult evaluate(AgentTask task, AgentRun run) {
        record Outcome(String name, EvaluationResult result) {}
        List<Outcome> judged = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        for (String criterion : criteria) {
            try {
                judged.add(new Outcome(criterion, judge.judge(task, run, CRITERIA.get(criterion))));
            } catch (Exception e) {
                degraded.add(criterion + ": " + rootMessage(e));
            }
        }

        if (judged.isEmpty()) {
            return new EvaluationResult(EVALUATOR_NAME, true, 0.0,
                    "judge unavailable (fail-open; not gating): " + String.join("; ", degraded),
                    Map.of("judge.status", "unavailable", "judge.degraded", List.copyOf(degraded)));
        }

        double score = judged.stream().mapToDouble(o -> o.result().score()).average().orElse(0.0);
        boolean passed = judged.stream().allMatch(o -> o.result().passed());
        List<String> reasons = judged.stream()
                .map(o -> o.name() + "=" + String.format("%.2f", o.result().score())
                        + (o.result().passed() ? "(pass)" : "(FAIL)") + " " + o.result().reason())
                .toList();
        List<String> finalReasons = degraded.isEmpty() ? reasons
                : List.copyOf(java.util.stream.Stream.concat(reasons.stream(),
                        degraded.stream().map(d -> "degraded: " + d)).toList());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("judge.status", degraded.isEmpty() ? "ok" : "partial");
        judged.forEach(o -> metadata.put(o.name(), o.result().score()));
        if (!degraded.isEmpty()) metadata.put("judge.degraded", List.copyOf(degraded));
        return new EvaluationResult(EVALUATOR_NAME, passed, score, String.join(" | ", finalReasons), metadata);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        String top = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String rootMsg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return top.equals(rootMsg) ? top : top + " / " + rootMsg;
    }
}
