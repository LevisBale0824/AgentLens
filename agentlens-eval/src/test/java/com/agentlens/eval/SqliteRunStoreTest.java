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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Per-side compare audit fields (sideOverrides / observedModel) must survive
     * the store: the gate decision is audited against exactly these values.
     */
    @Test
    void evaluationMetadataRoundtripRestoresAuditFields() throws Exception {
        var evaluation = new EvaluationRun("eval-meta-1", "task-1", "dsh", "candidate",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new EvaluationResult("task-completion", true, 1.0, "ok", Map.of())),
                Map.of("durationMs", 20090L, "totalTokens", 0L,
                        "observedModel", "qwen2.5:3b",
                        "sideOverrides", Map.of("dshArgs", "--model qwen2.5:3b")));

        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveEvaluation(evaluation);
            var restored = store.loadEvaluation("eval-meta-1");
            assertTrue(restored.isPresent());
            assertEquals("qwen2.5:3b", restored.get().metadata().get("observedModel"));
            // JSON roundtrip may narrow integral numbers: compare numerically.
            assertEquals(20090L,
                    ((Number) restored.get().metadata().get("durationMs")).longValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> sideOverrides =
                    (Map<String, Object>) restored.get().metadata().get("sideOverrides");
            assertEquals("--model qwen2.5:3b", sideOverrides.get("dshArgs"));
        }
    }

    /** Evaluations saved without metadata restore to an empty metadata map. */
    @Test
    void evaluationWithoutMetadataLoadsEmptyMetadata() throws Exception {
        var evaluation = new EvaluationRun("eval-nometa", "task-1", "mock", "v1",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new EvaluationResult("task-completion", true, 1.0, "ok", Map.of())),
                Map.of());
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveEvaluation(evaluation);
            var restored = store.loadEvaluation("eval-nometa");
            assertTrue(restored.isPresent());
            assertTrue(restored.get().metadata().isEmpty());
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

    @Test
    void importLogRoundtripKeepsOutcomeAndOrdersNewestFirst() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("import-log.db"))) {
            assertTrue(store.importHistory(10).isEmpty());
            store.recordImport("a.ndjson", "OTLP_NDJSON", "ui-import", 2, 0, null);
            store.recordImport("b.jsonl", "DSH_SESSION", "cli-import", 0, 1, "run already exists: x");
            var history = store.importHistory(10);
            assertEquals(2, history.size());
            assertEquals("b.jsonl", history.get(0).get("file"));
            assertEquals("DSH_SESSION", history.get(0).get("kind"));
            assertEquals("cli-import", history.get(0).get("source"));
            assertEquals(0, history.get(0).get("imported"));
            assertEquals(1, history.get(0).get("skipped"));
            assertEquals("run already exists: x", history.get(0).get("error"));
            assertEquals("a.ndjson", history.get(1).get("file"));
            assertFalse(history.get(1).containsKey("error") && history.get(1).get("error") != null);
            assertEquals(1, store.importHistory(1).size());
        }
    }

    @Test
    void annotationUpsertReplacesAndQueueOrdersNewestFirst() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("annotations.db"))) {
            assertTrue(store.annotationHistory().isEmpty());
            assertTrue(store.loadAnnotation("run-1").isEmpty());
            store.saveAnnotation(new SqliteRunStore.Annotation("run-1", true, "looks right", "alice", null));
            store.saveAnnotation(new SqliteRunStore.Annotation("run-2", false, "missed root cause", "bob", null));
            // re-annotating the same run replaces the verdict
            store.saveAnnotation(new SqliteRunStore.Annotation("run-1", false, "actually wrong", "alice", null));
            var ann = store.loadAnnotation("run-1").orElseThrow();
            assertFalse(ann.passed());
            assertEquals("actually wrong", ann.note());
            assertEquals("alice", ann.annotatedBy());
            var queue = store.annotationHistory();
            assertEquals(2, queue.size());
            assertEquals("run-1", queue.get(0).runId());
            assertFalse(queue.get(0).passed());
        }
    }
}
