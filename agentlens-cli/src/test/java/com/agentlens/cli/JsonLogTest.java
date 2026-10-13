package com.agentlens.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structured NDJSON logging (ROADMAP P2-5): line shape, kv pairs, no-op mode. */
class JsonLogTest {

    private ByteArrayOutputStream capture() {
        return new ByteArrayOutputStream();
    }

    private static List<String> lines(ByteArrayOutputStream out) {
        return java.util.Arrays.stream(out.toString(StandardCharsets.UTF_8).split("\n"))
                .filter(s -> !s.isBlank()).toList();
    }

    @Test
    void everyLineCarriesTsLevelEventAndRequestId() throws Exception {
        var out = capture();
        JsonLog log = JsonLog.to(new PrintStream(out, true, "UTF-8"), "req-test1234");
        log.event("info", "run.completed", "runId", "r-1", "status", "SUCCESS");
        var all = lines(out);
        assertEquals(1, all.size());
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(all.get(0));
        assertFalse(node.get("ts").asText().isBlank());
        assertEquals("info", node.get("level").asText());
        assertEquals("run.completed", node.get("event").asText());
        assertEquals("req-test1234", node.get("requestId").asText());
        assertEquals("r-1", node.get("runId").asText());
        assertEquals("SUCCESS", node.get("status").asText());
    }

    @Test
    void multipleEventsAppendOneLineEach() throws Exception {
        var out = capture();
        JsonLog log = JsonLog.to(new PrintStream(out, true, "UTF-8"), "req-abc");
        log.event("info", "cli.start", "command", "run");
        log.event("warn", "gate.verdict", "passed", false, "reason", "flaky");
        var all = lines(out);
        assertEquals(2, all.size());
        assertTrue(all.get(0).contains("\"event\":\"cli.start\""));
        assertTrue(all.get(1).contains("\"event\":\"gate.verdict\""));
        assertTrue(all.get(1).contains("\"passed\":false"));
    }

    @Test
    void oddKvTailIsIgnoredSafely() throws Exception {
        var out = capture();
        JsonLog log = JsonLog.to(new PrintStream(out, true, "UTF-8"), "req-odd");
        log.event("info", "weird", "key", "value", "dangling");
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(lines(out).get(0));
        assertEquals("value", node.get("key").asText());
        assertEquals(null, node.get("dangling"));
    }

    @Test
    void disabledLogIsANoOp() {
        var out = capture();
        // disabled() shares a singleton without a sink - nothing must ever be written
        JsonLog.disabled().event("info", "never.emitted", "k", "v");
        assertEquals(0, lines(out).size());
    }

    @Test
    void requestIdFormatIsReqPlus8Hex() {
        String id = JsonLog.newRequestId();
        assertTrue(id.matches("req-[0-9a-f]{8}"), "got: " + id);
    }
}
