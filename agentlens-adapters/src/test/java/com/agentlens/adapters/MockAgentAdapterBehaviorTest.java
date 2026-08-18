package com.agentlens.adapters;

import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MockAgentAdapterBehaviorTest {

    private AgentTask task(String behavior) {
        return new AgentTask("t-" + behavior, "diagnose alert", Map.of(
                "agentBehavior", behavior,
                "allowedTools", List.of("query_prometheus", "get_pod_logs"),
                "forbiddenTools", List.of("delete_database")), 3);
    }

    @Test
    void correctBehaviorQueriesAllowedToolsAndSucceeds() {
        AgentRun run = new MockAgentAdapter().run(task("correct"));
        assertEquals(RunStatus.SUCCESS, run.status());
        var tools = run.events().stream()
                .filter(e -> e.type() == EventType.TOOL_CALL)
                .map(e -> e.name()).toList();
        assertEquals(List.of("query_prometheus", "get_pod_logs"), tools);
        assertTrue(run.finalAnswer() != null && run.finalAnswer().contains("evidence"));
    }

    @Test
    void wrongToolBehaviorCallsOutOfPolicyTool() {
        AgentRun run = new MockAgentAdapter().run(task("wrong_tool"));
        var tools = run.events().stream()
                .filter(e -> e.type() == EventType.TOOL_CALL)
                .map(e -> e.name()).toList();
        assertEquals(List.of("restart_service"), tools);
        // restart_service is neither allowed nor forbidden: unknown-tool policy violation
        assertFalse(tools.contains("query_prometheus"));
    }

    @Test
    void forbiddenActionBehaviorCallsForbiddenTool() {
        AgentRun run = new MockAgentAdapter().run(task("forbidden_action"));
        var tools = run.events().stream()
                .filter(e -> e.type() == EventType.TOOL_CALL)
                .map(e -> e.name()).toList();
        assertEquals(List.of("delete_database"), tools);
    }

    @Test
    void loopBehaviorExceedsStepLimit() {
        AgentRun run = new MockAgentAdapter().run(task("loop"));
        // maxSteps=3, loop emits maxSteps+1 call/result pairs = 8 events + final answer = 9 > 3
        assertTrue(run.events().size() > 3);
        long repeated = run.repeatedToolCallCount();
        assertTrue(repeated >= 3, "expected repeated calls, got " + repeated);
    }

    @Test
    void flakyBehaviorProducesBothOutcomesAcrossRuns() {
        MockAgentAdapter adapter = new MockAgentAdapter();
        boolean sawSuccess = false, sawFailure = false;
        for (int i = 0; i < 200 && !(sawSuccess && sawFailure); i++) {
            AgentRun run = adapter.run(task("flaky"));
            if (run.status() == RunStatus.SUCCESS) sawSuccess = true;
            if (run.status() == RunStatus.FAILED) sawFailure = true;
        }
        assertTrue(sawSuccess, "flaky agent should succeed sometimes");
        assertTrue(sawFailure, "flaky agent should fail sometimes");
    }

    @Test
    void missingBehaviorDefaultsToCorrect() {
        AgentTask plain = new AgentTask("t-plain", "diagnose", Map.of(), 5);
        AgentRun run = new MockAgentAdapter().run(plain);
        assertEquals(RunStatus.SUCCESS, run.status());
    }
}
