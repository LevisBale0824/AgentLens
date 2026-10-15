package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI-compatible LLM judge (ROADMAP P2-1). Sends the task instruction plus a
 * bounded, deterministic trace digest to any /v1/chat/completions endpoint
 * (Ollama, vLLM, DeepSeek API, ...) and requires a strict JSON verdict:
 *
 * <pre>{"score": 0.0-1.0, "passed": true|false, "reason": "...", "evidence": ["..."]}</pre>
 *
 * <p>Reliability contract:
 * <ul>
 *   <li><b>Schema validation</b> - score must be a number in [0,1]; reason must
 *       be non-blank; a missing {@code passed} field is derived from
 *       {@code score >= 0.7}. Violations count as a failed attempt.</li>
 *   <li><b>Retry</b> - up to {@code maxAttempts} endpoint calls per judgment
 *       (invalid JSON and schema violations both retry).</li>
 *   <li><b>Cache</b> - keyed by SHA-256(model | promptVersion | criterion |
 *       trace digest), so identical traces never re-bill: k-repeats and
 *       re-evaluations are free and stable.</li>
 *   <li><b>Version recording</b> - every result's metadata carries the judge
 *       model, prompt version and criterion, keeping judgments auditable.</li>
 * </ul>
 *
 * <p>Fail-open behavior (not blocking gates on judge infrastructure problems)
 * is implemented by {@link LlmJudgeEvaluator}, the registry-facing wrapper.
 */
public final class OpenAiJudge implements LlmJudge {
    /** Bumped whenever the judge prompt changes; part of the cache key. */
    public static final String PROMPT_VERSION = "v1";
    /** Score at or above this value counts as passed when "passed" is absent. */
    static final double DEFAULT_PASS_THRESHOLD = 0.7;
    private static final int MAX_DIGEST_EVENTS = 40;
    private static final int MAX_ATTR_VALUE = 120;
    private static final int MAX_DIGEST_CHARS = 6000;

    private final HttpClient client;
    private final URI baseUrl;
    private final String model;
    private final String apiKey;
    private final double temperature;
    private final Duration timeout;
    private final int maxAttempts;
    private final boolean jsonMode;
    private final boolean ollamaNative;
    private final Map<String, EvaluationResult> cache = new ConcurrentHashMap<>();
    private final AtomicLong endpointCalls = new AtomicLong();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OpenAiJudge(URI baseUrl, String model) {
        this(baseUrl, model, null, 0.0, Duration.ofSeconds(120), 3);
    }

    public OpenAiJudge(URI baseUrl, String model, String apiKey, double temperature,
                       Duration timeout, int maxAttempts) {
        this(baseUrl, model, apiKey, temperature, timeout, maxAttempts, false, false);
    }

    /**
     * @param jsonMode     sends {@code response_format: {"type":"json_object"}} -
     *                     constrains output where the endpoint supports it (OpenAI, vLLM);
     *                     some Ollama compat layers accept but ignore the field.
     * @param ollamaNative uses Ollama's native {@code POST /api/chat} with
     *                     {@code format: "json"} - a grammar-level guarantee of valid JSON.
     */
    public OpenAiJudge(URI baseUrl, String model, String apiKey, double temperature,
                       Duration timeout, int maxAttempts, boolean jsonMode, boolean ollamaNative) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.jsonMode = jsonMode;
        this.ollamaNative = ollamaNative;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Number of endpoint calls actually issued (cache misses); observability + tests. */
    public long endpointCalls() { return endpointCalls.get(); }

    @Override
    public EvaluationResult judge(AgentTask task, AgentRun run, String criterion) throws Exception {
        String digest = traceDigest(task, run);
        String key = cacheKey(model, PROMPT_VERSION, criterion, digest);
        EvaluationResult cached = cache.get(key);
        if (cached != null) return cached;

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                EvaluationResult result = callEndpoint(task, run, criterion, digest, attempt);
                cache.put(key, result);
                return result;
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("judge failed after " + maxAttempts + " attempts: "
                + lastError, lastError);
    }

