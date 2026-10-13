package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROADMAP P2-1: structured verdict parsing, retry, cache, digest determinism. */
class OpenAiJudgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentTask TASK =
            new AgentTask("t-judge", "diagnose the alert", Map.of(), 10);
    private static final AgentRun RUN = new AgentRun("r1", "t-judge", RunStatus.SUCCESS,
            "root cause: cpu saturation",
            List.of(new AgentEvent(EventType.TOOL_CALL, "query_prometheus", Map.of("phase", "diagnose"), null, 5),
                    new AgentEvent(EventType.TOOL_RESULT, "query_prometheus", Map.of("status", "ok"), null, 3)),
            Instant.now(), 100, 42, null);

    private static final String VERDICT =
            "{\"score\":0.8,\"passed\":true,\"reason\":\"answer matches tool results\",\"evidence\":[\"final answer cites query_prometheus\"]}";

    /** Starts a stub /v1/chat/completions server returning the given contents in order. */
    private static HttpServer startJudgeServer(List<String> contents) throws IOException {
        AtomicInteger call = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int i = Math.min(call.getAndIncrement(), contents.size() - 1);
            String body = JSON.writeValueAsString(Map.of("choices", List.of(
                    Map.of("message", Map.of("role", "assistant", "content", contents.get(i)))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20)));
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static OpenAiJudge judgeAt(HttpServer server) {
        return new OpenAiJudge(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "test-judge-model", null, 0.0, Duration.ofSeconds(10), 3);
    }

    @Test
    void parsesStructuredVerdictWithVersionRecording() throws Exception {
        HttpServer server = startJudgeServer(List.of(VERDICT));
        OpenAiJudge judge = judgeAt(server);
        try {
            EvaluationResult result = judge.judge(TASK, RUN, "explanation_quality: clear answer");
            assertEquals("llm-judge:explanation_quality: clear answer", result.evaluator());
            assertTrue(result.passed());
            assertEquals(0.8, result.score());
            assertEquals("answer matches tool results", result.reason());
            assertEquals("test-judge-model", result.evidence().get("judge.model"));
            assertEquals(OpenAiJudge.PROMPT_VERSION, result.evidence().get("judge.promptVersion"));
            assertEquals("explanation_quality: clear answer", result.evidence().get("judge.criterion"));
            assertEquals(1, judge.endpointCalls());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void extractsFencedJsonWithSurroundingProse() throws Exception {
        String fenced = "Here is my verdict:\n```json\n" + VERDICT + "\n```\nHope this helps!";
        HttpServer server = startJudgeServer(List.of(fenced));
        try {
            EvaluationResult result = judgeAt(server).judge(TASK, RUN, "c");
            assertTrue(result.passed());
            assertEquals(0.8, result.score());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOnSchemaViolationThenSucceeds() throws Exception {
        HttpServer server = startJudgeServer(List.of(
                "{\"score\":1.5,\"passed\":true,\"reason\":\"out of range\"}", VERDICT));
        OpenAiJudge judge = judgeAt(server);
        try {
            EvaluationResult result = judge.judge(TASK, RUN, "c");
            assertTrue(result.passed());
            assertEquals(2, judge.endpointCalls());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachesIdenticalTracesButNotDifferentCriteria() throws Exception {
        HttpServer server = startJudgeServer(List.of(VERDICT, VERDICT));
        OpenAiJudge judge = judgeAt(server);
        try {
            EvaluationResult first = judge.judge(TASK, RUN, "c1");
            EvaluationResult again = judge.judge(TASK, RUN, "c1");
            assertEquals(1, judge.endpointCalls(), "same trace + criterion must be served from cache");
            assertEquals(first, again);
            judge.judge(TASK, RUN, "c2");
            assertEquals(2, judge.endpointCalls(), "a different criterion is a separate judgment");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void derivePassedFromScoreWhenAbsent() {
        Map<String, Object> high = new OpenAiJudge(URI.create("http://127.0.0.1:1"), "m")
                .parseVerdict("{\"score\":0.9,\"reason\":\"fine\"}");
        assertTrue((Boolean) high.get("passed"));
        Map<String, Object> low = new OpenAiJudge(URI.create("http://127.0.0.1:1"), "m")
                .parseVerdict("{\"score\":0.3,\"reason\":\"weak\"}");
        assertFalse((Boolean) low.get("passed"));
    }

    @Test
    void extractsBalancedJsonObjectIgnoringBracesInStrings() {
        String content = "noise {\"a\":\"has } brace\",\"b\":{\"c\":1}} tail";
        assertEquals("{\"a\":\"has } brace\",\"b\":{\"c\":1}}",
                OpenAiJudge.extractJsonObject(content));
        assertNull(OpenAiJudge.extractJsonObject("no json here"));
    }

    @Test
    void ollamaNativeTransportParsesMessageContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String body = JSON.writeValueAsString(Map.of(
                    "message", Map.of("role", "assistant", "content", VERDICT)));
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var judge = new OpenAiJudge(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "m", null, 0.0, Duration.ofSeconds(10), 3, false, true);
        try {
            EvaluationResult result = judge.judge(TASK, RUN, "c");
            assertTrue(result.passed());
            assertEquals(0.8, result.score());
            assertEquals(1, judge.endpointCalls());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void traceDigestSanitizesJsonHostileCharacters() {
        var events = List.of(new AgentEvent(EventType.TOOL_CALL, "write",
                Map.of("arguments", "{\"content\":\"# Notes\"}", "path", "D:\\tmp\\x"), null, 0));
        String digest = OpenAiJudge.traceDigest(TASK,
                new AgentRun("r", "t", RunStatus.SUCCESS, "done {\"quoted\"}", events, null, 1, 0, null));
        for (String hostile : new String[]{"\\", "\"", "{", "}"}) {
            assertFalse(digest.contains(hostile), "digest must not contain " + hostile + ": " + digest);
        }
        assertTrue(digest.contains("D:/tmp/x"));
    }

    @Test
    void traceDigestIsDeterministicAndAttributeOrderInsensitive() {
        var events1 = List.of(new AgentEvent(EventType.TOOL_CALL, "write",
                new java.util.LinkedHashMap<>(Map.of("arguments", "a", "callId", "c1")), null, 0));
        var events2 = List.of(new AgentEvent(EventType.TOOL_CALL, "write",
                new java.util.LinkedHashMap<>(Map.of("callId", "c1", "arguments", "a")), null, 0));
        String d1 = OpenAiJudge.traceDigest(TASK,
                new AgentRun("r", "t", RunStatus.SUCCESS, "done", events1, null, 1, 0, null));
        String d2 = OpenAiJudge.traceDigest(TASK,
                new AgentRun("r", "t", RunStatus.SUCCESS, "done", events2, null, 1, 0, null));
        assertEquals(d1, d2, "attribute iteration order must not change the digest");
        assertTrue(d1.contains("task: t-judge"));
        assertTrue(d1.contains("status: SUCCESS"));
        assertTrue(d1.contains("TOOL_CALL write"));
    }
}
