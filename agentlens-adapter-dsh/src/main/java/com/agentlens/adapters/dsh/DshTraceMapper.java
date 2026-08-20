package com.agentlens.adapters.dsh;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps raw DSH session-log events onto AgentLens's unified {@link AgentRun}
 * trace model. Event mapping (validated against real session logs):
 *
 * <pre>
 * session            -> run metadata (id, createdAt, cwd, preset)
 * request/context    -> MODEL_CALL event attributes (provider, model)
 * user/message       -> instruction recovery (first user text)
 * tool/call          -> TOOL_CALL   (name, callId, arguments)
 * tool/result        -> TOOL_RESULT (callId, status, content digest)
 * assistant/message  -> MODEL_CALL + final answer text (last one wins)
 * </pre>
 *
 * Streaming chunk events (assistant/chunk, text-chunks, ...) are ignored: the
 * assembled message events carry the same information once per step.
 */
public final class DshTraceMapper {
    private static final int MAX_ATTR_LENGTH = 200;

    private DshTraceMapper() {}

    /** Mapping result: the unified run plus recovered session metadata. */
    public record Mapped(AgentRun run, String instruction, String sessionId, String cwd, int turns) {}

    /** Maps decoded session-log events to an AgentRun under the given task id. */
    public static Mapped toRun(List<JsonNode> events, String taskId) {
        String sessionId = taskId;
        String cwd = null;
        long createdAt = System.currentTimeMillis();
        String firstUserText = null;
        String lastAssistantText = null;
        String provider = null;
        String model = null;

        Map<String, String> callNames = new HashMap<>();
        List<AgentEvent> mapped = new ArrayList<>();
        long lastTime = createdAt;
        int turns = 0;

        for (JsonNode node : events) {
            String type = text(node, "type");
            if (type == null) continue;
            JsonNode data = node.get("data");
            long time = node.has("time") && node.get("time").canConvertToLong()
                    ? node.get("time").asLong() : lastTime;
            lastTime = Math.max(lastTime, time);

            switch (type) {
                case "session" -> {
                    sessionId = text(node, "id");
                    cwd = text(node, "cwd");
                    if (node.has("createdAt") && node.get("createdAt").canConvertToLong()) {
                        createdAt = node.get("createdAt").asLong();
                        lastTime = createdAt; // session is always the first event
                    }
                }
                case "turn/start" -> turns++;
                case "request/context" -> {
                    if (data != null) {
                        provider = text(data, "provider");
                        model = text(data, "model");
                    }
                }
                case "user/message" -> {
                    if (firstUserText == null && data != null)
                        firstUserText = firstTextItem(data.get("content"));
                }
                case "tool/call" -> {
                    if (data == null) break;
                    String name = text(data, "name");
                    String callId = text(data, "callId");
                    if (callId != null && name != null) callNames.put(callId, name);
                    mapped.add(new AgentEvent(EventType.TOOL_CALL, name,
                            attrs("callId", callId, "arguments", truncate(text(data, "arguments"))),
                            Instant.ofEpochMilli(time), 0));
                }
                case "tool/result" -> {
                    if (data == null) break;
                    String callId = callIdOf(data);
                    String name = callNames.getOrDefault(callId, "dsh.tool");
                    boolean isError = containsErrorItem(data.get("message"));
                    mapped.add(new AgentEvent(EventType.TOOL_RESULT, name,
                            attrs("callId", callId, "status", isError ? "error" : "ok"),
                            Instant.ofEpochMilli(time), 0));
                }
                case "assistant/message" -> {
                    if (data == null) break;
                    JsonNode message = data.get("message");
                    String text = lastTextItem(message == null ? null : message.get("content"));
                    if (text != null) lastAssistantText = text;
                    mapped.add(new AgentEvent(EventType.MODEL_CALL, "dsh.model",
                            attrs("provider", provider, "model", model,
                                    "turn", intText(data, "turn"), "step", intText(data, "step")),
                            Instant.ofEpochMilli(time), 0));
                }
                default -> { /* streaming chunks, titles, permissions: not trace-worthy */ }
            }
        }

        if (lastAssistantText != null && !lastAssistantText.isBlank()) {
            mapped.add(new AgentEvent(EventType.FINAL_ANSWER, "dsh.answer",
                    Map.of(), Instant.ofEpochMilli(lastTime), 0));
        }
        RunStatus status = lastAssistantText != null ? RunStatus.SUCCESS : RunStatus.FAILED;
        long durationMs = Math.max(0, lastTime - createdAt);
        AgentRun run = new AgentRun(sessionId, taskId, status, lastAssistantText,
                List.copyOf(mapped), Instant.ofEpochMilli(createdAt), durationMs, 0,
                lastAssistantText == null ? "no assistant answer in session log" : null);
        return new Mapped(run, firstUserText, sessionId, cwd, turns);
    }

    private static String callIdOf(JsonNode data) {
        JsonNode message = data.get("message");
        if (message != null && message.has("source")) {
            String id = text(message.get("source"), "callId");
            if (id != null) return id;
        }
        return text(data, "callId");
    }

    private static String firstTextItem(JsonNode content) {
        if (content == null || !content.isArray()) return null;
        for (JsonNode item : content) {
            if ("text".equals(text(item, "type"))) return text(item, "text");
        }
        return null;
    }

    private static String lastTextItem(JsonNode content) {
        if (content == null || !content.isArray()) return null;
        String last = null;
        for (JsonNode item : content) {
            if ("text".equals(text(item, "type"))) last = text(item, "text");
        }
        return last;
    }

    private static boolean containsErrorItem(JsonNode message) {
        if (message == null) return false;
        JsonNode content = message.get("content");
        if (content == null || !content.isArray()) return false;
        for (JsonNode item : content) {
            String t = text(item, "type");
            if (t != null && t.contains("error")) return true;
        }
        return false;
    }

    private static Map<String, Object> attrs(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
            if (pairs[i + 1] != null) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String intText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || !v.canConvertToInt() ? null : String.valueOf(v.asInt());
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_ATTR_LENGTH ? value : value.substring(0, MAX_ATTR_LENGTH) + "...";
    }
}
