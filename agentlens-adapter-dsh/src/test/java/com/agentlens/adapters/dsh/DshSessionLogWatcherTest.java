package com.agentlens.adapters.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DshSessionLogWatcherTest {

    @Test
    void tailsZstdFramesIncrementally(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.jsonl.zstd");
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        try (var watcher = DshSessionLogWatcher.watch(file, 50, received::add).start()) {
            appendFrame(file, event("session", 1));
            await(() -> received.size() >= 1);
            appendFrame(file, event("turn/start", 2));
            appendFrame(file, event("tool/call", 3));
            await(() -> received.size() >= 3);
            // A frame arriving in two flushes: watcher must skip the torn first half,
            // then consume the completed frame once the second half lands.
            byte[] frame = frameBytes(event("step/end", 4));
            Files.write(file, java.util.Arrays.copyOfRange(frame, 0, 15),
                    java.nio.file.StandardOpenOption.APPEND);
            Thread.sleep(200);
            assertEquals(3, received.size()); // torn frame not emitted yet
            Files.write(file, java.util.Arrays.copyOfRange(frame, 15, frame.length),
                    java.nio.file.StandardOpenOption.APPEND);
            await(() -> received.size() >= 4);
        }
        assertEquals(List.of("session", "turn/start", "tool/call", "step/end"),
                received.stream().map(n -> n.get("type").asText()).toList());
    }

    @Test
    void buffersPartialLinesUntilNewlineArrives(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.jsonl"); // plain text mode
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        try (var watcher = DshSessionLogWatcher.watch(file, 50, received::add).start()) {
            Files.writeString(file, "{\"type\": \"session\", \"id\": \"x\"}", java.nio.file.StandardOpenOption.CREATE);
            Thread.sleep(200);
            assertEquals(0, received.size()); // no newline yet: buffered
            Files.writeString(file, "\n{\"type\": \"turn/start\"}\n", java.nio.file.StandardOpenOption.APPEND);
            await(() -> received.size() >= 2);
        }
        assertEquals(2, received.size());
        assertEquals("session", received.get(0).get("type").asText());
        assertEquals("turn/start", received.get(1).get("type").asText());
    }

    private static String event(String type, int seq) {
        return "{\"type\": \"" + type + "\", \"seq\": " + seq + ", \"time\": " + (1000 + seq)
                + ", \"data\": {\"turn\": 1}}\n";
    }

    private static void appendFrame(Path file, String jsonl) throws Exception {
        Files.write(file, frameBytes(jsonl),
                Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND
                        : java.nio.file.StandardOpenOption.CREATE);
    }

    private static byte[] frameBytes(String jsonl) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream z = new com.github.luben.zstd.ZstdOutputStream(out)) {
            z.write(jsonl.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "condition not met within timeout");
    }
}
