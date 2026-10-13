package com.agentlens.adapters.dsh;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tails a DSH session log ({@code session.jsonl.zstd} or plain {@code session.jsonl})
 * and emits each newly durable event as it appears.
 *
 * <p>DSH appends the log as a sequence of independently decodable zstd frames, one
 * per durable event batch; the frame in flight is structurally incomplete. The
 * watcher leverages {@link DshSessionLogReader#scanFrames}: on every poll it
 * rescans the grown file, decodes only frames beyond the last consumed offset,
 * and silently skips the torn final frame until it completes. Plain-text logs
 * are tailed by byte offset.
 *
 * <p>Polling (default 500ms) instead of watch-service: the log is rewritten in
 * place by an appending writer, and DSH's own durability cadence is batch-level
 * anyway, so sub-second latency is sufficient for online evaluation.
 */
public final class DshSessionLogWatcher implements AutoCloseable {
    private final Path file;
    private final long pollMillis;
    private final Consumer<JsonNode> onEvent;
    private final Consumer<Throwable> onError;

    private long consumedBytes;
    private int consumedFrames;
    private long plainTextOffset;
    private boolean plainText;
    private Thread thread;
    private volatile boolean running;

    private DshSessionLogWatcher(Path file, long pollMillis,
                                 Consumer<JsonNode> onEvent, Consumer<Throwable> onError) {
        this.file = file;
        this.pollMillis = pollMillis;
        this.onEvent = onEvent;
        this.onError = onError;
        Path fileName = file.getFileName();
        this.plainText = fileName == null || !fileName.toString().endsWith(".zstd");
    }

    /** Creates a watcher; call {@link #start()} to begin polling. */
    public static DshSessionLogWatcher watch(Path file, Consumer<JsonNode> onEvent) {
        return watch(file, 500, onEvent, t -> {});
    }

    public static DshSessionLogWatcher watch(Path file, long pollMillis, Consumer<JsonNode> onEvent) {
        return watch(file, pollMillis, onEvent, t -> {});
    }

    public static DshSessionLogWatcher watch(Path file, long pollMillis,
                                             Consumer<JsonNode> onEvent, Consumer<Throwable> onError) {
        return new DshSessionLogWatcher(file, pollMillis, onEvent, onError);
    }

    /** Starts background polling. Events decoded so far are replayed first. */
    public synchronized DshSessionLogWatcher start() {
        if (thread != null) return this;
        running = true;
        thread = new Thread(this::loop, "dsh-log-watcher-" + file.getFileName());
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    @Override public synchronized void close() {
        running = false;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void loop() {
        while (running) {
            try {
                poll();
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                onError.accept(e);
                // A transient read of a torn file must not kill the watcher.
                try { Thread.sleep(pollMillis); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One poll cycle: decode and emit everything durable that is newer than our offsets. */
    synchronized void poll() throws IOException {
        if (!Files.isRegularFile(file)) return;
        if (plainText) {
            pollPlainText();
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < consumedBytes) { // truncated/rotated: reset and replay
            consumedBytes = 0;
            consumedFrames = 0;
        }
        List<int[]> frames = DshSessionLogReader.scanFrames(
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN));
        for (int i = consumedFrames; i < frames.size(); i++) {
            int[] range = frames.get(i);
            byte[] compressed = java.util.Arrays.copyOfRange(bytes, range[0], range[1]);
            emitLines(new String(DshSessionLogReader.decompressFrame(compressed),
                    java.nio.charset.StandardCharsets.UTF_8));
            consumedFrames = i + 1;
            consumedBytes = range[1];
        }
    }

    private void pollPlainText() throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < plainTextOffset) plainTextOffset = 0;
        if (bytes.length == plainTextOffset) return;
        String chunk = new String(java.util.Arrays.copyOfRange(bytes, (int) plainTextOffset, bytes.length),
                java.nio.charset.StandardCharsets.UTF_8);
        emitLines(chunk);
        plainTextOffset = bytes.length;
    }

    /** Emits complete JSON lines; buffers a trailing partial line until more data arrives. */
    private final StringBuilder lineBuffer = new StringBuilder();

    private void emitLines(String chunk) {
        lineBuffer.append(chunk);
        int start = 0;
        for (int nl; (nl = lineBuffer.indexOf("\n", start)) >= 0; start = nl + 1) {
            emitOne(lineBuffer.substring(start, nl));
        }
        if (start > 0) lineBuffer.delete(0, start);
    }

    private void emitOne(String line) {
        String trimmed = line.stripTrailing();
        if (trimmed.isEmpty()) return;
        try {
            onEvent.accept(DshSessionLogReader.parseEvent(trimmed));
        } catch (IOException e) {
            onError.accept(new IllegalStateException("unparsable session line: " + trimmed.substring(0,
                    Math.min(120, trimmed.length())), e));
        }
    }
}
