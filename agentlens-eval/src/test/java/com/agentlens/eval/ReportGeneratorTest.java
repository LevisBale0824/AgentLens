package com.agentlens.eval;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesHtmlWithScenariosAndRuns() throws Exception {
        var adapter = new MockAgentAdapter();
        var task = new AgentTask("report-scenario", "diagnose", Map.of(
                "allowedTools", List.of("query_prometheus")), 10);
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            AgentRun run = adapter.run(task);
            store.saveRun(run, "mock");
            store.saveEvaluation(new EvaluationRun(run.runId(), task.id(), "mock", "v1",
                    run.startedAt(),
                    List.of(new com.agentlens.core.EvaluationResult("tool-policy", true, 1.0, "ok", Map.of())),
                    Map.of()));

            Path out = new ReportGenerator().generate(store, tempDir.resolve("report.html"));
            assertTrue(Files.exists(out));
            String html = Files.readString(out);
            assertTrue(html.contains("<!DOCTYPE html>"));
            assertTrue(html.contains("report-scenario"));
            assertTrue(html.contains("STABLE_PASS"));
            assertTrue(html.contains("TOOL_CALL"));
            assertTrue(html.contains("query_prometheus"));
            assertTrue(html.contains("tool-policy"));
            // self-contained: no external asset references
            assertFalse(html.contains("src=\"http"));
            assertFalse(html.contains("href=\"http"));
        }
    }

    @Test
    void emptyStoreRejected() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("empty.db"))) {
            assertThrows(Exception.class,
                    () -> new ReportGenerator().generate(store, tempDir.resolve("r.html")));
        }
    }

    @Test
    void htmlEscapesUntrustedContent() throws Exception {
        var task = new AgentTask("xss<script>", "diagnose", Map.of(
                "allowedTools", List.of("query_prometheus")), 10);
        var run = new MockAgentAdapter().run(task);
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runs.db"))) {
            store.saveRun(run, "mock");
            String html = Files.readString(new ReportGenerator().generate(store, tempDir.resolve("r.html")));
            assertFalse(html.contains("<script>"), "raw script tag must be escaped");
            assertTrue(html.contains("xss&lt;script&gt;"));
        }
    }
}