    private EvaluationResult callEndpoint(AgentTask task, AgentRun run, String criterion,
                                          String digest, int attempt) throws Exception {
        endpointCalls.incrementAndGet();
        // Retry at a higher temperature: at temperature 0 a malformed answer would
        // otherwise reproduce deterministically and no retry could ever succeed.
        double attemptTemperature = Math.min(1.0, temperature + 0.3 * (attempt - 1));
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", userPrompt(task, criterion, digest)));
        String content = chat(messages, jsonMode, attemptTemperature);
        return toResult(criterion, parseVerdict(content));
    }

    /**
     * P1-B: free-form review call (gate review, narrative summaries) - plain
     * text in, plain text out, retried like a judgment but without the JSON
     * verdict schema. Callers fail open on exceptions.
     */
    @Override
    public String review(String prompt) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                endpointCalls.incrementAndGet();
                double attemptTemperature = Math.min(1.0, temperature + 0.3 * (attempt - 1));
                List<Map<String, Object>> messages = List.of(
                        Map.of("role", "system", "content", REVIEW_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", prompt));
                return chat(messages, false, attemptTemperature);
            } catch (Exception e) {
                lastError = e;
            }
        }
        throw new IllegalStateException("judge review failed after " + maxAttempts + " attempts: "
                + lastError, lastError);
    }

    private static final String REVIEW_SYSTEM_PROMPT = """
            You are a release gate reviewer for AI agent evaluations. You receive the \
            gate verdict, aggregate pass^k metrics, per-case outcomes and an evidence \
            chain. Answer as concise plain-text prose (no JSON, no markdown headings).""";

    /**
     * One chat completion through the configured transport (OpenAI-compatible or
     * native Ollama); returns the assistant message content.
     *
     * @param useJson when true, asks for a strict JSON object (response_format /
     *                Ollama format:"json"); the review path passes false
     */
    private String chat(List<Map<String, Object>> messages, boolean useJson,
                        double attemptTemperature) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", attemptTemperature);
        if (jsonMode) payload.put("response_format", Map.of("type", "json_object"));

        String endpointPath = "/v1/chat/completions";
        if (ollamaNative) {
            // Native Ollama transport: format:"json" constrains generation at the
            // grammar level when a strict JSON verdict is requested (the
            // OpenAI-compat layer ignores response_format); plain reviews use the
            // same /api/chat endpoint without the format constraint.
            endpointPath = "/api/chat";
            payload.remove("response_format");
            payload.remove("temperature");
            payload.put("stream", false);
            if (useJson) payload.put("format", "json");
            payload.put("options", Map.of("temperature", attemptTemperature));
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(baseUrl.resolve(endpointPath))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
        if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("judge endpoint returned " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        return extractContent(response.body());
    }

    private String systemPrompt() {
        return """
                You are a strict, evidence-based evaluation judge for AI agent traces. \
                Score the trace against the given criterion. Respond with ONLY one JSON object, \
                no markdown fences, no extra text: \
                {"score": <0.0-1.0>, "passed": <true|false>, "reason": "<short paragraph>", \
                "evidence": ["<concrete trace fact>", "..."]} \
                Rules: valid JSON only - no trailing commas, no comments, no markdown; \
                passed=true only when the criterion is clearly satisfied; every evidence \
                entry must cite a concrete fact from the trace; score 0 when it is violated.""";
    }

    private String userPrompt(AgentTask task, String criterion, String digest) {
        return "# Criterion\n" + criterion + "\n\n# Task\n" + task.instruction()
                + "\n\n# Agent trace\n" + digest;
    }

    /** Parses the judge verdict; throws on invalid JSON or schema violations (retryable). */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseVerdict(String content) {
        String json = extractJsonObject(content);
        if (json == null) throw new IllegalStateException("no JSON object in judge response: "
                + abbreviate(content, 500));
        Map<String, Object> root;
        try {
            root = mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("judge response is not valid JSON: " + abbreviate(json, 500), e);
        }
        Object scoreValue = root.get("score");
        if (!(scoreValue instanceof Number scoreNumber)
                || scoreNumber.doubleValue() < 0.0 || scoreNumber.doubleValue() > 1.0) {
            throw new IllegalStateException("judge score missing or out of range: " + scoreValue);
        }
        double score = scoreNumber.doubleValue();
        Object passedValue = root.get("passed");
        boolean passed = passedValue instanceof Boolean b ? b : score >= DEFAULT_PASS_THRESHOLD;
        Object reason = root.get("reason");
        if (!(reason instanceof String reasonText) || reasonText.isBlank()) {
            throw new IllegalStateException("judge reason missing or blank");
        }
        Map<String, Object> verdict = new HashMap<>();
        verdict.put("score", score);
        verdict.put("passed", passed);
        verdict.put("reason", reasonText.trim());
        Object evidence = root.get("evidence");
        if (evidence instanceof List<?> list) {
            verdict.put("evidence", list.stream().map(String::valueOf).limit(5).toList());
        }
        return verdict;
    }

    @SuppressWarnings("unchecked")
    private EvaluationResult toResult(String criterion, Map<String, Object> verdict) {
        List<String> evidence = (List<String>) verdict.getOrDefault("evidence", List.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("judge.model", model);
        metadata.put("judge.promptVersion", PROMPT_VERSION);
        metadata.put("judge.criterion", criterion);
        if (!evidence.isEmpty()) metadata.put("judge.evidence", evidence);
        return new EvaluationResult("llm-judge:" + criterion,
                (Boolean) verdict.get("passed"), (Double) verdict.get("score"),
                (String) verdict.get("reason"), metadata);
    }

    /**
     * Extracts the first balanced JSON object from a response, tolerating markdown
     * fences and surrounding prose; returns null when none is present.
     */
    static String extractJsonObject(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return content.substring(start, i + 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String responseBody) throws Exception {
        Map<String, Object> parsed = mapper.readValue(responseBody, Map.class);
        if (ollamaNative) {
            Map<String, Object> message = (Map<String, Object>) parsed.get("message");
            String content = message == null ? null : (String) message.get("content");
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("ollama response has empty content");
            }
            return content;
        }
        Object choices = parsed.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("judge response has no choices");
        }
        Map<String, Object> choice = (Map<String, Object>) list.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String content = message == null ? null : (String) message.get("content");
        if (content == null || content.isBlank()) throw new IllegalStateException("judge response has empty content");
        return content;
    }

    /**
     * Deterministic, bounded trace serialization - the judge's view of the run and
     * the cache key basis: task instruction, final status/answer, then one line per
     * event with sorted attributes (values truncated, event count capped).
     *
     * <p>Every embedded value is {@linkplain #sanitize sanitized}: the digest is
     * prompt text, not data. Raw quotes, backslashes or braces in attribute values
     * (e.g. a tool's JSON arguments) would otherwise be copied verbatim into the
     * model's JSON verdict strings and break their escaping - the root cause of
     * most small-model judge parse failures.
     */
    static String traceDigest(AgentTask task, AgentRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("task: ").append(task.id()).append(" | ").append(sanitize(task.instruction())).append('\n');
        sb.append("status: ").append(run.status()).append('\n');
        sb.append("finalAnswer: ").append(run.finalAnswer() == null ? "(none)" : sanitize(run.finalAnswer())).append('\n');
        sb.append("events:\n");
        int count = 0;
        for (AgentEvent event : run.events()) {
            if (count++ >= MAX_DIGEST_EVENTS) {
                sb.append("... (").append(run.events().size() - MAX_DIGEST_EVENTS)
                        .append(" more events omitted)\n");
                break;
            }
            Map<String, String> attrs = new TreeMap<>();
            event.attributes().forEach((k, v) -> attrs.put(k, sanitize(String.valueOf(v))));
            String attrText = attrs.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            sb.append(event.type()).append(' ').append(event.name()).append(' ').append(attrText).append('\n');
            if (sb.length() > MAX_DIGEST_CHARS) {
                sb.append("... (digest truncated)\n");
                break;
            }
        }
        return sb.toString();
    }

    /** Replaces JSON-hostile characters so quoted digest text cannot break verdict escaping. */
    private static String sanitize(String s) {
        if (s == null) return "";
        return abbreviate(s)
                .replace("\\", "/")
                .replace("\"", "'")
                .replace("{", "(")
                .replace("}", ")")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
    }

    private static String cacheKey(String model, String promptVersion, String criterion, String digest) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((model + '\n' + promptVersion + '\n' + criterion + '\n' + digest)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String abbreviate(String s) {
        return abbreviate(s, MAX_ATTR_VALUE);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
