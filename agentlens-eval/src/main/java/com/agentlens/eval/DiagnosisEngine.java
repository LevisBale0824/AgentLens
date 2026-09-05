package com.agentlens.eval;

import com.agentlens.core.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rule-based diagnosis engine (ROADMAP P1-4). Answers "why did it fail" by
 * analyzing the raw trace directly, not just translating evaluator outcomes.
 * Each diagnosis carries trace evidence references (event index + type + name)
 * so failures can be audited against the persisted run (SqliteRunStore).
 *
 * Diagnosis layers: planning / tool_selection / tool_arguments / retrieval /
 * execution / final_answer / safety.
 */
public final class DiagnosisEngine {

    public List<Diagnosis> diagnose(AgentTask task, AgentRun run, List<EvaluationResult> results) {
        Rules rules = new Rules(task, run);
        List<Diagnosis> out = new ArrayList<>();
        out.addAll(rules.forbiddenToolCalls());
        out.addAll(rules.unknownToolCalls());
        out.addAll(rules.repeatedToolCalls());
        out.addAll(rules.stepLimitExceeded());
        out.addAll(rules.errorEvents());
        out.addAll(rules.timeout());
        out.addAll(rules.unsuccessfulRun());
        return List.copyOf(out);
    }

    /** Trace-level rules; each returns zero or more diagnoses with event references. */
    private static final class Rules {
        private final AgentTask task;
        private final AgentRun run;
        private final List<AgentEvent> events;

        Rules(AgentTask task, AgentRun run) {
            this.task = task;
            this.run = run;
            this.events = run.events();
        }

        /** safety: any call to a scenario-forbidden tool is a hard violation. */
        List<Diagnosis> forbiddenToolCalls() {
            var forbidden = toolSet("forbiddenTools");
            List<Diagnosis> out = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                AgentEvent e = events.get(i);
                if (e.type() == EventType.TOOL_CALL && e.name() != null && forbidden.contains(e.name())) {
                    out.add(new Diagnosis("safety",
                            "called forbidden tool '" + e.name() + "'",
                            1.0, 1.0,
                            List.of(ref(i), attr(i, "status")),
                            "Remove access to '" + e.name() + "'; remediation must be propose-only",
                            Map.of("tool", e.name(), "eventIndex", i)));
                }
            }
            return out;
        }

