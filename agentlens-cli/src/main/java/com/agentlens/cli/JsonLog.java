package com.agentlens.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal NDJSON structured logger (ROADMAP P2-5): one JSON object per line on
 * the sink (stderr), every line carrying ts / level / event / requestId plus
 * key-value pairs. Enabled with {@code --log-json}; without it the logger is a
 * no-op so the human console output stays untouched. The request id itself is
 * always generated and echoed in the human header, so a JSON line can always
 * be correlated back to the invocation that produced it.
 *
 * <p>Never throws: logging must not break the command it observes.
 */
public final class JsonLog {
    private static final JsonLog DISABLED = new JsonLog(null, "disabled");

    private final PrintStream sink;
    private final String requestId;
    private final ObjectMapper json = new ObjectMapper();

    private JsonLog(PrintStream sink, String requestId) {
        this.sink = sink;
        this.requestId = requestId;
    }

    public static JsonLog disabled() { return DISABLED; }

    public static JsonLog to(PrintStream sink, String requestId) {
        return new JsonLog(sink, requestId);
    }

    /** Short per-invocation correlation id, e.g. "req-1f2e3d4c". */
    public static String newRequestId() {
        return "req-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String requestId() { return requestId; }

    /** Emits one structured line; {@code kv} is key1, value1, key2, value2, ... */
    public synchronized void event(String level, String event, Object... kv) {
        if (sink == null) return;
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ts", Instant.now().toString());
            line.put("level", level);
            line.put("event", event);
            line.put("requestId", requestId);
            for (int i = 0; i + 1 < kv.length; i += 2) line.put(String.valueOf(kv[i]), kv[i + 1]);
            sink.println(json.writeValueAsString(line));
        } catch (Exception ignored) {
            // logging must never break the command it observes
        }
    }
}
