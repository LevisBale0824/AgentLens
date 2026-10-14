package com.agentlens.cli;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.agentlens.eval.SqliteRunStore;
import com.agentlens.otel.OtelSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelReceiveServerTest {

    @TempDir
    Path temp;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private SqliteRunStore store;
    private OtelReceiveServer server;

    @BeforeEach
    void startServer() throws Exception {
        store = new SqliteRunStore(temp.resolve("ingest.db"));
        server = new OtelReceiveServer(store, 0);
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        store.close();
    }

    private static AgentRun sampleRun() {
        Instant base = Instant.parse("2026-10-14T08:00:00Z");
        return new AgentRun("run-42", "task-7", RunStatus.SUCCESS, "all good", List.of(
                new AgentEvent(EventType.TOOL_CALL, "query_prometheus",
                        Map.of("phase", "diagnose"), base, 5)),
                base, 6, 100, null);
    }

    @Test
    void ingestedTraceIsEvaluatedAndPersisted() throws Exception {
        String body = json.writeValueAsString(OtelSpanMapper.toOtlpJson(sampleRun()));
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/v1/traces"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"accepted\":1"), response.body());

        // Run persisted with the native id and its deterministic evaluation.
        var run = store.loadRun("run-42").orElseThrow();
        assertEquals("task-7", run.taskId());
        var evaluation = store.loadEvaluation("run-42").orElseThrow();
        assertEquals("otlp", evaluation.metadata().get("ingest"));
        assertTrue(evaluation.results().stream().anyMatch(r -> "task-completion".equals(r.evaluator())
                && r.passed()));
        assertTrue(evaluation.results().stream().anyMatch(r -> "run-limits".equals(r.evaluator())));
    }

    @Test
    void malformedBodyIsRejectedWith400() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/v1/traces"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"nope\":true}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("resourceSpans"), response.body());
    }

    @Test
    void infoEndpointDescribesTheContract() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("otel-receive"), response.body());
        assertTrue(response.body().contains("/v1/traces"), response.body());
    }

    @Test
    void wrongMethodIsRejectedWith405() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/v1/traces")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }
}
