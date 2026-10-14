package com.agentlens.cli;

import com.agentlens.core.AgentRun;
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
    private final SessionImportService imports;

    public OtelReceiveServer(SqliteRunStore store, int port) throws IOException {
        this.imports = new SessionImportService(store);
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

    /**
     * Persists one ingested run through the shared import service. Overwrite
     * stays on: OTLP exporters retry the same payload after transport errors,
     * and an upsert matches the pre-service behavior.
     */
    private void persist(AgentRun run, Map<String, Object> payload) throws IOException {
        imports.importOtelRun(run, OtlpIngestMapper.serviceName(payload, "otel"),
                "otel-receive", true);
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
