package com.agentlens.adapters;

import com.agentlens.core.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Real-LLM agent adapter (ROADMAP V1.1): drives an OpenAI-compatible chat
 * completions endpoint (Ollama / vLLM / DeepSeek API) through a tool-calling
 * loop against the simulated ops environment. Every model turn and tool
 * execution lands in the trace as MODEL_CALL / TOOL_CALL / TOOL_RESULT events,
 * so the full evaluation platform (evaluators, pass^k, diagnosis, report)
 * works unchanged on real LLM behavior.
 */
public final class LlmAgentAdapter implements AgentAdapter {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI baseUrl;
    private final String model;
    private final double temperature;
    private final Duration timeout;
    private final ToolExecutor tools;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Executes a named tool of the simulated ops environment and returns its result text. */
    public interface ToolExecutor {
        String execute(String toolName, Map<String, Object> arguments);
    }

    public LlmAgentAdapter(URI baseUrl, String model, double temperature, Duration timeout, ToolExecutor tools) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        this.tools = tools;
    }

    @Override public String name() { return "llm"; }
    public String version() { return model; }

    @Override
    public AgentRun run(AgentTask task) {
        Instant started = Instant.now();
        String runId = UUID.randomUUID().toString();
        List<AgentEvent> events = new ArrayList<>();
        long totalTokens = 0;

        // tool catalog from the task context
        List<String> toolNames = stringList(task.context().get("allowedTools"));
        var catalog = tools instanceof SimulatedToolExecutor sim
                ? SimulatedToolExecutor.TOOL_DESCRIPTIONS : Map.<String, String>of();
        List<Map<String, Object>> toolSchemas = new ArrayList<>();
        for (String name : toolNames) toolSchemas.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", catalog.getOrDefault(name, "Ops tool: " + name),
                        "parameters", Map.of("type", "object", "properties", Map.of()))));

        // conversation state
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(task)));
        messages.add(Map.of("role", "user", "content", task.instruction()));

        try {
            for (int step = 0; step < task.maxSteps(); step++) {
                Instant callStart = Instant.now();
                String response = chatCompletion(messages, toolSchemas);
                long callMs = Duration.between(callStart, Instant.now()).toMillis();

                Map<String, Object> parsed = mapper.readValue(response, Map.class);
                Map<String, Object> choice = firstChoice(parsed);
                if (choice == null) {
                    events.add(new AgentEvent(EventType.ERROR, "llm.empty_choice",
                            Map.of("response", truncated(response)), callStart, callMs));
                    return new AgentRun(runId, task.id(), RunStatus.FAILED, null, events, started,
                            Duration.between(started, Instant.now()).toMillis(), (int) totalTokens, "empty choice from LLM");
                }
                totalTokens += tokens(parsed);
                Map<String, Object> message = castMap(choice.get("message"));
                String content = (String) message.getOrDefault("content", "");
                List<Map<String, Object>> toolCalls = castList(message.get("tool_calls"));

                events.add(new AgentEvent(EventType.MODEL_CALL, model,
                        Map.of("step", step, "promptTokens", promptTokens(parsed),
                                "completionTokens", completionTokens(parsed),
                                "content", truncated(String.valueOf(content))),
                        callStart, callMs));

                if (toolCalls == null || toolCalls.isEmpty()) {
                    // no tool requested -> treat content as the final answer
                    events.add(new AgentEvent(EventType.FINAL_ANSWER, "llm.answer",
                            Map.of(), Instant.now(), 0));
                    return new AgentRun(runId, task.id(), RunStatus.SUCCESS, content, events, started,
                            Duration.between(started, Instant.now()).toMillis(), (int) totalTokens, null);
                }

                // execute each requested tool against the simulated environment
                messages.add(new HashMap<>(message));
                for (Map<String, Object> call : toolCalls) {
                    Map<String, Object> function = castMap(call.get("function"));
                    String toolName = function == null ? null : (String) function.get("name");
                    String rawArgs = function == null ? "{}" : String.valueOf(function.getOrDefault("arguments", "{}"));
                    Map<String, Object> args = parseArgs(rawArgs);
                    Instant toolStart = Instant.now();
                    events.add(new AgentEvent(EventType.TOOL_CALL, toolName,
                            Map.of("step", step, "args", truncated(rawArgs)), toolStart, 0));
                    String result = tools.execute(toolName, args);
                    events.add(new AgentEvent(EventType.TOOL_RESULT, toolName,
                            Map.of("status", "ok", "result", truncated(result)), toolStart,
                            Duration.between(toolStart, Instant.now()).toMillis()));
                    messages.add(Map.of("role", "tool", "tool_call_id",
                            String.valueOf(call.getOrDefault("id", "")), "content", result));
                }
            }
            // step budget exhausted without a final answer
            return new AgentRun(runId, task.id(), RunStatus.FAILED, null, events, started,
                    Duration.between(started, Instant.now()).toMillis(), (int) totalTokens,
                    "no final answer within " + task.maxSteps() + " steps");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(runId, task, events, started, (int) totalTokens, e.getMessage());
        } catch (Exception e) {
            return failed(runId, task, events, started, (int) totalTokens, e.getMessage());
        }
    }

    private String systemPrompt(AgentTask task) {
        List<String> allowed = stringList(task.context().get("allowedTools"));
        List<String> forbidden = stringList(task.context().get("forbiddenTools"));
        return """
                You are an SRE agent diagnosing production incidents. Use ONLY the tools \
                listed below - calling any other tool name returns an error and counts as \
                a failed step. Investigate efficiently: call one tool at a time, use its \
                result to decide the next step, and do not repeat a call you already made. \
                When you have enough evidence (usually 1-3 tool calls), stop calling tools \
                and reply with a concise final answer naming the root cause and the \
                recommended remediation%s."""
                .formatted(forbidden.isEmpty() ? "" : ". NEVER call: " + forbidden)
                + (allowed.isEmpty() ? "" : "\nAvailable tools: " + String.join(", ", allowed));
    }

    private String chatCompletion(List<Map<String, Object>> messages, List<Map<String, Object>> tools)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        if (!tools.isEmpty()) payload.put("tools", tools);
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/v1/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("LLM endpoint returned " + response.statusCode()
                    + ": " + truncated(response.body()));
        }
        return response.body();
    }

    private AgentRun failed(String runId, AgentTask task, List<AgentEvent> events,
                            Instant started, long tokens, String error) {
        events.add(new AgentEvent(EventType.ERROR, "llm.error",
                Map.of("error", String.valueOf(error)), Instant.now(), 0));
        return new AgentRun(runId, task.id(), RunStatus.FAILED, null, events, started,
                Duration.between(started, Instant.now()).toMillis(), (int) tokens, error);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return o instanceof List<?> list ? (List<Map<String, Object>>) list : null;
    }

    private static Map<String, Object> firstChoice(Map<String, Object> parsed) {
        Object choices = parsed.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) return castMap(list.get(0));
        return null;
    }

    private static Map<String, Object> parseArgs(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of("_raw", raw);
        }
    }

    private static long tokens(Map<String, Object> parsed) {
        Map<String, Object> usage = castMap(parsed.get("usage"));
        if (usage == null) return 0;
        return ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue()
                + ((Number) usage.getOrDefault("completion_tokens", 0)).longValue();
    }

    private static long promptTokens(Map<String, Object> parsed) {
        Map<String, Object> usage = castMap(parsed.get("usage"));
        return usage == null ? 0 : ((Number) usage.getOrDefault("prompt_tokens", 0)).longValue();
    }

    private static long completionTokens(Map<String, Object> parsed) {
        Map<String, Object> usage = castMap(parsed.get("usage"));
        return usage == null ? 0 : ((Number) usage.getOrDefault("completion_tokens", 0)).longValue();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String truncated(String s) {
        return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) + "..." : s);
    }
}
