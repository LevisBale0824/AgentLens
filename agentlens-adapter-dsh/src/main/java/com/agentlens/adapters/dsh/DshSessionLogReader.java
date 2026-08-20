package com.agentlens.adapters.dsh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a DeepSeek Harness session log and returns its raw JSONL events.
 *
 * <p>DSH persists sessions as {@code session.jsonl.zstd}: a container of
 * concatenated, independently decodable Zstandard frames (one frame per durable
 * event batch), possibly with a torn in-flight final frame. The frame scanner
 * below is a Java port of DSH's own {@code scanZstdFrames} structural scan:
 * it locates complete frame boundaries without decompressing block payloads,
 * then decompresses each frame separately. Plain {@code session.jsonl}
 * (compression "none") is also supported.
 */
public final class DshSessionLogReader {
    private static final int ZSTD_MAGIC = 0xFD2FB528; // little-endian frame magic
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DshSessionLogReader() {}

    /** Reads all decodable events from {@code session.jsonl} or {@code session.jsonl.zstd}. */
    public static List<JsonNode> read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        String text = name.endsWith(".zstd") ? decodeZstdContainer(bytes) : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.stripTrailing();
            if (trimmed.isEmpty()) continue;
            events.add(MAPPER.readTree(trimmed));
        }
        return events;
    }

    /** Decodes a concatenated-complete-frames zstd container, ignoring any torn tail. */
    static String decodeZstdContainer(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        StringBuilder out = new StringBuilder();
        for (int[] frame : scanFrames(buffer)) {
            byte[] slice = java.util.Arrays.copyOfRange(bytes, frame[0], frame[1]);
            out.append(new String(decompressFrame(slice), java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Parses one session-log JSON line. */
    static JsonNode parseEvent(String line) throws IOException {
        return MAPPER.readTree(line);
    }

    /** Decompresses one complete frame (shared with the incremental watcher). */
    static byte[] decompressFrame(byte[] frame) throws IOException {
        try (var in = new com.github.luben.zstd.ZstdInputStream(new ByteArrayInputStream(frame))) {
            in.setContinuous(false); // one frame per slice; do not read past its boundary
            return in.readAllBytes();
        }
    }

    /**
     * Structurally scans complete zstd frames. Ported from DSH's session
     * persistence: header fields (descriptor/content-size/dictionary/checksum)
     * and 3-byte block headers are walked without touching block payloads.
     */
    static List<int[]> scanFrames(ByteBuffer b) {
        List<int[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < b.limit()) {
            int start = offset;
            if (b.limit() - offset < 4) return frames; // torn tail
            if (b.getInt(offset) != ZSTD_MAGIC)
                throw new IllegalArgumentException("corrupt session log: invalid frame magic at byte " + offset);
            offset += 4;
            if (offset >= b.limit()) return frames;
            int descriptor = b.get(offset) & 0xFF;
            offset += 1;
            int contentSizeFlag = descriptor >>> 6;
            boolean singleSegment = (descriptor & 32) != 0;
            boolean checksum = (descriptor & 4) != 0;
            int dictionaryFlag = descriptor & 3;
            int dictionaryBytes = dictionaryFlag == 3 ? 4 : dictionaryFlag;
            int contentSizeBytes = contentSizeFlag == 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag;
            offset += (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
            for (;;) {
                if (b.limit() - offset < 3) return frames; // torn tail
                int blockHeader = (b.get(offset) & 0xFF) | ((b.get(offset + 1) & 0xFF) << 8) | ((b.get(offset + 2) & 0xFF) << 16);
                offset += 3;
                boolean lastBlock = (blockHeader & 1) != 0;
                int blockType = (blockHeader >>> 1) & 3;
                int blockSize = blockHeader >>> 3;
                int payloadBytes = blockType == 1 ? 1 : blockSize; // RLE blocks encode 1 byte
                if (b.limit() - offset < payloadBytes) return frames; // torn tail
                offset += payloadBytes;
                if (lastBlock) break;
            }
            if (checksum) offset += 4;
            frames.add(new int[]{start, offset});
        }
        return frames;
    }
}
