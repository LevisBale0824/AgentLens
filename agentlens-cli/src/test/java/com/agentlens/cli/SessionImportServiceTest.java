package com.agentlens.cli;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.agentlens.eval.SqliteRunStore;
import com.agentlens.otel.OtelSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 of the UI history-analysis plan: the shared import service sniffs the
 * historical format (DSH session / OTLP NDJSON / AIOpsLab), maps it onto the
 * unified trace, evaluates with the deterministic evaluators, and persists
 * with an explicit source tag - skipping instead of duplicating on re-import.
 */
class SessionImportServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant BASE = Instant.parse("2026-10-14T08:00:00Z");

    private static final String DSH_SESSION_JSONL = """
            {"type": "session", "id": "session-imp-1", "createdAt": 1000, "cwd": "D:/w", "agentPreset": "standard"}
            {"type": "request/context", "time": 1100, "data": {"provider": "zai", "model": "glm-5.1"}}
            {"type": "user/message", "time": 1088, "data": {"content": [{"type": "text", "text": "fix the bug"}]}}
            {"type": "tool/call", "time": 2000, "data": {"callId": "c1", "name": "read", "arguments": "{}"}}
            {"type": "tool/result", "time": 2100, "data": {"message": {"source": {"callId": "c1"}, "content": [{"type": "tool-result", "content": [{"type": "text", "text": "contents"}]}]}}}
            {"type": "assistant/message", "time": 3000, "data": {"turn": 1, "message": {"content": [{"type": "text", "text": "done: fixed"}]}}}
            """;

    private static final String AIOPSLAB_JSON = """
            {
              "agent": "qwen-agent",
              "session_id": "8d2b1a0e-1111-2222-3333-444455556666",
              "problem_id": "k8s_target_port-misconfig-detection-1",
              "start_time": 1788600000.0,
              "end_time": 1788600030.5,
              "trace": [
                {"role": "assistant", "content": "Inspect the logs first.\\n```\\nget_logs(\\"test-social-network\\", \\"user-service\\")\\n```"},
                {"role": "env", "content": "connectivity error ... 2026-01-01 user-service logs"},
                {"role": "assistant", "content": "Concluding:\\n```\\nsubmit(\\"Yes\\")\\n```"},
                {"role": "env", "content": "Submission received."}
              ],
              "results": {"TTD": 12.34, "steps": 2, "in_tokens": 1200, "out_tokens": 340}
            }
            """;

    /** Builds one OTLP/JSON payload line via the native round-trip exporter. */
    static String otlpLine(String runId, String taskId, boolean failed) throws Exception {
        AgentRun run = failed
                ? new AgentRun(runId, taskId, RunStatus.FAILED, null, List.of(), BASE, 4, 10,
                        "agent gave up")
                : new AgentRun(runId, taskId, RunStatus.SUCCESS, "all good", List.of(
                        new AgentEvent(EventType.TOOL_CALL, "query_prometheus",
                                java.util.Map.of("phase", "diagnose"), BASE, 5),
                        new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null,
                                BASE.plusMillis(8), 1)),
                        BASE, 9, 333, null);
        return JSON.writeValueAsString(OtelSpanMapper.toOtlpJson(run));
    }

    @Test
    void detectRecognizesAllHistoricalFormats() {
        assertEquals(SessionImportService.Kind.DSH_SESSION,
                SessionImportService.detect("session.jsonl.zstd", "anything at all"));
        assertEquals(SessionImportService.Kind.DSH_SESSION,
                SessionImportService.detect("session.jsonl", DSH_SESSION_JSONL));
        assertEquals(SessionImportService.Kind.OTLP_NDJSON,
                SessionImportService.detect("traces.ndjson", otlpLineUnchecked()));
        assertEquals(SessionImportService.Kind.AIOPSLAB,
                SessionImportService.detect("results.json", AIOPSLAB_JSON));
        assertEquals(SessionImportService.Kind.UNKNOWN,
                SessionImportService.detect("notes.txt", "plain prose, no json"));
    }

    @Test
    void dshImportEvaluatesPersistsAndTagsProvenance() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            var outcome = service.importFile("session.jsonl",
                    DSH_SESSION_JSONL.getBytes(StandardCharsets.UTF_8),
                    new SessionImportService.Options("ui-import", null, false));

            assertNull(outcome.error(), outcome.error());
            assertEquals(SessionImportService.Kind.DSH_SESSION, outcome.kind());
            assertEquals(1, outcome.runs().size());
            var imported = outcome.runs().get(0);
            assertEquals("session-imp-1", imported.run().runId());
            assertTrue(imported.run().taskId().startsWith("dsh-"),
                    "derived task id: " + imported.run().taskId());
            assertEquals(RunStatus.SUCCESS, imported.run().status());
            assertTrue(store.loadRun("session-imp-1").isPresent());
            var evaluation = store.loadEvaluation("session-imp-1").orElseThrow();
            assertEquals("ui-import", evaluation.metadata().get("source"));
            assertEquals("dsh-session", evaluation.agentName());
            assertTrue(evaluation.results().stream().anyMatch(r -> "task-completion".equals(r.evaluator())));
            assertTrue(evaluation.results().stream().anyMatch(r -> "run-limits".equals(r.evaluator())));
            var log = store.importHistory(10);
            assertEquals(1, log.size());
            assertEquals("session.jsonl", log.get(0).get("file"));
            assertEquals("DSH_SESSION", log.get(0).get("kind"));
            assertEquals("ui-import", log.get(0).get("source"));
            assertEquals(1, log.get(0).get("imported"));
            assertEquals(0, log.get(0).get("skipped"));
        }
    }

    @Test
    void dshTaskIdOverrideWinsOverDerivedId() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            var outcome = service.importFile("session.jsonl",
                    DSH_SESSION_JSONL.getBytes(StandardCharsets.UTF_8),
                    new SessionImportService.Options("cli-import", "my-task", false));
            assertEquals("my-task", outcome.runs().get(0).run().taskId());
        }
    }

    @Test
    void otlpNdjsonImportsEachTraceAsARun() throws Exception {
        String ndjson = otlpLine("run-imp-a", "task-imp-a", false) + "\n"
                + otlpLine("run-imp-b", "task-imp-b", true) + "\n";
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            var outcome = service.importFile("traces.ndjson", ndjson.getBytes(StandardCharsets.UTF_8),
                    new SessionImportService.Options("ui-import", null, false));

            assertNull(outcome.error(), outcome.error());
            assertEquals(2, outcome.runs().size());
            assertEquals(RunStatus.SUCCESS, outcome.runs().get(0).run().status());
            assertEquals(RunStatus.FAILED, outcome.runs().get(1).run().status());
            var evaluation = store.loadEvaluation("run-imp-a").orElseThrow();
            assertEquals("otlp", evaluation.metadata().get("ingest"));
            assertEquals("ui-import", evaluation.metadata().get("source"));
            assertEquals("ingest", evaluation.agentVersion());
        }
    }

    @Test
    void bomPrefixedPayloadsStillImport() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            String bom = "\uFEFF" + otlpLine("run-bom", "task-bom", false);
            var outcome = service.importFile("t.ndjson", bom.getBytes(StandardCharsets.UTF_8),
                    new SessionImportService.Options("ui-import", null, false));
            assertNull(outcome.error(), String.valueOf(outcome.error()));
            assertEquals(1, outcome.runs().size());
            assertEquals("run-bom", outcome.runs().get(0).run().runId());
        }
    }

    @Test
    void reimportSkipsExistingRunsUnlessOverwriteRequested() throws Exception {
        byte[] bytes = DSH_SESSION_JSONL.getBytes(StandardCharsets.UTF_8);
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            var first = service.importFile("session.jsonl", bytes,
                    new SessionImportService.Options("ui-import", null, false));
            assertEquals(1, first.runs().size());

            var second = service.importFile("session.jsonl", bytes,
                    new SessionImportService.Options("ui-import", null, false));
            assertEquals(0, second.runs().size());
            assertEquals(1, second.skipped().size());
            assertTrue(second.skipped().get(0).contains("session-imp-1"), second.skipped().toString());

            var overwrite = service.importFile("session.jsonl", bytes,
                    new SessionImportService.Options("ui-import", null, true));
            assertEquals(1, overwrite.runs().size());
            assertTrue(overwrite.skipped().isEmpty());
        }
    }

    @Test
    void aiopslabImportKeepsPolicyEvaluatorAndPrefixedAgent() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            var outcome = service.importFile("results-8842.json",
                    AIOPSLAB_JSON.getBytes(StandardCharsets.UTF_8),
                    new SessionImportService.Options("ui-import", null, false));

            assertNull(outcome.error(), outcome.error());
            var imported = outcome.runs().get(0);
            assertEquals("aiopslab:qwen-agent", imported.agentName());
            assertTrue(imported.results().stream().anyMatch(r -> "tool-policy".equals(r.evaluator())));
            var evaluation = store.loadEvaluation("8d2b1a0e-1111-2222-3333-444455556666").orElseThrow();
            assertEquals("ui-import", evaluation.metadata().get("source"));
            assertNotNull(evaluation.metadata().get("problemId"));
        }
    }

    @Test
    void unknownContentIsRejectedGracefully() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            var service = new SessionImportService(store);
            var outcome = service.importFile("notes.txt", "plain prose".getBytes(StandardCharsets.UTF_8),
                    new SessionImportService.Options("ui-import", null, false));
            assertEquals(SessionImportService.Kind.UNKNOWN, outcome.kind());
            assertFalse(outcome.error() == null);
            assertTrue(outcome.runs().isEmpty());
        }
    }

    private static String otlpLineUnchecked() {
        try {
            return otlpLine("run-42", "task-7", false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TempDir
    Path tempDir;
}
