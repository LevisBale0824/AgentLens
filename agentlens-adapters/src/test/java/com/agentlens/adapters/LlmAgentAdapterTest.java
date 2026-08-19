package com.agentlens.adapters;

import com.agentlens.core.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the tool-calling loop against a fake OpenAI-compatible endpoint:
 * first response requests a tool call, second returns the final answer.
 */
class LlmAgentAdapterTest {

    private HttpServer server;
    private LlmAgentAdapter adapter;
    private final AtomicInteger callsSeen = new AtomicInteger();

    @BeforeEach
    void startFakeEndpoint() throws Exception {
        StringBuilder firstBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            callsSeen.incrementAndGet();
            String response = switch (callsSeen.get()) {
                case 1 -> """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"c1","function":{"name":"query_prometheus","arguments":"{}"}}]}}],
                         "usage":{"prompt_tokens":120,"completion_tokens":8}}""";
                default -> """
                        {"choices":[{"message":{"role":"assistant",
                          "content":"Root cause: DB connection pool exhaustion. Restart the leaking service and raise the pool limit."}}],
                         "usage":{"prompt_tokens":260,"completion_tokens":45}}""";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        adapter = new LlmAgentAdapter(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "fake-model", 0.0, Duration.ofSeconds(10),
                (tool, args) -> "metric: pool in use 20/20");
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void toolLoopProducesFullTraceAndRealTokens() {
        AgentTask task = new AgentTask("db_pool_exhausted", "diagnose the 5xx spike", Map.of(
                "allowedTools", List.of("query_prometheus", "get_pod_logs")), 6);
        AgentRun run = adapter.run(task);

        assertEquals(RunStatus.SUCCESS, run.status());
        assertTrue(run.finalAnswer().contains("pool exhaustion"));

        List<EventType> types = run.events().stream().map(AgentEvent::type).toList();
        assertEquals(EventType.MODEL_CALL, types.get(0));
        assertEquals(EventType.TOOL_CALL, types.get(1));
        assertEquals(EventType.TOOL_RESULT, types.get(2));
        assertEquals(EventType.MODEL_CALL, types.get(3));
        assertEquals(EventType.FINAL_ANSWER, types.get(4));

        // token accounting from both model turns (120+8 + 260+45)
        assertEquals(433, run.totalTokens());
        assertEquals("query_prometheus", run.events().get(1).name());
    }

    @Test
    void endpointFailureMarksRunFailed() throws Exception {
        server.stop(0);
        HttpServer dead = HttpServer.create(new InetSocketAddress(0), 0);
        int port = dead.getAddress().getPort(); // nothing listening after create+stop
        dead.stop(0);
        LlmAgentAdapter deadAdapter = new LlmAgentAdapter(
                java.net.URI.create("http://127.0.0.1:" + port), "m", 0.0,
                Duration.ofSeconds(2), (t, a) -> "");
        AgentRun run = deadAdapter.run(new AgentTask("t", "i", Map.of(), 4));
        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.events().stream().anyMatch(e -> e.type() == EventType.ERROR));
    }

    @Test
    void stepBudgetExhaustionFailsRun() throws Exception {
        // endpoint always requests a tool -> loop until maxSteps
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            String response = """
                    {"choices":[{"message":{"content":null,"tool_calls":[
                      {"id":"c","function":{"name":"query_prometheus","arguments":"{}"}}]}}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        AgentRun run = adapter.run(new AgentTask("t", "i", Map.of(), 3));
        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.error().contains("no final answer"));
        assertEquals(3, run.events().stream().filter(e -> e.type() == EventType.MODEL_CALL).count());
    }
}
