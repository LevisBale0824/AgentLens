package com.agentlens.adapters;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class PrometheusClient {
    private final HttpClient client = HttpClient.newHttpClient();
    private final URI baseUri;

    public PrometheusClient(URI baseUri) { this.baseUri = baseUri; }

    public String query(String promQl) throws Exception {
        URI uri = URI.create(baseUri.toString().replaceAll("/$", "") + "/api/v1/query?query="
                + URLEncoder.encode(promQl, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
