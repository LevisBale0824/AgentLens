package com.agentlens.adapters.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DshSessionLogReaderTest {

    private static final List<String> LINES = List.of(
            "{\"type\": \"session\", \"version\": 0, \"id\": \"session-abc\", \"createdAt\": 1787145673496, \"cwd\": \"D:\\\\code\\\\AgentLens\", \"agentPreset\": \"standard\"}",
            "{\"type\": \"turn/start\", \"seq\": 4, \"time\": 1787145685177, \"data\": {\"turn\": 1}}",
            "{\"type\": \"tool/call\", \"seq\": 123, \"time\": 1787145690540, \"data\": {\"turn\": 1, \"step\": 1, \"callId\": \"call_1\", \"name\": \"skill\", \"arguments\": \"{\\\"name\\\":\\\"x\\\"}\"}}",
            "{\"type\": \"tool/result\", \"seq\": 124, \"time\": 1787145690554, \"data\": {\"turn\": 1, \"step\": 1, \"message\": {\"source\": {\"kind\": \"tool\", \"callId\": \"call_1\"}, \"content\": [{\"type\": \"tool-result\", \"content\": [{\"type\": \"text\", \"text\": \"ok\"}]}]}}}",
            "{\"type\": \"assistant/message\", \"seq\": 130, \"time\": 1787145692000, \"data\": {\"turn\": 1, \"step\": 2, \"message\": {\"role\": \"assistant\", \"content\": [{\"type\": \"reasoning\", \"text\": \"thinking\"}, {\"type\": \"text\", \"text\": \"final answer here\"}], \"source\": {\"kind\": \"model\", \"provider\": \"zai\", \"model\": \"glm-5.1\"}}}}"
    );

    @Test
    void readsPlainJsonl(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.jsonl");
        Files.writeString(file, String.join("\n", LINES) + "\n");
        List<JsonNode> events = DshSessionLogReader.read(file);
        assertEquals(5, events.size());
        assertEquals("session", events.get(0).get("type").asText());
        assertEquals("tool/call", events.get(2).get("type").asText());
    }

    @Test
    void readsMultiFrameZstdContainerAndIgnoresTornTail(@TempDir Path dir) throws Exception {
        // Two complete frames (one event batch each) + trailing bytes that scan as a torn frame.
        byte[] container = concat(
                frame(LINES.get(0) + "\n" + LINES.get(1) + "\n"),
                frame(LINES.get(2) + "\n" + LINES.get(3) + "\n" + LINES.get(4) + "\n"),
                new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x00});

        Path file = dir.resolve("session.jsonl.zstd");
        Files.write(file, container);

        List<JsonNode> events = DshSessionLogReader.read(file);
        assertEquals(5, events.size());
        assertEquals("session", events.get(0).get("type").asText());
        assertEquals("assistant/message", events.get(4).get("type").asText());
    }

    @Test
    void readsRealLocalSessionLogWhenPresent() {
        Path home = Path.of(System.getProperty("user.home"), ".dsh", "sessions");
        if (!Files.isDirectory(home)) return; // machine without DSH: skip
        try (var files = Files.walk(home, 3)) {
            Path real = files.filter(p -> p.getFileName().toString().equals("session.jsonl.zstd"))
                    .findFirst().orElse(null);
            if (real == null) return;
            List<JsonNode> events = DshSessionLogReader.read(real);
            assertFalse(events.isEmpty());
            assertEquals("session", events.get(0).get("type").asText());
        } catch (Exception e) {
            fail("failed to read real session log: " + e.getMessage());
        }
    }

    @Test
    void scanFramesRejectsBadMagic() {
        ByteBuffer bad = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bad.putInt(0x11223344).putInt(0);
        assertThrows(IllegalArgumentException.class, () -> DshSessionLogReader.scanFrames(bad));
    }

    private static byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] c : chunks) total += c.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] c : chunks) { System.arraycopy(c, 0, out, pos, c.length); pos += c.length; }
        return out;
    }

    /** One independently decodable zstd frame, mirroring DSH's per-batch compression. */
    private static byte[] frame(String jsonl) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream z = new com.github.luben.zstd.ZstdOutputStream(out)) {
            z.write(jsonl.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
