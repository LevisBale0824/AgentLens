package com.agentlens.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Incremental online evaluator for streaming traces (live DSH sessions).
 *
 * <p>Unlike the batch evaluators (which see a finished {@code AgentRun}), this
 * consumes events one at a time and flags violations the moment they become
 * detectable:
 *
 * <ul>
 *   <li>safety      - a call to a forbidden tool (zero-tolerance, hard)</li>
 *   <li>execution   - step limit exceeded (max tool calls)</li>
 *   <li>execution   - repeated identical calls without progress; callId-aware,
 *       so parallel duplicates of an unanswered call are not misread as a loop</li>
 * </ul>
 *
 * <p>Deliberately excludes task-completion judging: the final answer does not
 * exist until the session ends, so completion stays a batch concern. Each
 * verdict carries the triggering event index for evidence chaining.
 */
public final class LiveEvaluator {
    /** An online verdict: which rule fired, when, and how bad it is. */
    public record Verdict(String layer, String rule, String message, int eventIndex,
                          boolean hardViolation) {}

    private final Set<String> forbiddenTools;
    private final int maxToolCalls;
    private final int repeatThreshold;
    private final List<Verdict> verdicts = new ArrayList<>();
    private final Consumer<Verdict> sink;

    private int toolCalls;
    private final ToolRepeatDetector repeats = new ToolRepeatDetector();
    private String streakSignature;
    private int streak;

    /** @param repeatThreshold consecutive identical tool calls flagged as a loop (0 disables) */
    public LiveEvaluator(Set<String> forbiddenTools, int maxToolCalls, int repeatThreshold) {
        this(forbiddenTools, maxToolCalls, repeatThreshold, v -> {});
    }

    public LiveEvaluator(Set<String> forbiddenTools, int maxToolCalls, int repeatThreshold,
                         Consumer<Verdict> sink) {
        this.forbiddenTools = Set.copyOf(forbiddenTools);
        this.maxToolCalls = maxToolCalls;
        this.repeatThreshold = repeatThreshold;
        this.sink = sink;
    }

    /**
     * Feeds one streaming event; emits verdicts via the sink as they fire.
     *
     * <p>Repeat rule (callId-aware): only RETRY (previous identical call
     * errored) and NO_PROGRESS (previous identical call completed) extend the
     * streak; a parallel duplicate of a still-unanswered call never counts,
     * so parallel bursts do not trigger the loop verdict.
     */
    public void onEvent(int eventIndex, AgentEvent event) {
        if (event.type() == EventType.TOOL_RESULT) {
            repeats.onEvent(eventIndex, event); // attribute outcome to its call
            return;
        }
        if (event.type() != EventType.TOOL_CALL || event.name() == null) return;
        String tool = event.name();

        if (forbiddenTools.contains(tool)) {
            emit(new Verdict("safety", "forbidden-tool",
                    "called forbidden tool '" + tool + "'", eventIndex, true));
        }

        toolCalls++;
        if (maxToolCalls > 0 && toolCalls > maxToolCalls) {
            emit(new Verdict("execution", "step-limit",
                    "tool call budget exhausted: " + toolCalls + " > " + maxToolCalls,
                    eventIndex, false));
        }

        if (repeatThreshold > 0) {
            repeats.onEvent(eventIndex, event);
            ToolRepeatDetector.Attempt attempt =
                    repeats.attempts().get(repeats.attempts().size() - 1);
            if (attempt.kind() == ToolRepeatDetector.Kind.PARALLEL) {
                // duplicate of an in-flight call: no loop evidence
            } else if (attempt.signature().equals(streakSignature)) {
                streak++;
            } else {
                streakSignature = attempt.signature();
                streak = 1;
            }
            if (streak == repeatThreshold) {
                String why = attempt.kind() == ToolRepeatDetector.Kind.RETRY
                        ? "re-called after error"
                        : "identical arguments, no progress";
                emit(new Verdict("execution", "repeated-calls",
                        "tool '" + tool + "' called " + streak + " times consecutively (" + why + ")",
                        eventIndex, false));
            }
        }
    }

    /** True once any hard (zero-tolerance) violation has fired. */
    public boolean hardViolationSeen() {
        return verdicts.stream().anyMatch(Verdict::hardViolation);
    }

    public List<Verdict> verdicts() { return List.copyOf(verdicts); }

    private void emit(Verdict verdict) {
        verdicts.add(verdict);
        sink.accept(verdict);
    }
}
