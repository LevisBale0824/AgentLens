package com.agentlens.core;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>execution   - same tool called repeatedly without progress (loop proxy)</li>
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
    private final Map<String, Integer> consecutiveByName = new LinkedHashMap<>();
    private final Deque<String> recentTools = new ArrayDeque<>();

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

    /** Feeds one streaming event; emits verdicts via the sink as they fire. */
    public void onEvent(int eventIndex, AgentEvent event) {
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
            recentTools.addLast(tool);
            consecutiveByName.merge(tool, 1, Integer::sum);
            if (recentTools.size() > repeatThreshold) {
                String evicted = recentTools.pollFirst();
                consecutiveByName.merge(evicted, -1, Integer::sum);
                if (consecutiveByName.get(evicted) == 0) consecutiveByName.remove(evicted);
            }
            if (recentTools.size() == repeatThreshold
                    && consecutiveByName.size() == 1 && consecutiveByName.containsKey(tool)) {
                emit(new Verdict("execution", "repeated-calls",
                        "tool '" + tool + "' called " + repeatThreshold + " times consecutively",
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
