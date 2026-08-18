package com.agentlens.eval;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.EvaluationRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRunStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void runRoundtripRestoresTraceEvents() throws Exception {
        AgentTask task = new AgentTask("roundtrip", "diagnose", Map.of(
                "allowedTools", List.of("query_prometheus", "get_pod_logs")), 10);
        AgentRun original = new MockAgentAdapter().run(task);

        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveRun(original, "mock");
            var restored = store.loadRun(original.runId());
            assertTrue(restored.isPresent());
            AgentRun r = restored.get();
            assertEquals(original.runId(), r.runId());
            assertEquals(original.taskId(), r.taskId());
            assertEquals(original.status(), r.status());
            assertEquals(original.finalAnswer(), r.finalAnswer());
            assertEquals(original.durationMs(), r.durationMs());
            assertEquals(original.totalTokens(), r.totalTokens());
            assertEquals(original.events().size(), r.events().size());
            assertEquals(original.events(), r.events());
        }
    }

    @Test
    void evaluationRoundtripRestoresResults() throws Exception {
        var evaluation = new EvaluationRun("eval-1", "task-1", "mock", "v1",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new EvaluationResult("tool-policy", true, 1.0, "ok",
                                Map.of("called", List.of("query_prometheus"))),
                        new EvaluationResult("run-limits", false, 0.0, "exceeded",
                                Map.of("steps", 12))),
                Map.of());

        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveEvaluation(evaluation);
            var restored = store.loadEvaluation("eval-1");
            assertTrue(restored.isPresent());
            assertEquals("task-1", restored.get().taskId());
            assertEquals(2, restored.get().results().size());
            assertTrue(restored.get().results().get(0).passed());
            assertFalse(restored.get().results().get(1).passed());
        }
    }

    @Test
    void listRunsReturnsSavedIds() throws Exception {
        AgentTask task = new AgentTask("list-test", "diagnose", Map.of(), 5);
        AgentRun run = new MockAgentAdapter().run(task);
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveRun(run, "mock");
            assertTrue(store.listRuns().contains(run.runId()));
        }
    }

    @Test
    void missingRunReturnsEmpty() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            assertTrue(store.loadRun("no-such-run").isEmpty());
            assertTrue(store.loadEvaluation("no-such-run").isEmpty());
        }
    }

    @Test
    void multipleRunsInSameStore() throws Exception {
        var task = new AgentTask("multi", "diagnose", Map.of(
                "allowedTools", List.of("query_prometheus")), 5);
        var run1 = new MockAgentAdapter().run(task);
        var run2 = new MockAgentAdapter().run(task);
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveRun(run1, "mock");
            store.saveRun(run2, "mock");
            assertEquals(2, store.listRuns().size());
            assertEquals(run1.events(), store.loadRun(run1.runId()).get().events());
            assertEquals(run2.events(), store.loadRun(run2.runId()).get().events());
        }
    }
}
