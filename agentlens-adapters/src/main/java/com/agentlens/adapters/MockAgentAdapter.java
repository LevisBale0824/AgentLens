package com.agentlens.adapters;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Controllable mock agent whose behavior is driven by the task context key
 * "agentBehavior" (set by Scenario.toTask()). Behaviors produce known failure
 * modes so evaluators, diagnosis and pass^k layering can be verified against
 * expected outcomes:
 *
 * <ul>
 *   <li>correct          - queries allowed tools in order, cites evidence, succeeds</li>
 *   <li>wrong_tool       - calls an out-of-policy diagnostic tool (tool-selection flaw)</li>
 *   <li>forbidden_action - calls a forbidden tool (hard safety violation)</li>
 *   <li>loop             - repeats the same tool call until the step limit (execution flaw)</li>
 *   <li>flaky            - nondeterministic: ~60% success, ~40% failure per run</li>
 * </ul>
 */
public final class MockAgentAdapter implements AgentAdapter {
    static final String BEHAVIOR_KEY = "agentBehavior";
    static final String ALLOWED_KEY = "allowedTools";
    static final String FORBIDDEN_KEY = "forbiddenTools";

    @Override
    public String name() { return "mock"; }

    @Override
    public AgentRun run(AgentTask task) {
        String behavior = String.valueOf(task.context().getOrDefault(BEHAVIOR_KEY, "correct"));
        Instant started = Instant.now();
        return switch (behavior) {
            case "wrong_tool" -> wrongTool(task, started);
            case "forbidden_action" -> forbiddenAction(task, started);
            case "loop" -> loop(task, started);
            case "flaky" -> ThreadLocalRandom.current().nextDouble() < 0.6
                    ? correct(task, started) : giveUp(task, started);
            default -> correct(task, started);
        };
    }

    private AgentRun correct(AgentTask task, Instant started) {
        List<AgentEvent> events = new ArrayList<>();
        long ts = 0;
        for (String tool : allowedTools(task)) {
            events.add(new AgentEvent(EventType.TOOL_CALL, tool, Map.of("phase", "diagnose"),
                    started.plusMillis(ts), 5));
            events.add(new AgentEvent(EventType.TOOL_RESULT, tool, Map.of("status", "ok"),
                    started.plusMillis(ts + 5), 3));
            ts += 10;
        }
        events.add(new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null,
                started.plusMillis(ts), 1));
        return new AgentRun(UUID.randomUUID().toString(), task.id(), RunStatus.SUCCESS,
                "Root cause identified with evidence from " + allowedTools(task).size() + " tools",
                List.copyOf(events), started, ts + 1, 120 + events.size() * 15, null);
    }

    private AgentRun wrongTool(AgentTask task, Instant started) {
        List<AgentEvent> events = new ArrayList<>();
        // Picks an out-of-policy but non-destructive tool: the classic tool-selection flaw.
        events.add(new AgentEvent(EventType.TOOL_CALL, "restart_service",
                Map.of("phase", "diagnose", "note", "should have queried metrics first"),
                started, 5));
        events.add(new AgentEvent(EventType.TOOL_RESULT, "restart_service", Map.of("status", "ok"),
                started.plusMillis(5), 3));
        events.add(new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null,
                started.plusMillis(10), 1));
        return new AgentRun(UUID.randomUUID().toString(), task.id(), RunStatus.SUCCESS,
                "Restarted service without diagnosing", List.copyOf(events), started, 11,
                60, null);
    }

    private AgentRun forbiddenAction(AgentTask task, Instant started) {
        List<String> forbidden = forbiddenTools(task);
        String tool = forbidden.isEmpty() ? "delete_database" : forbidden.get(0);
        List<AgentEvent> events = List.of(
                new AgentEvent(EventType.TOOL_CALL, tool, Map.of("phase", "remediate"),
                        started, 5),
                new AgentEvent(EventType.TOOL_RESULT, tool, Map.of("status", "executed"),
                        started.plusMillis(5), 3),
                new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null,
                        started.plusMillis(10), 1));
        return new AgentRun(UUID.randomUUID().toString(), task.id(), RunStatus.SUCCESS,
                "Executed destructive remediation: " + tool, events, started, 11, 80, null);
    }

    private AgentRun loop(AgentTask task, Instant started) {
        List<AgentEvent> events = new ArrayList<>();
        String tool = firstAllowedOr(task, "query_prometheus");
        long ts = 0;
        // Repeats the identical call past the step limit: maxSteps * 2 events + final answer.
        for (int i = 0; i <= task.maxSteps(); i++) {
            events.add(new AgentEvent(EventType.TOOL_CALL, tool, Map.of("attempt", i),
                    started.plusMillis(ts), 5));
            events.add(new AgentEvent(EventType.TOOL_RESULT, tool, Map.of("status", "ok"),
                    started.plusMillis(ts + 5), 3));
            ts += 10;
        }
        events.add(new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null,
                started.plusMillis(ts), 1));
        return new AgentRun(UUID.randomUUID().toString(), task.id(), RunStatus.SUCCESS,
                "Kept re-querying without progress", List.copyOf(events), started, ts + 1,
                200 + events.size() * 10, null);
    }

    private AgentRun giveUp(AgentTask task, Instant started) {
        List<AgentEvent> events = List.of(
                new AgentEvent(EventType.MODEL_CALL, "mock.model", Map.of("status", "incomplete"),
                        started, 20),
                new AgentEvent(EventType.ERROR, "mock.model", Map.of("reason", "gave_up"),
                        started.plusMillis(20), 1));
        return new AgentRun(UUID.randomUUID().toString(), task.id(), RunStatus.FAILED,
                null, events, started, 21, 40, "agent gave up");
    }

    @SuppressWarnings("unchecked")
    private static List<String> allowedTools(AgentTask task) {
        Object v = task.context().get(ALLOWED_KEY);
        return v instanceof List<?> list ? (List<String>) list : List.of("query_prometheus");
    }

    @SuppressWarnings("unchecked")
    private static List<String> forbiddenTools(AgentTask task) {
        Object v = task.context().get(FORBIDDEN_KEY);
        return v instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static String firstAllowedOr(AgentTask task, String fallback) {
        List<String> allowed = allowedTools(task);
        return allowed.isEmpty() ? fallback : allowed.get(0);
    }
}
