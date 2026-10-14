package com.agentlens.cli;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.EvaluationRun;
import com.agentlens.eval.SqliteRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the mini web dashboard (ROADMAP P2-4): the embedded API serves the
 * SQLite store - run summaries, run detail with live-computed diagnoses,
 * scenario pass^k layering, and the HTML page itself.
 */
class DashboardServerTest {

    @TempDir
    Path tempDir;

    private static final String RUN_ID = "run-dashboard-1";

    private SqliteRunStore storeWithOneRun(Path db) throws Exception {
        AgentTask task = new AgentTask("dash-task", "diagnose", Map.of(), 10);
        AgentRun run = new MockAgentAdapter().run(task);
        AgentRun identified = new AgentRun(RUN_ID, run.taskId(), run.status(), run.finalAnswer(),
                run.events(), run.startedAt(), run.durationMs(), run.totalTokens(), run.error());
        SqliteRunStore store = new SqliteRunStore(db);
        store.saveRun(identified, "mock");
        // Production convention: the evaluation id IS the run id (evaluations.run_id PK).
        store.saveEvaluation(new EvaluationRun(RUN_ID, identified.taskId(), "mock", "baseline",
                identified.startedAt(),
                List.of(new EvaluationResult("task-completion", true, 1.0, "completed", Map.of())),
                Map.of("durationMs", identified.durationMs())));
        return store;
    }

    private String get(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 404,
                response.statusCode() + " for " + url);
        return response.body();
    }

    @Test
    void servesHtmlPageAndApiEndpoints() throws Exception {
        try (SqliteRunStore store = storeWithOneRun(tempDir.resolve("runs.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            String base = server.baseUrl();
            assertTrue(get(base + "/").contains("AgentLens Dashboard"));
            assertTrue(get(base + "/").contains("Version diff (per-case)"));
            assertTrue(get(base + "/api/runs").contains(RUN_ID));
            assertTrue(get(base + "/api/scenarios").contains("STABLE_PASS"));
            assertTrue(get(base + "/api/summary").contains("\"runs\":1"));
        }
    }

    @Test
    void runDetailIncludesEventsEvaluationAndLiveDiagnoses() throws Exception {
        try (SqliteRunStore store = storeWithOneRun(tempDir.resolve("runs.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            String detail = get(server.baseUrl() + "/api/runs/" + RUN_ID);
            assertTrue(detail.contains("\"events\""));
            // Mock correct-behavior runs (empty allowed tools) emit a FINAL_ANSWER event.
            assertTrue(detail.contains("FINAL_ANSWER"));
            assertTrue(detail.contains("task-completion"));
            assertTrue(detail.contains("\"diagnoses\""));
            assertTrue(detail.contains("baseline"));
        }
    }

    @Test
    void unknownRunReturns404Body() throws Exception {
        try (SqliteRunStore store = storeWithOneRun(tempDir.resolve("runs.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            String body = get(server.baseUrl() + "/api/runs/no-such-run");
            assertTrue(body.contains("run not found"));
            assertFalse(body.contains(RUN_ID));
        }
    }

    @Test
    void scenarioLayeringMatchesEvaluationOutcome() throws Exception {
        try (SqliteRunStore store = storeWithOneRun(tempDir.resolve("runs.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            // One scenario x one passing run -> STABLE_PASS, passRate 1.0
            String scenarios = get(server.baseUrl() + "/api/scenarios");
            assertTrue(scenarios.contains("STABLE_PASS"));
            assertTrue(scenarios.contains("dash-task"));
            String summary = get(server.baseUrl() + "/api/summary");
            assertTrue(summary.contains("\"stablePass\":1"));
            assertTrue(summary.contains("\"flaky\":0"));
        }
    }

    /** Stores one run + evaluation pair for a (taskId, version, passed) case. */
    private void addCase(SqliteRunStore store, String taskId, String version, boolean passed,
                         String judgeReason) throws Exception {
        String runId = "run-" + taskId + "-" + version;
        AgentTask task = new AgentTask(taskId, "diagnose", Map.of(), 10);
        AgentRun run = new MockAgentAdapter().run(task);
        AgentRun identified = new AgentRun(runId, run.taskId(), run.status(),
                passed ? run.finalAnswer() : "wrong conclusion",
                run.events(), run.startedAt(), run.durationMs(), run.totalTokens(), run.error());
        store.saveRun(identified, "agent");
        store.saveEvaluation(new EvaluationRun(runId, taskId, "agent", version,
                identified.startedAt(),
                List.of(new EvaluationResult("llm-judge", passed, passed ? 0.9 : 0.2,
                        judgeReason, Map.of())),
                Map.of()));
    }

    @Test
    void perCaseDiffHighlightsFlipsAlignsByTaskAndDrillsIntoTraces() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("diff.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            // v1 batch first, v2 batch last -> v2 is the most recent version
            // (candidate), v1 the baseline; the unpaired case must not change that
            addCase(store, "case-only-baseline", "v1", true, "fine");
            addCase(store, "case-pass-pass", "v1", true, "fine");
            addCase(store, "case-pass-pass", "v2", true, "fine");
            addCase(store, "case-regression", "v1", true, "was correct");
            addCase(store, "case-regression", "v2", false, "wrong conclusion asserted");
            addCase(store, "case-fix", "v1", false, "hallucinated evidence");
            addCase(store, "case-fix", "v2", true, "now cites tools");
            addCase(store, "case-same-fail", "v1", false, "still broken");
            addCase(store, "case-same-fail", "v2", false, "still broken");

            String body = get(server.baseUrl() + "/api/diff");
            assertTrue(body.contains("\"FLIP_TO_FAIL\""), body);
            assertTrue(body.contains("\"FLIP_TO_PASS\""), body);
            assertTrue(body.contains("\"SAME_FAIL\""), body);
            assertTrue(body.contains("MISSING_CANDIDATE"), body);
            // failing side's judge reason surfaces for drill-down context
            assertTrue(body.contains("wrong conclusion asserted"), body);
            assertTrue(body.contains("hallucinated evidence"), body);
            assertTrue(body.contains("\"flips\":2"), body);
            // explicit params reorder the comparison
            String explicit = get(server.baseUrl() + "/api/diff?baseline=v2&candidate=v1");
            assertTrue(explicit.contains("\"baseline\":\"v2\""), explicit);
            assertTrue(explicit.contains("\"candidate\":\"v1\""), explicit);
        }
    }

    @Test
    void diffWithFewerThanTwoVersionsStillResponds() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("diff1.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            addCase(store, "solo", "v1", true, "fine");
            String body = get(server.baseUrl() + "/api/diff");
            // single version: it becomes the candidate, baseline side is missing
            assertTrue(body.contains("MISSING_BASELINE"), body);
            assertTrue(body.contains("\"candidate\":\"v1\""), body);
        }
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("diff0.db"));
             DashboardServer server = new DashboardServer(store, 0)) {
            String empty = get(server.baseUrl() + "/api/diff");
            assertTrue(empty.contains("\"rows\":[]"), empty);
        }
    }
}
