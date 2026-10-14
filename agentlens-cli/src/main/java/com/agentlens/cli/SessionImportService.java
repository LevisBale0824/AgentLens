package com.agentlens.cli;

import com.agentlens.adapters.aiopslab.AIOpsLabProblemCatalog;
import com.agentlens.adapters.aiopslab.AIOpsLabSessionImporter;
import com.agentlens.adapters.dsh.DshSessionLogReader;
import com.agentlens.adapters.dsh.DshTraceMapper;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.EvaluationRun;
import com.agentlens.eval.EvaluatorRegistry;
import com.agentlens.eval.RunLimitEvaluator;
import com.agentlens.eval.SqliteRunStore;
import com.agentlens.eval.TaskCompletionEvaluator;
import com.agentlens.eval.ToolPolicyEvaluator;
import com.agentlens.otel.OtlpIngestMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M1 of the UI history-analysis plan (docs/reports/ui-history-analysis-plan.md):
 * the single shared path for importing historical session JSON onto the
 * unified trace - detect the format, map it, run the deterministic evaluators,
 * and persist with an explicit provenance tag. Used by the dashboard
 * {@code POST /api/import} (source=ui-import) and the CLI {@code import}
 * command (source=cli-import) alike, so every historical analysis lands in the
 * same store the board reads.
 *
 * <p>Format detection (file name plus first-line sniffing):
 * <ul>
 *   <li>{@code *.zstd} - DSH session log (zstd frame container; the other
 *       formats are plain text)</li>
 *   <li>body containing {@code resourceSpans} - OTLP/JSON NDJSON, one payload
 *       per line, each payload possibly holding several traces</li>
 *   <li>a JSON object with {@code trace} plus {@code session_id}/
 *       {@code problem_id} - AIOpsLab results JSON</li>
 *   <li>JSONL lines carrying a {@code type} field - plain DSH session log</li>
 * </ul>
 *
 * <p>Conflict policy: when a run id already exists in the store the import
 * throws {@link RunExistsException} unless {@code overwrite} is set - a
 * silently duplicated history is worse than an explicit skip.
 */
public final class SessionImportService {

    /** Historical formats recognized by {@link #detect}. */
    public enum Kind { DSH_SESSION, OTLP_NDJSON, AIOPSLAB, UNKNOWN }

    /** Thrown when the run id is already in the store and overwrite was not requested. */
    public static final class RunExistsException extends RuntimeException {
        private RunExistsException(String runId) {
            super("run already exists: " + runId);
        }
    }

