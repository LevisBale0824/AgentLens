package com.agentlens.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * ROADMAP P2-3: posts OTLP/JSON trace payloads to an OTLP/HTTP endpoint
 * (e.g. an OpenTelemetry Collector at {@code http://host:4318/v1/traces}, or
 * the OTLP endpoint of Phoenix/Langfuse/Jaeger).
 *
 * <p>Fail-open (same philosophy as the LLM judge): the observability side
 * channel must never break an evaluation run. Transport problems and non-2xx
 * answers are reported on stderr and {@link #export(Map)} returns false.
 */
public final class OtlpExporter {
    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;
    private final int attempts;
    private final ObjectMapper mapper = new ObjectMapper();

    public OtlpExporter(URI endpoint, Duration timeout, int attempts) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.attempts = Math.max(1, attempts);
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    /** @return true if the endpoint accepted the payload (2xx) within the attempts. */
    public boolean export(Map<String, Object> otlpPayload) {
        String body;
        try {
            body = mapper.writeValueAsString(otlpPayload);
        } catch (Exception e) {
            System.err.println("otel export: payload serialization failed (fail-open): " + e);
            return false;
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) return true;
                System.err.printf("otel export: %s answered %d (attempt %d/%d)%n",
                        endpoint, response.statusCode(), attempt, attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("otel export interrupted (fail-open)");
                return false;
            } catch (Exception e) {
                System.err.printf("otel export failed (attempt %d/%d): %s%n", attempt, attempts, e);
            }
        }
        return false;
    }
}
