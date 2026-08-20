package com.agentlens.adapters;

import com.agentlens.core.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HttpAgentAdapter implements AgentAdapter {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final Duration timeout;

    /** SPI no-arg constructor: endpoint comes from AGENTLENS_HTTP_ENDPOINT (default localhost:8080). */
    public HttpAgentAdapter() {
        this(URI.create(System.getenv().getOrDefault("AGENTLENS_HTTP_ENDPOINT", "http://localhost:8080/agent")), null);
    }

    public HttpAgentAdapter(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    @Override public String name() { return "http"; }

    @Override
    public AgentRun run(AgentTask task) {
        Instant started = Instant.now();
        try {
            String body = "{\"task_id\":\"" + escape(task.id()) + "\",\"instruction\":\"" + escape(task.instruction()) + "\"}";
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            AgentEvent event = new AgentEvent(success ? EventType.FINAL_ANSWER : EventType.ERROR,
                    "http.response", Map.of("statusCode", response.statusCode()), started,
                    Duration.between(started, Instant.now()).toMillis());
            return new AgentRun(UUID.randomUUID().toString(), task.id(), success ? RunStatus.SUCCESS : RunStatus.FAILED,
                    success ? response.body() : null, List.of(event), started,
                    Duration.between(started, Instant.now()).toMillis(), 0,
                    success ? null : "HTTP status " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(task, started, RunStatus.CANCELLED, e.getMessage());
        } catch (Exception e) {
            return failed(task, started, RunStatus.FAILED, e.getMessage());
        }
    }

    private AgentRun failed(AgentTask task, Instant started, RunStatus status, String error) {
        return new AgentRun(UUID.randomUUID().toString(), task.id(), status, null,
                List.of(new AgentEvent(EventType.ERROR, "http.error", Map.of("error", String.valueOf(error)), started, 0)),
                started, Duration.between(started, Instant.now()).toMillis(), 0, error);
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