    /** One mapped + evaluated + persisted run. */
    public record ImportedRun(AgentRun run, String agentName, List<EvaluationResult> results) {
        public ImportedRun {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    /** Per-file result: what was recognized, what landed, what was skipped, and why it failed. */
    public record Outcome(String fileName, Kind kind, List<ImportedRun> runs,
                          List<String> skipped, String error) {
        public Outcome {
            runs = runs == null ? List.of() : List.copyOf(runs);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    /** Import knobs: provenance tag, optional DSH task-id override, conflict policy. */
    public record Options(String source, String taskId, boolean overwrite) {
        public Options {
            source = source == null || source.isBlank() ? "ui-import" : source;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() { };

    private final SqliteRunStore store;

    public SessionImportService(SqliteRunStore store) {
        this.store = store;
    }

    // ------------------------------------------------------------- detection

    /** Sniffs the historical format from the file name plus first-line content. */
    static Kind detect(String fileName, String text) {
        if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".zstd")) {
            return Kind.DSH_SESSION;
        }
        if (text.contains("resourceSpans")) {
            return Kind.OTLP_NDJSON;
        }
        String head = text.strip();
        int newline = head.indexOf('\n');
        head = newline < 0 ? head : head.substring(0, newline);
        JsonNode headNode = tryRead(head);
        if (headNode != null && headNode.isObject()) {
            if (headNode.has("trace") && (headNode.has("problem_id") || headNode.has("session_id"))) {
                return Kind.AIOPSLAB;
            }
            if (headNode.has("type")) {
                return Kind.DSH_SESSION;
            }
        }
        // Pretty-printed single-object JSON (e.g. AIOpsLab results) has a bare
        // "{" first line that fails head parsing - fall back to the whole text.
        JsonNode whole = tryRead(text);
        if (whole != null && whole.isObject() && whole.has("trace")
                && (whole.has("problem_id") || whole.has("session_id"))) {
            return Kind.AIOPSLAB;
        }
        return Kind.UNKNOWN;
    }

    private static JsonNode tryRead(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    // --------------------------------------------------- file orchestration

    /**
     * Imports one uploaded file: detect the format, dispatch to the matching
     * mapper, evaluate, persist, and report per-run outcomes. Never throws for
     * content problems - the outcome carries them so the UI can render them.
     */
    public Outcome importFile(String fileName, byte[] bytes, Options options) throws IOException {
        String name = fileName == null || fileName.isBlank() ? "upload" : fileName;
        Options opts = options == null ? new Options(null, null, false) : options;
        String text = utf8(bytes);
        Kind kind = detect(name, text);
        List<ImportedRun> runs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            switch (kind) {
                case DSH_SESSION -> {
                    try {
                        runs.add(importDshRun(decodeDsh(name, bytes), opts));
                    } catch (RunExistsException e) {
                        skipped.add(String.valueOf(e.getMessage()));
                    }
                }
                case OTLP_NDJSON -> importOtlpBody(text, opts, runs, skipped);
                case AIOPSLAB -> {
                    try {
                        runs.add(importAiopslabRun(
                                new AIOpsLabSessionImporter().importSession(JSON.readTree(text)), opts));
                    } catch (RunExistsException e) {
                        skipped.add(String.valueOf(e.getMessage()));
                    }
                }
                case UNKNOWN -> {
                    return new Outcome(name, kind, runs, skipped,
                            "unrecognized format: expected a DSH session JSONL(.zstd), "
                                    + "OTLP/JSON NDJSON, or AIOpsLab results JSON");
                }
            }
        } catch (RunExistsException e) {
            skipped.add(String.valueOf(e.getMessage()));
        } catch (Exception e) {
            return new Outcome(name, kind, runs, skipped, String.valueOf(e.getMessage()));
        }
        return new Outcome(name, kind, runs, skipped, null);
    }

    /** Decodes DSH session events; zstd containers go through a temp file. */
    private List<JsonNode> decodeDsh(String name, byte[] bytes) throws IOException {
        if (name.toLowerCase(Locale.ROOT).endsWith(".zstd")) {
            Path temp = Files.createTempFile("agentlens-import", ".session.jsonl.zstd");
            try {
                Files.write(temp, bytes);
                return DshSessionLogReader.read(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        List<JsonNode> events = new ArrayList<>();
        for (String line : utf8(bytes).split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                events.add(JSON.readTree(trimmed));
            }
        }
        return events;
    }

    /** UTF-8 decode that tolerates the Windows BOM many editors and shells write. */
    private static String utf8(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private void importOtlpBody(String ndjson, Options opts, List<ImportedRun> runs,
                                List<String> skipped) throws IOException {
        for (String line : ndjson.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, Object> payload;
            try {
                payload = JSON.readValue(trimmed, PAYLOAD);
            } catch (Exception e) {
                skipped.add("unparseable OTLP line: " + e.getMessage());
                continue;
            }
            for (AgentRun run : OtlpIngestMapper.fromOtlpJson(payload)) {
                try {
                    runs.add(importOtelRun(run, OtlpIngestMapper.serviceName(payload, "otel"),
                            opts.source(), opts.overwrite()));
                } catch (RunExistsException e) {
                    skipped.add(String.valueOf(e.getMessage()));
                }
            }
        }
    }

    // -------------------------------------------------- shared import paths

    /** DSH session events → unified trace → deterministic evaluation → persist. */
    public ImportedRun importDshRun(List<JsonNode> events, Options opts) throws IOException {
        boolean customTask = opts.taskId() != null && !opts.taskId().isBlank();
        DshTraceMapper.Mapped mapped = DshTraceMapper.toRun(events,
                customTask ? opts.taskId() : "imported");
        AgentRun run = mapped.run();
        if (!customTask) {
            String derived = "dsh-" + shortId(mapped.sessionId());
            if (!derived.equals(run.taskId())) {
                run = new AgentRun(run.runId(), derived, run.status(), run.finalAnswer(),
                        run.events(), run.startedAt(), run.durationMs(), run.totalTokens(),
                        run.error());
            }
        }
        guard(run.runId(), opts.overwrite());
        int steps = (int) run.events().stream().filter(e -> e.type() == EventType.TOOL_CALL).count();
        AgentTask task = new AgentTask(run.taskId(),
                mapped.instruction() == null ? "imported DSH session" : mapped.instruction(),
                Map.of(), steps + 10);
        List<EvaluationResult> results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .evaluate(task, run);
        persist(run, "dsh-session", "import", results, Map.of("source", opts.source()));
        return new ImportedRun(run, "dsh-session", results);
    }

    /**
     * OTLP ingest path shared by {@code otel-receive} and file import: the
     * synthetic task keeps evaluators honest without scenario context. The
     * receive server passes {@code overwrite=true} so OTLP client retries with
     * the same traceId upsert instead of being dropped.
     */
    public ImportedRun importOtelRun(AgentRun run, String agentName, String source,
                                     boolean overwrite) throws IOException {
        guard(run.runId(), overwrite);
        AgentTask task = new AgentTask(run.taskId(),
                "ingested OTLP trace (no scenario context)", Map.of(), 50);
        List<EvaluationResult> results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .evaluate(task, run);
        persist(run, agentName, "ingest", results,
                Map.of("ingest", "otlp", "source", source));
        return new ImportedRun(run, agentName, results);
    }

    /** AIOpsLab results JSON → ACI turns → deterministic evaluation incl. policy → persist. */
    public ImportedRun importAiopslabRun(AIOpsLabSessionImporter.Imported imported,
                                         Options opts) throws IOException {
        AgentRun run = imported.run();
        guard(run.runId(), opts.overwrite());
        String agentName = "aiopslab:" + imported.agentName();
        int steps = (int) run.events().stream().filter(e -> e.type() == EventType.TOOL_CALL).count();
        AgentTask task = new AgentTask(run.taskId(),
                "AIOpsLab problem " + run.taskId(), Map.of(), steps + 10);
        List<EvaluationResult> results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(
                        new HashSet<>(AIOpsLabProblemCatalog.ACI_ACTIONS), Set.of()))
                .evaluate(task, run);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("durationMs", run.durationMs());
        meta.put("totalTokens", run.totalTokens());
        if (imported.problemId() != null) {
            meta.put("problemId", imported.problemId());
        }
        meta.put("source", opts.source());
        persist(run, agentName, "imported", results, meta);
        return new ImportedRun(run, agentName, results);
    }

    // ------------------------------------------------------------- plumbing

    private void guard(String runId, boolean overwrite) throws IOException {
        if (!overwrite && store.loadRun(runId).isPresent()) {
            throw new RunExistsException(runId);
        }
    }

    private void persist(AgentRun run, String agentName, String agentVersion,
                         List<EvaluationResult> results, Map<String, Object> metadata)
            throws IOException {
        store.saveRun(run, agentName);
        store.saveEvaluation(new EvaluationRun(run.runId(), run.taskId(), agentName,
                agentVersion, run.startedAt(), results, metadata));
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "unknown";
        }
        String clean = id.replaceAll("[^A-Za-z0-9_-]", "");
        return clean.substring(0, Math.min(8, clean.length()));
    }
}
