package com.agentlens.cli;

import com.agentlens.core.AgentRun;
import com.agentlens.core.EvaluationRun;
import com.agentlens.eval.SqliteRunStore;
import com.agentlens.otel.OtlpIngestMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * P0-B: OTLP/JSON ingestion endpoint - the receiving twin of the P2-3 export
 * path. Agents instrumented with OpenTelemetry (any language, any framework:
 * OpenLLMetry, OpenInference auto-instrumentors, the OTel javaagent) point
 * their OTLP/HTTP exporter here; every trace becomes a persisted, evaluated
 * run in the shared store - zero adapter code per new agent.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /v1/traces - OTLP/JSON body; mapped runs are auto-evaluated with
 *       the deterministic evaluators and persisted. Responds 200 with the
 *       OTLP empty success body, 400 with a plain message on malformed input.</li>
 *   <li>GET / - small JSON info card (endpoint, db, accepted conventions).</li>
 * </ul>
 */
public final class OtelReceiveServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private final HttpServer server;
    private final SqliteRunStore store;

    public OtelReceiveServer(SqliteRunStore store, int port) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/traces", this::handleTraces);
        server.createContext("/", this::handleInfo);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port();
    }

    /** Handles POST /v1/traces: parse -> map -> evaluate -> persist. */
    void handleTraces(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("message", "POST OTLP/JSON traces here"));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES);
        Map<String, Object> payload;
        try {
            payload = JSON.readValue(new String(body, StandardCharsets.UTF_8),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<AgentRun> runs = OtlpIngestMapper.fromOtlpJson(payload);
            for (AgentRun run : runs) persist(run, payload);
            respond(exchange, 200, Map.of("accepted", runs.size(), "partialSuccess", Map.of()));
        } catch (Exception e) {
            respond(exchange, 400, Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /** Persists one ingested run with its deterministic evaluation. */
    private void persist(AgentRun run, Map<String, Object> payload) throws IOException {
        String agentName = serviceName(payload, "otel");
        // No scenario context exists for ingested traces; the synthetic task gives
        // RunLimitEvaluator a generous step budget while keeping evaluators honest.
        var task = new com.agentlens.core.AgentTask(run.taskId(),
                "ingested OTLP trace (no scenario context)", Map.of(), 50);
        var evaluations = new com.agentlens.eval.EvaluatorRegistry()
                .register(new com.agentlens.eval.TaskCompletionEvaluator())
                .register(new com.agentlens.eval.RunLimitEvaluator())
                .evaluate(task, run);
        store.saveRun(run, agentName);
        store.saveEvaluation(new EvaluationRun(run.runId(), run.taskId(), agentName, "ingest",
                run.startedAt(), evaluations,
                Map.of("ingest", "otlp")));
    }

    /** service.name from the first resource that carries one. */
    private String serviceName(Map<String, Object> payload, String fallback) {
        if (payload.get("resourceSpans") instanceof List<?> resourceSpans) {
            for (Object rs : resourceSpans) {
                if (!(rs instanceof Map<?, ?> rsMap)) continue;
                Object resource = rsMap.get("resource");
                if (!(resource instanceof Map<?, ?> res)) continue;
                Object attrs = res.get("attributes");
                if (!(attrs instanceof List<?> attrList)) continue;
                for (Object item : attrList) {
                    if (item instanceof Map<?, ?> attr && "service.name".equals(attr.get("key"))
                            && attr.get("value") instanceof Map<?, ?> value
                            && value.get("stringValue") != null)
                        return String.valueOf(value.get("stringValue"));
                }
            }
        }
        return fallback;
    }

    private void handleInfo(HttpExchange exchange) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("command", "otel-receive");
        info.put("endpoint", "/v1/traces (OTLP/JSON, POST)");
        info.put("conventions", List.of("agentlens.* (native round trip)",
                "OTel GenAI semconv (gen_ai.usage.*, gen_ai.request.model)",
                "OpenInference (llm.token_count.*, generation/retriever spans)"));
        respond(exchange, 200, info);
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
