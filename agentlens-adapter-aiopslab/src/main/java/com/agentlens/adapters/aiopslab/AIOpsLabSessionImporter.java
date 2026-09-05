package com.agentlens.adapters.aiopslab;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Imports a real AIOpsLab session JSON ({@code results/<session_id>_<ts>.json})
 * onto the unified AgentLens trace (ROADMAP P2-2 "task/trace adapter").
 *
 * <p>AIOpsLab drives the agent through the Agent-Cloud Interface: the agent
 * answers with one API call per turn in a markdown code block
 * (```
 * exec_shell("kubectl get pods")
 * ```
 * ), the environment executes it and replies, and the agent finishes with
 * {@code submit(...)}. The session file persists:
 *
 * <pre>
 * {"agent": "...", "session_id": "...", "problem_id": "k8s_...-localization-1",
 *  "start_time": 1788..., "end_time": 1788...,
 *  "trace": [{"role": "assistant"|"env", "content": "..."}],
 *  "results": {"TTD": 12.3, "steps": 5, "in_tokens": ..., "out_tokens": ...}}
 * </pre>
 *
 * <p>Mapping onto AgentLens events:
 * <ul>
 *   <li>assistant message with a parseable {@code api_name(...)} →
 *       {@code TOOL_CALL} ({@code callId="aci-<i>"}, arguments=raw code line);
 *       a {@code submit(...)} additionally produces the run's FINAL_ANSWER</li>
 *   <li>assistant message without a parseable action → {@code MODEL_CALL}
 *       (the agent produced reasoning but no valid ACI action)</li>
 *   <li>env message → {@code TOOL_RESULT} for the last issued call
 *       ({@code status=error} when the reply is an ACI error)</li>
 * </ul>
 *
 * <p>Run status is SUCCESS only when AIOpsLab's evaluator actually ran
 * (results carry TTD/TTL/TTA/TTM or success) and the agent submitted a
 * solution.
 */
public final class AIOpsLabSessionImporter {
    /** ACI error prefixes observed in env replies when an action cannot run. */
    private static final String[] ERROR_MARKERS = {
            "Error:", "InvalidActionError", "Format validation failure",
            "An error occurred", "Error when parsing", "Error parsing"
    };

    private final ObjectMapper mapper = new ObjectMapper();

    /** Import result: the unified run plus selected session metadata. */
    public record Imported(AgentRun run, String agentName, String problemId,
                           JsonNode results) {}

    public Imported importSession(Path sessionJson) throws IOException {
        JsonNode root = mapper.readTree(sessionJson.toFile());
        return importSession(root);
    }

    public Imported importSession(JsonNode root) {
        String sessionId = text(root, "session_id");
        String problemId = text(root, "problem_id");
        String agentName = text(root, "agent");
        JsonNode trace = root.get("trace");
        JsonNode results = root.get("results");

        List<AgentEvent> events = new ArrayList<>();
        String solution = null;
        String lastApi = null;
        String lastCallId = null;
        int eventCount = trace == null ? 0 : trace.size();

        for (int i = 0; i < eventCount; i++) {
            JsonNode item = trace.get(i);
            String role = text(item, "role");
            String content = text(item, "content");
            if (content == null) content = "";
            if ("assistant".equals(role)) {
                String action = extractAction(content);
                String api = apiName(action);
                if (api == null) {
                    events.add(new AgentEvent(EventType.MODEL_CALL, "aiopslab.agent",
                            Map.of("content", truncate(content)), null, 0));
                    continue;
                }
                String callId = "aci-" + i;
                lastApi = api;
                lastCallId = callId;
                events.add(new AgentEvent(EventType.TOOL_CALL, api,
                        Map.of("callId", callId, "arguments", truncate(action)), null, 0));
                if ("submit".equals(api)) {
                    solution = actionArguments(action);
                }
            } else if ("env".equals(role)) {
                String api = lastApi != null ? lastApi : "aiopslab.env";
                String callId = lastCallId != null ? lastCallId : "aci-" + i;
                events.add(new AgentEvent(EventType.TOOL_RESULT, api,
                        Map.of("callId", callId, "status", isError(content) ? "error" : "ok",
                                "content", truncate(content)),
                        null, 0));
            }
        }
        if (solution != null) {
            events.add(new AgentEvent(EventType.FINAL_ANSWER, "aiopslab.submit", null, null, 0));
        }

        boolean evaluated = evaluated(results);
        RunStatus status = solution != null && evaluated ? RunStatus.SUCCESS : RunStatus.FAILED;
        double start = root.has("start_time") && root.get("start_time").isNumber()
                ? root.get("start_time").asDouble() : 0;
        double end = root.has("end_time") && root.get("end_time").isNumber()
                ? root.get("end_time").asDouble() : start;
        long durationMs = (long) Math.max(0, (end - start) * 1000);
        int tokens = 0;
        if (results != null) {
            tokens = (int) Math.min(Integer.MAX_VALUE,
                    numberOr(results.get("in_tokens")) + numberOr(results.get("out_tokens")));
        }

        String runId = sessionId != null ? sessionId : "aiopslab-" + System.currentTimeMillis();
        AgentRun run = new AgentRun(runId, problemId != null ? problemId : "aiopslab-imported",
                status, solution, events,
                start > 0 ? Instant.ofEpochMilli((long) (start * 1000)) : null,
                durationMs, tokens, null);
        return new Imported(run, agentName, problemId, results);
    }

    /** True when AIOpsLab's evaluator produced results (TTD/TTL/TTA/TTM or success flag). */
    public static boolean evaluated(JsonNode results) {
        if (results == null || !results.isObject()) return false;
        for (String key : new String[]{"TTD", "TTL", "TTA", "TTM", "success"}) {
            if (results.has(key)) return true;
        }
        return false;
    }

    /**
     * Extracts the ACI action from an assistant message: the first markdown code
     * block, matching AIOpsLab's ResponseParser convention (exactly one fenced
     * block per reply, containing {@code api_name(args)}).
     */
    static String extractAction(String content) {
        if (content == null) return null;
        String[] lines = content.split("\n", -1);
        int open = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("```")) {
                if (open < 0) open = i;
                else return String.join("\n", java.util.Arrays.copyOfRange(lines, open + 1, i)).trim();
            }
        }
        return null;
    }

    /** API name of an action line ("exec_shell(\"ls\")" → "exec_shell"), null when absent. */
    static String apiName(String action) {
        if (action == null) return null;
        int paren = action.indexOf('(');
        if (paren <= 0) return null;
        String name = action.substring(0, paren).trim();
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? name : null;
    }

    /** Raw argument text of an action line, quotes stripped (for submit solutions). */
    static String actionArguments(String action) {
        if (action == null) return null;
        int open = action.indexOf('(');
        int close = action.lastIndexOf(')');
        if (open < 0 || close <= open) return action;
        String args = action.substring(open + 1, close).trim();
        if (args.length() >= 2
                && ((args.startsWith("\"") && args.endsWith("\""))
                || (args.startsWith("'") && args.endsWith("'")))) {
            args = args.substring(1, args.length() - 1);
        }
        return args;
    }

    private static boolean isError(String content) {
        if (content == null) return false;
        String trimmed = content.stripLeading();
        for (String marker : ERROR_MARKERS) {
            if (trimmed.startsWith(marker) || trimmed.contains(marker)) return true;
        }
        return false;
    }

    private static long numberOr(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : 0;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
