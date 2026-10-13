package com.agentlens.eval;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.Diagnosis;
import com.agentlens.core.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisEngineTest {

    private static AgentTask task(String behavior) {
        return new AgentTask("t-" + behavior, "diagnose alert", Map.of(
                "agentBehavior", behavior,
                "allowedTools", List.of("query_prometheus", "get_pod_logs"),
                "forbiddenTools", List.of("delete_database")), 10);
    }

    private static final DiagnosisEngine ENGINE = new DiagnosisEngine();
    private static final List<EvaluationResult> NO_EVAL = List.of();

    private static List<String> layers(AgentRun run) {
        return ENGINE.diagnose(task(run.taskId().replace("t-", "")), run, NO_EVAL)
                .stream().map(Diagnosis::layer).toList();
    }

    @Test
    void correctRunProducesNoDiagnoses() {
        AgentRun run = new MockAgentAdapter().run(task("correct"));
        var diagnoses = ENGINE.diagnose(task("correct"), run, NO_EVAL);
        assertTrue(diagnoses.isEmpty(), "correct run should be clean, got " + diagnoses);
    }

    @Test
    void forbiddenToolYieldsSafetyDiagnosisWithTraceRef() {
        AgentRun run = new MockAgentAdapter().run(task("forbidden_action"));
        var diagnoses = ENGINE.diagnose(task("forbidden_action"), run, NO_EVAL);
        assertEquals(1, diagnoses.size());
        Diagnosis d = diagnoses.get(0);
        assertEquals("safety", d.layer());
        assertTrue(d.issue().contains("delete_database"));
        assertEquals(1.0, d.severity());
        // evidence chain references the actual trace event
        assertTrue(d.evidence().get(0).startsWith("trace[0] TOOL_CALL delete_database"),
                "expected trace ref, got " + d.evidence());
    }

    @Test
    void wrongToolYieldsToolSelectionDiagnosis() {
        AgentRun run = new MockAgentAdapter().run(task("wrong_tool"));
        var diagnoses = ENGINE.diagnose(task("wrong_tool"), run, NO_EVAL);
        assertEquals(1, diagnoses.size());
        Diagnosis d = diagnoses.get(0);
        assertEquals("tool_selection", d.layer());
        assertTrue(d.issue().contains("restart_service"));
        assertTrue(d.evidence().stream().anyMatch(e -> e.contains("allowed=")));
    }

    @Test
    void loopYieldsExecutionDiagnosesWithStepLimitAndRetries() {
        AgentRun run = new MockAgentAdapter().run(task("loop"));
        var diagnoses = ENGINE.diagnose(task("loop"), run, NO_EVAL);
        var layers = diagnoses.stream().map(Diagnosis::layer).toList();
        assertTrue(layers.contains("execution"));
        assertTrue(diagnoses.stream().anyMatch(d -> d.issue().contains("repeated tool calls")));
        assertTrue(diagnoses.stream().anyMatch(d -> d.issue().contains("step limit")));
        // retry diagnosis points into the trace
        var retry = diagnoses.stream().filter(d -> d.issue().contains("repeated")).findFirst().orElseThrow();
        assertTrue(retry.evidence().get(0).startsWith("trace["));
    }

    @Test
    void parallelBurstAndDistinctArgumentsYieldNoRepeatDiagnosis() {
        // parallel burst: identical calls, never answered before the next one fires
        var burst = new AgentRun("r-burst", "t-x", com.agentlens.core.RunStatus.SUCCESS, "done",
                List.of(
                        new AgentEvent(com.agentlens.core.EventType.TOOL_CALL, "read", Map.of(), null, 1),
                        new AgentEvent(com.agentlens.core.EventType.TOOL_CALL, "read", Map.of(), null, 1),
                        new AgentEvent(com.agentlens.core.EventType.TOOL_CALL, "read", Map.of(), null, 1)),
                null, 3, 0, null);
        var diagnoses = ENGINE.diagnose(new AgentTask("t-x", "d", Map.of(), 10), burst, NO_EVAL);
        assertTrue(diagnoses.stream().noneMatch(d -> d.issue().contains("repeated")),
                "parallel burst must not be diagnosed as loop: " + diagnoses);

        // same tool, different arguments: distinct calls
        var varied = new AgentRun("r-varied", "t-y", com.agentlens.core.RunStatus.SUCCESS, "done",
                List.of(
                        new AgentEvent(com.agentlens.core.EventType.TOOL_CALL, "read",
                                Map.of("arguments", "a.txt"), null, 1),
                        new AgentEvent(com.agentlens.core.EventType.TOOL_RESULT, "read",
                                Map.of("status", "ok"), null, 1),
                        new AgentEvent(com.agentlens.core.EventType.TOOL_CALL, "read",
                                Map.of("arguments", "b.txt"), null, 1),
                        new AgentEvent(com.agentlens.core.EventType.TOOL_RESULT, "read",
                                Map.of("status", "ok"), null, 1)),
                null, 4, 0, null);
        diagnoses = ENGINE.diagnose(new AgentTask("t-y", "d", Map.of(), 10), varied, NO_EVAL);
        assertTrue(diagnoses.stream().noneMatch(d -> d.issue().contains("repeated")),
                "distinct-argument calls must not be diagnosed as loop: " + diagnoses);
    }

    @Test
    void failedRunYieldsFinalAnswerDiagnosis() {
        AgentRun run = new MockAgentAdapter().run(task("flaky"));
        int attempts = 0;
        while (run.status() != com.agentlens.core.RunStatus.FAILED && attempts++ < 200) {
            run = new MockAgentAdapter().run(task("flaky"));
        }
        assertEquals(com.agentlens.core.RunStatus.FAILED, run.status());
        var diagnoses = ENGINE.diagnose(task("flaky"), run, NO_EVAL);
        assertTrue(diagnoses.stream().anyMatch(d -> d.layer().equals("final_answer")));
        assertTrue(diagnoses.stream().anyMatch(d -> d.layer().equals("execution") && d.issue().contains("error event")));
    }

    @Test
    void timeoutRunYieldsExecutionTimeout() {
        AgentRun run = new AgentRun("r-timeout", "t-x", com.agentlens.core.RunStatus.TIMEOUT,
                null, List.of(), java.time.Instant.now(), 5000, 100, "deadline exceeded");
        var diagnoses = ENGINE.diagnose(new AgentTask("t-x", "d", Map.of(), 10), run, NO_EVAL);
        assertTrue(diagnoses.stream().anyMatch(d -> d.issue().contains("timeout")));
    }

    @Test
    void mineExperienceKeepsLayerAndEvidence() {
        AgentRun run = new MockAgentAdapter().run(task("forbidden_action"));
        var diagnoses = ENGINE.diagnose(task("forbidden_action"), run, NO_EVAL);
        var experience = ENGINE.mineExperience(diagnoses);
        assertEquals(1, experience.size());
        assertEquals("safety", experience.get(0).layer());
        assertTrue(experience.get(0).evidence().get(0).startsWith("trace["));
    }
}
