package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.agentlens.otel.OtelSpanMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the OTLP/HTTP exporter (ROADMAP P2-3): payload delivery to a real
 * local endpoint, retry after a server error, fail-open on connection refusal.
 */
class OtlpExporterTest {

    private static Map<String, Object> payload() {
        AgentRun run = new AgentRun("run-otel", "task-1", RunStatus.SUCCESS, "done",
                List.of(new AgentEvent(EventType.TOOL_CALL, "read", Map.of(), Instant.now(), 5)),
                Instant.now(), 10, 0, null);
        return OtelSpanMapper.toOtlpJson(run);
    }

    @Test
    void postsOtlpJsonToCollectorPath() throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        StringBuilder received = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            received.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            var exporter = new OtlpExporter(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces"),
                    Duration.ofSeconds(5), 1);
            assertTrue(exporter.export(payload()));
            assertTrue(received.toString().contains("resourceSpans"));
            assertTrue(received.toString().contains("agentlens.run"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesAfterServerErrorAndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(calls.get() == 1 ? 500 : 200, -1);
            exchange.close();
        });
        server.start();
        try {
            var exporter = new OtlpExporter(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces"),
                    Duration.ofSeconds(5), 3);
            assertTrue(exporter.export(payload()));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsOpenWhenEndpointIsDown() {
        // Port 1 on localhost is refused immediately; a single attempt keeps this fast.
        var exporter = new OtlpExporter(URI.create("http://127.0.0.1:1/v1/traces"),
                Duration.ofSeconds(2), 1);
        assertFalse(exporter.export(payload()));
    }

    @Test
    void non2xxWithinAttemptsIsFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var exporter = new OtlpExporter(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces"),
                    Duration.ofSeconds(5), 2);
            assertFalse(exporter.export(payload()));
        } finally {
            server.stop(0);
        }
    }
}