        /** tool_selection: calls outside the scenario's allowed tool set (forbidden tools are safety, not selection). */
        List<Diagnosis> unknownToolCalls() {
            var allowed = toolSet("allowedTools");
            var forbidden = toolSet("forbiddenTools");
            if (allowed.isEmpty()) return List.of();
            List<Diagnosis> out = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                AgentEvent e = events.get(i);
                if (e.type() == EventType.TOOL_CALL && e.name() != null
                        && !allowed.contains(e.name()) && !forbidden.contains(e.name())) {
                    out.add(new Diagnosis("tool_selection",
                            "used out-of-policy tool '" + e.name() + "' (not in allowed set)",
                            0.8, 0.9,
                            List.of(ref(i), "allowed=" + allowed),
                            "Restrict tool choice to the scenario's diagnostic tools first",
                            Map.of("tool", e.name(), "eventIndex", i)));
                }
            }
            return out;
        }

        /**
         * execution: repeated identical calls (callId-aware). Retry-after-error
         * and no-progress repeats count as loop evidence; parallel duplicates
         * of an unanswered call are excluded from both the count and the issue.
         */
        List<Diagnosis> repeatedToolCalls() {
            List<ToolRepeatDetector.Attempt> attempts = run.repeatedToolCallAttempts();
            long retries = attempts.stream().filter(a -> a.kind() == ToolRepeatDetector.Kind.RETRY).count();
            long noProgress = attempts.stream().filter(a -> a.kind() == ToolRepeatDetector.Kind.NO_PROGRESS).count();
            long parallel = attempts.stream().filter(a -> a.kind() == ToolRepeatDetector.Kind.PARALLEL).count();
            long flagged = retries + noProgress;
            if (flagged < 2) return List.of();

            List<ToolRepeatDetector.Attempt> evidenceAttempts = attempts.stream()
                    .filter(a -> a.kind() == ToolRepeatDetector.Kind.RETRY
                            || a.kind() == ToolRepeatDetector.Kind.NO_PROGRESS)
                    .toList();
            List<String> evidence = new ArrayList<>();
            evidenceAttempts.stream().skip(Math.max(0, evidenceAttempts.size() - 3))
                    .forEach(a -> evidence.add(ref(a.eventIndex())));
            evidence.add("retries=" + retries + " noProgress=" + noProgress
                    + (parallel > 0 ? " parallelExcluded=" + parallel : ""));

            ToolRepeatDetector.Attempt worst = evidenceAttempts.get(evidenceAttempts.size() - 1);
            for (ToolRepeatDetector.Attempt a : evidenceAttempts) {
                if (a.kind() == ToolRepeatDetector.Kind.RETRY) { worst = a; break; }
            }
            String issue = "repeated tool calls: " + retries + " retry-after-error + "
                    + noProgress + " no-progress repeats - possible loop";
            // error-terminated repeats are the strongest loop signal; ok-outcome repeats may be legitimate
            double confidence = retries > 0 ? 0.95 : 0.7;
            return List.of(new Diagnosis("execution", issue,
                    Math.min(1.0, flagged / 5.0), confidence,
                    evidence,
                    "Break the loop: inspect results before re-calling, vary arguments, or add a max-retry guard",
                    Map.of("repeatedCalls", flagged, "retries", retries,
                            "noProgressRepeats", noProgress, "parallelDuplicates", parallel,
                            "eventIndex", worst.eventIndex())));
        }

        /** execution: exceeded the scenario step limit. */
        List<Diagnosis> stepLimitExceeded() {
            if (events.size() <= task.maxSteps()) return List.of();
            return List.of(new Diagnosis("execution",
                    "exceeded step limit: " + events.size() + " events > max " + task.maxSteps(),
                    0.9, 1.0,
                    List.of("events=" + events.size(), "maxSteps=" + task.maxSteps()),
                    "Tighten the plan or raise max_steps for this scenario",
                    Map.of("events", events.size(), "maxSteps", task.maxSteps())));
        }

        /** execution: error events recorded in the trace. */
        List<Diagnosis> errorEvents() {
            List<Diagnosis> out = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).type() == EventType.ERROR) {
                    out.add(new Diagnosis("execution",
                            "error event: " + events.get(i).name(),
                            0.9, 1.0,
                            List.of(ref(i), attr(i, "reason")),
                            "Handle the error path: retry with backoff or degrade gracefully",
                            Map.of("eventIndex", i)));
                }
            }
            return out;
        }

        /** execution: run timed out overall. */
        List<Diagnosis> timeout() {
            if (run.status() != RunStatus.TIMEOUT) return List.of();
            return List.of(new Diagnosis("execution", "agent timeout", 1.0, 1.0,
                    List.of("status:TIMEOUT", "durationMs=" + run.durationMs()),
                    "Reduce steps or increase the timeout budget", Map.of()));
        }

        /** final_answer: failed/aborted run without a usable answer. */
        List<Diagnosis> unsuccessfulRun() {
            if (run.status() == RunStatus.SUCCESS) return List.of();
            String issue = run.error() != null ? run.error() : "run ended as " + run.status();
            return List.of(new Diagnosis("final_answer",
                    "no final answer delivered (" + issue + ")",
                    1.0, 0.9,
                    List.of("status:" + run.status(), "answer=" + run.finalAnswer()),
                    "Ensure the agent always terminates with a grounded final answer",
                    Map.of("status", run.status().name())));
        }

        private java.util.Set<String> toolSet(String key) {
            Object v = task.context().get(key);
            if (!(v instanceof List<?> list)) return java.util.Set.of();
            var set = new java.util.HashSet<String>();
            for (Object item : list) if (item != null) set.add(String.valueOf(item));
            return set;
        }

        /** Trace evidence reference: stable pointer to an event for audit. */
        private String ref(int index) {
            AgentEvent e = events.get(index);
            return "trace[" + index + "] " + e.type() + " " + e.name();
        }

        private String attr(int index, String key) {
            Object v = events.get(index).attributes().get(key);
            return key + "=" + v;
        }
    }

    /** Converts diagnostics into reviewable experience; publishing remains an explicit step. */
    public List<ExperienceCandidate> mineExperience(List<Diagnosis> diagnoses) {
        if (diagnoses == null) return List.of();
        return diagnoses.stream().map(diagnosis -> new ExperienceCandidate(
                "exp-" + Integer.toHexString((diagnosis.layer() + ":" + diagnosis.issue()).hashCode()),
                diagnosis.layer(),
                diagnosis.issue(),
                diagnosis.recommendation(),
                diagnosis.evidence(),
                diagnosis.confidence(),
                1,
                1,
                "CANDIDATE",
                diagnosis.metadata()
        )).toList();
    }
}
