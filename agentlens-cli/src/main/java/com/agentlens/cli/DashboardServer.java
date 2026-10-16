package com.agentlens.cli;

import com.agentlens.core.AgentRun;
import com.agentlens.core.Diagnosis;
import com.agentlens.core.EvaluationResult;
import com.agentlens.eval.DiagnosisEngine;
import com.agentlens.eval.Scenario;
import com.agentlens.eval.SqliteRunStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ROADMAP P2-4 (mini web dashboard): serves the SQLite run store over a tiny
 * local HTTP API plus a single-file embedded frontend (vanilla JS, no build
 * step, no new dependencies - JDK HttpServer only).
 *
 * <pre>
 * GET /                  -> dashboard page (runs list + filters, pass^k layers,
 *                           trend bars, run detail with trace timeline,
 *                           evaluations, diagnosis evidence, version compare)
 * GET /api/summary       -> headline cards (runs, pass rate, stable/flaky/fail)
 * GET /api/runs          -> run summaries (filterable client-side)
 * GET /api/runs/{id}     -> full run: events timeline + evaluations + metadata
 *                           + diagnoses (computed live from the trace)
 * GET /api/scenarios     -> per-scenario pass^k layering
 * GET /api/diff          -> P0-C per-case baseline/candidate diff (pass/fail
 *                           flips highlighted, judge reason of the failing
 *                           side, row click drills into the trace)
 * POST /api/import       -> M1 of the UI history-analysis plan: upload a
 *                           historical session file as raw bytes (?name=,
 *                           ?overwrite=) - the format is sniffed (DSH session
 *                           JSONL(.zstd) / OTLP NDJSON / AIOpsLab JSON), the
 *                           trace is evaluated and persisted with
 *                           source=ui-import, so it shows up in the board
 *                           immediately.
 * GET /api/imports       -> M2: the import history log (file, kind, source,
 *                           outcome per row)
 * GET /api/artifacts     -> M2: calibration / compare / approved-baseline
 *                           artifacts found in output/ and baseline/ (or the
 *                           serve --artifacts list), with a sniffed kind
 * GET /api/artifacts/{id}-> M2: one artifact's parsed JSON content
 * POST /api/scenarios    -> M3: save a run as a dataset Scenario case
 *                           {runId, instruction?, expected?} - requires
 *                           serve --scenarios-out PATH (opt-in write path,
 *                           upserts by case id)
 * POST /api/runs/{id}/annotate -> M3: human review verdict {passed, note,
 *                           annotatedBy} for the review queue
 * GET /api/annotations   -> M3: every human annotation, newest first
 * </pre>
 *
 * The dashboard is read-only except the tagged write paths (M1 import, M3
 * scenario save + annotation) - imports never delete and never overwrite
 * without ?overwrite; scenario saving is disabled until --scenarios-out is
 * given.
 */
public final class DashboardServer implements AutoCloseable {
    private static final int MAX_IMPORT_BYTES = 64 * 1024 * 1024;

    private final SqliteRunStore store;
    private final SessionImportService importService;
    private final List<Path> artifactFiles;
    private final Path scenariosOut;
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    /** Scenario files are snake_case (ScenarioLoader reads them that way). */
    private final ObjectMapper scenarioJson = new ObjectMapper()
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    private final DiagnosisEngine diagnosisEngine = new DiagnosisEngine();

    public DashboardServer(SqliteRunStore store, int port) throws IOException {
        this(store, port, defaultArtifacts());
    }

    public DashboardServer(SqliteRunStore store, int port, List<Path> artifacts) throws IOException {
        this(store, port, artifacts, null);
    }

    public DashboardServer(SqliteRunStore store, int port, List<Path> artifacts,
                           Path scenariosOut) throws IOException {
        this.store = store;
        this.importService = new SessionImportService(store);
        this.artifactFiles = artifacts == null ? List.of() : List.copyOf(artifacts);
        this.scenariosOut = scenariosOut;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> respond(exchange, 200, "text/html; charset=utf-8", INDEX_HTML));
        server.createContext("/api/summary", exchange ->
                respondJson(exchange, 200, summary()));
        server.createContext("/api/runs", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/runs")) {
                respondJson(exchange, 200, runSummaries());
            } else if (path.startsWith("/api/runs/") && path.endsWith("/annotate")) {
                String id = path.substring("/api/runs/".length(),
                        path.length() - "/annotate".length());
                annotate(exchange, id);
            } else if (path.startsWith("/api/runs/") && path.endsWith("/scores")) {
                String id = path.substring("/api/runs/".length(),
                        path.length() - "/scores".length());
                var scores = runScores(id);
                respondJson(exchange, scores == null ? 404 : 200, scores == null
                        ? Map.of("error", "run not found: " + id) : scores);
            } else if (path.startsWith("/api/runs/")) {
                String id = path.substring("/api/runs/".length());
                var detail = runDetail(id);
                respondJson(exchange, detail == null ? 404 : 200, detail == null
                        ? Map.of("error", "run not found: " + id) : detail);
            } else {
                respondJson(exchange, 404, Map.of("error", "not found"));
            }
        });
        server.createContext("/api/scenarios", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                saveScenario(exchange);
                return;
            }
            respondJson(exchange, 200, scenarios());
        });
        server.createContext("/api/annotations", exchange ->
                respondJson(exchange, 200, Map.of("annotations", annotationList())));
        server.createContext("/api/diff", exchange -> {
            var query = parseQuery(exchange.getRequestURI().getRawQuery());
            respondJson(exchange, 200, diff(query.get("baseline"), query.get("candidate")));
        });
        server.createContext("/api/import", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondJson(exchange, 405, Map.of("error",
                        "POST the file as raw bytes to /api/import (?name=<file.name>)"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_IMPORT_BYTES + 1);
            if (body.length > MAX_IMPORT_BYTES) {
                respondJson(exchange, 413, Map.of("error", "file too large (limit is 64MB)"));
                return;
            }
            var query = parseQuery(exchange.getRequestURI().getRawQuery());
            var options = new SessionImportService.Options("ui-import", null,
                    "true".equalsIgnoreCase(query.getOrDefault("overwrite", "false")));
            var outcome = importService.importFile(query.getOrDefault("name", "upload"), body, options);
            respondJson(exchange, outcome.error() == null ? 200 : 400, importOutcomeMap(outcome));
        });
        server.createContext("/api/imports", exchange ->
                respondJson(exchange, 200, Map.of("imports", store.importHistory(50))));
        server.createContext("/api/artifacts", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/artifacts")) {
                respondJson(exchange, 200, artifactList());
            } else if (path.startsWith("/api/artifacts/")) {
                int id;
                try {
                    id = Integer.parseInt(path.substring("/api/artifacts/".length()));
                } catch (NumberFormatException e) {
                    respondJson(exchange, 404, Map.of("error", "no such artifact"));
                    return;
                }
                if (id < 0 || id >= artifactFiles.size()) {
                    respondJson(exchange, 404, Map.of("error", "no such artifact"));
                    return;
                }
                respondJson(exchange, 200, artifactContent(id));
            } else {
                respondJson(exchange, 404, Map.of("error", "not found"));
            }
        });
        server.start();
    }

    /** M2: known artifact locations scanned from the working directory. */
    static List<Path> defaultArtifacts() {
        List<Path> out = new ArrayList<>();
        for (String dir : new String[] {"output", "baseline"}) {
            try (var stream = Files.list(Path.of(dir))) {
                stream.filter(p -> String.valueOf(p.getFileName()).endsWith(".json")).sorted()
                        .forEach(out::add);
            } catch (IOException ignored) {
                // directory absent - nothing to scan there
            }
        }
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    private List<Map<String, Object>> artifactList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < artifactFiles.size(); i++) {
            Path file = artifactFiles.get(i);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", i);
            meta.put("name", String.valueOf(file.getFileName()));
            meta.put("dir", file.getParent() == null ? "." : String.valueOf(file.getParent()));
            meta.put("kind", artifactKind(file));
            try {
                meta.put("size", Files.size(file));
            } catch (IOException e) {
                meta.put("size", -1);
            }
            out.add(meta);
        }
        return out;
    }

    /** Sniffs which known artifact family a JSON file belongs to. */
    private String artifactKind(Path file) {
        try {
            JsonNode node = json.readTree(file.toFile());
            if (node == null || !node.isObject()) {
                return "generic";
            }
            if (node.has("casePassRates") && node.has("approvedBy")) {
                return "approved-baseline";
            }
            if (node.has("baseline") && node.has("candidate") && node.has("gate")) {
                return "compare";
            }
            if (node.has("samples") && node.get("samples").isArray()
                    && node.get("samples").size() > 0
                    && node.get("samples").get(0).has("expectedPass")) {
                return "calibration";
            }
            return "generic";
        } catch (Exception e) {
            return "generic";
        }
    }

    private Map<String, Object> artifactContent(int id) throws IOException {
        Path file = artifactFiles.get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", String.valueOf(file.getFileName()));
        String kind = artifactKind(file);
        out.put("kind", kind);
        try {
            out.put("content", json.readTree(file.toFile()));
        } catch (Exception e) {
            out.put("kind", "generic");
            out.put("content", Files.readString(file));
        }
        return out;
    }

    /** Base URL the server actually bound to (useful with port 0 in tests). */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- API

    /** @return null when the run id is unknown. */
    public Map<String, Object> runDetail(String runId) throws IOException {
        var entryOpt = allEntries().stream().filter(e -> e.getKey().runId().equals(runId)).findFirst();
        if (entryOpt.isEmpty()) return null;
        AgentRun run = entryOpt.get().getKey();
        Optional<com.agentlens.core.EvaluationRun> evaluation = entryOpt.get().getValue();
        var results = evaluation.map(com.agentlens.core.EvaluationRun::results).orElse(List.of());

        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < run.events().size(); i++) {
            var e = run.events().get(i);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("index", i);
            ev.put("type", e.type().name());
            ev.put("name", e.name());
            ev.put("durationMs", e.durationMs());
            ev.put("timestamp", e.timestamp().toString());
            ev.put("attributes", e.attributes());
            events.add(ev);
        }

        // Diagnoses are pure functions of (task, run, results) - computed live so
        // pre-diagnosis stores still show evidence. Task context (e.g. forbidden
        // tools) is not persisted, so policy-layer detail mirrors `show` limits.
        var task = new com.agentlens.core.AgentTask(run.taskId(),
                run.finalAnswer() == null ? "(imported run)" : run.finalAnswer(),
                Map.of(), (int) run.toolCallCount() + 10);
        List<Diagnosis> diagnoses = diagnosisEngine.diagnose(task, run, results);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", runSummary(entryOpt.get()));
        out.put("finalAnswer", run.finalAnswer());
        out.put("error", run.error());
        out.put("events", events);
        out.put("evaluation", evaluation.<Object>map(e -> Map.of(
                        "agent", e.agentName(),
                        "agentVersion", e.agentVersion(),
                        "results", e.results().stream().map(DashboardServer::resultMap).toList(),
                        "metadata", e.metadata()))
                .orElse(null));
        out.put("diagnoses", diagnoses.stream().map(DashboardServer::diagnosisMap).toList());
        out.put("annotation", store.loadAnnotation(runId)
                .<Object>map(DashboardServer::annotationMap).orElse(null));
        return out;
    }

    /** M3: human review verdict for one run (upsert). */
    /**
     * P1 分数三来源: evaluator (deterministic), judge (LLM) and human
     * (annotation) scores uniformly attached to one run, plus an agreement
     * verdict across every source that is actually present.
     */
    public Map<String, Object> runScores(String runId) throws IOException {
        var entryOpt = allEntries().stream().filter(e -> e.getKey().runId().equals(runId)).findFirst();
        if (entryOpt.isEmpty()) return null;
        List<EvaluationResult> results = entryOpt.get().getValue()
                .map(com.agentlens.core.EvaluationRun::results).orElse(List.of());
        List<Map<String, Object>> evaluatorRows = new ArrayList<>();
        List<Map<String, Object>> judgeRows = new ArrayList<>();
        for (EvaluationResult r : results) {
            (r.evaluator().startsWith("llm-judge") ? judgeRows : evaluatorRows)
                    .add(resultMap(r));
        }
        Boolean judgePassed = judgeRows.isEmpty() ? null
                : judgeRows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        boolean evaluatorPassed = !evaluatorRows.isEmpty() && evaluatorRows.stream()
                .allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        var annotation = store.loadAnnotation(runId).orElse(null);
        Map<String, Object> human = null;
        if (annotation != null) {
            human = annotationMap(annotation);
        }
        List<Boolean> present = new ArrayList<>();
        present.add(evaluatorPassed);
        if (judgePassed != null) present.add(judgePassed);
        if (annotation != null) present.add(annotation.passed());
        boolean allAgree = present.stream().allMatch(Boolean::booleanValue);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("evaluator", Map.of("passed", evaluatorPassed, "results", evaluatorRows));
        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("passed", judgePassed);
        judge.put("results", judgeRows);
        out.put("judge", judge);
        out.put("human", human);
        out.put("agreement", Map.of(
                "all", allAgree,
                "sources", present.size(),
                "disagree", present.size() > 1 && !allAgree));
        return out;
    }

    private void annotate(HttpExchange exchange, String runId) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, 405, Map.of("error",
                    "POST {\"passed\":true,\"note\":\"...\"} to /api/runs/{id}/annotate"));
            return;
        }
        if (store.loadRun(runId).isEmpty()) {
            respondJson(exchange, 404, Map.of("error", "run not found: " + runId));
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String note = stringOrNull(body.get("note"));
        String by = stringOrNull(body.get("annotatedBy"));
        boolean passed = Boolean.parseBoolean(String.valueOf(body.get("passed")));
        store.saveAnnotation(new SqliteRunStore.Annotation(runId, passed, note, by, null));
        respondJson(exchange, 200, Map.of("annotated", true, "runId", runId,
                "passed", passed, "note", note == null ? "" : note));
    }

    /** M3: every annotation, newest first, enriched with the run's task/status. */
    private List<Map<String, Object>> annotationList() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SqliteRunStore.Annotation a : store.annotationHistory()) {
            Map<String, Object> row = new LinkedHashMap<>(annotationMap(a));
            store.loadRun(a.runId()).ifPresent(run -> {
                row.put("taskId", run.taskId());
                row.put("status", run.status().name());
            });
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> annotationMap(SqliteRunStore.Annotation a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", a.runId());
        out.put("passed", a.passed());
        out.put("note", a.note());
        out.put("annotatedBy", a.annotatedBy());
        out.put("createdAt", a.createdAt());
        return out;
    }

    /**
     * M3: saves a run as a dataset Scenario case - instruction + golden answer
     * (expected root cause) + the tools the trace actually used. Upserts by
     * case id into the --scenarios-out file; disabled (400) unless the flag
     * was given at startup.
     */
    private void saveScenario(HttpExchange exchange) throws IOException {
        if (scenariosOut == null) {
            respondJson(exchange, 400, Map.of("error",
                    "scenario saving is disabled - start serve with --scenarios-out PATH"));
            return;
        }
        Map<String, Object> body = readJsonBody(exchange);
        String runId = stringOrNull(body.get("runId"));
        if (runId == null || store.loadRun(runId).isEmpty()) {
            respondJson(exchange, 404, Map.of("error", "run not found: " + runId));
            return;
        }
        AgentRun run = store.loadRun(runId).orElseThrow();
        String expected = stringOrNull(body.get("expected"));
        String instruction = stringOrNull(body.get("instruction"));
        List<String> tools = run.events().stream()
                .filter(e -> e.type() == com.agentlens.core.EventType.TOOL_CALL && e.name() != null)
                .map(com.agentlens.core.AgentEvent::name)
                .distinct()
                .toList();
        if (tools.isEmpty()) tools = List.of("none");
        String id = "board-" + run.taskId();
        // upsert merges: fields absent from the request keep their saved values
        Map<String, Object> existing = loadExistingCase(id);
        if (expected == null || expected.isBlank()) {
            expected = existing != null && existing.get("root_cause") != null
                    ? String.valueOf(existing.get("root_cause"))
                    : (run.finalAnswer() == null ? "(not captured)" : run.finalAnswer());
        }
        if (instruction == null || instruction.isBlank()) {
            instruction = existing != null && existing.get("instruction") != null
                    ? String.valueOf(existing.get("instruction"))
                    : "Diagnose " + run.taskId() + " (saved from board run " + runId + ")";
        }
        Scenario scenario = new Scenario(id, "board-saved", run.taskId(), List.of(),
                expected, tools, List.of(),
                List.of("final answer states the expected root cause"),
                Math.max(10, run.events().size()), "correct", "mock", null, instruction, null);
        int total = upsertScenario(scenario);
        respondJson(exchange, 200, Map.of("saved", true, "id", id,
                "file", scenariosOut.toString(), "total", total));
    }

    /** The saved case with this id, or null when the file lacks it. */
    private Map<String, Object> loadExistingCase(String id) throws IOException {
        if (scenariosOut == null || !Files.exists(scenariosOut)) return null;
        List<Map<String, Object>> cases = json.readValue(scenariosOut.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        return cases.stream().filter(c -> id.equals(c.get("id"))).findFirst().orElse(null);
    }

    /** Reads the scenarios file (if any), replaces/inserts by id, writes back. */
    private int upsertScenario(Scenario scenario) throws IOException {
        List<Map<String, Object>> cases = new ArrayList<>();
        if (Files.exists(scenariosOut)) {
            try {
                cases.addAll(json.readValue(scenariosOut.toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}));
            } catch (IOException e) {
                throw new IOException("existing scenarios file is not a JSON array: " + scenariosOut, e);
            }
        }
        Map<String, Object> asMap = json.readValue(
                scenarioJson.writerWithDefaultPrettyPrinter().writeValueAsString(scenario),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        cases.removeIf(c -> scenario.id().equals(c.get("id")));
        cases.add(asMap);
        Path parent = scenariosOut.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(scenariosOut,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(cases));
        return cases.size();
    }

    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        try {
            return json.readValue(exchange.getRequestBody(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    public List<Map<String, Object>> runSummaries() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var entry : allEntries()) out.add(runSummary(entry));
        return out;
    }

    public Map<String, Object> summary() throws IOException {
        var entries = allEntries();
        var scenarios = scenarios();
        long passed = entries.stream().filter(e -> passed(e.getValue())).count();
        long stablePass = scenarios.stream().filter(s -> "STABLE_PASS".equals(s.get("layer"))).count();
        long stableFail = scenarios.stream().filter(s -> "STABLE_FAIL".equals(s.get("layer"))).count();
        long flaky = scenarios.stream().filter(s -> "FLAKY".equals(s.get("layer"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runs", entries.size());
        out.put("passedRuns", passed);
        out.put("passRate", entries.isEmpty() ? 0.0 : (double) passed / entries.size());
        out.put("scenarios", scenarios.size());
        out.put("stablePass", stablePass);
        out.put("flaky", flaky);
        out.put("stableFail", stableFail);
        return out;
    }

    public List<Map<String, Object>> scenarios() throws IOException {
        var byTask = new LinkedHashMap<String, List<Map.Entry<AgentRun,
                Optional<com.agentlens.core.EvaluationRun>>>>();
        for (var entry : allEntries())
            byTask.computeIfAbsent(entry.getKey().taskId(), t -> new ArrayList<>()).add(entry);

        List<Map<String, Object>> out = new ArrayList<>();
        byTask.forEach((task, runs) -> {
            long passed = runs.stream().filter(e -> passed(e.getValue())).count();
            String layer = passed == runs.size() ? "STABLE_PASS"
                    : passed == 0 ? "STABLE_FAIL" : "FLAKY";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scenario", task);
            row.put("runs", runs.size());
            row.put("passed", passed);
            row.put("passRate", runs.isEmpty() ? 0.0 : (double) passed / runs.size());
            row.put("layer", layer);
            out.add(row);
        });
        return out;
    }

    /**
     * P0-C: per-case diff between two agent versions (baseline vs candidate).
     * Cases are aligned by taskId; a version's case outcome is its latest run.
     * Verdicts highlight pass/fail flips so a regression is one glance away.
     * With no explicit query params the two most recently run versions compare.
     */
    public Map<String, Object> diff(String baseline, String candidate) throws IOException {
        var entries = allEntries();
        // versions ordered by most recent activity
        var versions = new LinkedHashMap<String, java.time.Instant>();
        for (var entry : entries) {
            String version = entry.getValue().map(com.agentlens.core.EvaluationRun::agentVersion)
                    .orElse(null);
            if (version == null || version.isBlank()) continue;
            versions.merge(version, entry.getKey().startedAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        List<String> ordered = new ArrayList<>(versions.entrySet().stream()
                .map(Map.Entry::getKey)
                .toList());
        java.util.Collections.reverse(ordered);
        // stable sort on reversed insertion order: ties on startedAt go to the
        // version that was ingested later (the newer one)
        ordered.sort((a, b) -> versions.get(b).compareTo(versions.get(a)));
        if (baseline == null || baseline.isBlank()) baseline = ordered.size() > 1 ? ordered.get(1) : null;
        if (candidate == null || candidate.isBlank()) candidate = ordered.isEmpty() ? null : ordered.get(0);

        // one row per taskId seen on either side; per version keep the latest run
        var tasks = new java.util.TreeSet<String>();
        var latest = new LinkedHashMap<String, Map.Entry<AgentRun,
                Optional<com.agentlens.core.EvaluationRun>>>();
        for (var entry : entries) {
            String version = entry.getValue().map(com.agentlens.core.EvaluationRun::agentVersion)
                    .orElse(null);
            if (version == null) continue;
            String key = version + "\u0000" + entry.getKey().taskId();
            tasks.add(entry.getKey().taskId());
            latest.merge(key, entry, (a, b) ->
                    a.getKey().startedAt().isAfter(b.getKey().startedAt()) ? a : b);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        long flips = 0;
        for (String task : tasks) {
            var baseSide = latest.get((baseline == null ? "" : baseline) + "\u0000" + task);
            var candSide = latest.get((candidate == null ? "" : candidate) + "\u0000" + task);
            Boolean basePass = baseSide == null ? null : passed(baseSide.getValue());
            Boolean candPass = candSide == null ? null : passed(candSide.getValue());
            String verdict = basePass == null ? "MISSING_BASELINE"
                    : candPass == null ? "MISSING_CANDIDATE"
                    : basePass && candPass ? "SAME_PASS"
                    : !basePass && !candPass ? "SAME_FAIL"
                    : basePass ? "FLIP_TO_FAIL" : "FLIP_TO_PASS";
            if (verdict.startsWith("FLIP")) flips++;
            // judge reason: prefer the failing side's worst result, else the judge's own
            String reason = candSide != null && Boolean.FALSE.equals(candPass)
                    ? judgeReason(candSide.getValue())
                    : baseSide != null && Boolean.FALSE.equals(basePass)
                    ? judgeReason(baseSide.getValue()) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", task);
            row.put("verdict", verdict);
            row.put("baseline", baseSide == null ? null : side(baseSide));
            row.put("candidate", candSide == null ? null : side(candSide));
            row.put("judgeReason", reason);
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", baseline);
        out.put("candidate", candidate);
        out.put("versions", ordered);
        out.put("flips", flips);
        out.put("rows", rows);
        return out;
    }

    private static Map<String, Object> side(Map.Entry<AgentRun,
            Optional<com.agentlens.core.EvaluationRun>> entry) {
        AgentRun run = entry.getKey();
        Optional<com.agentlens.core.EvaluationRun> evaluation = entry.getValue();
        double score = evaluation.map(e -> e.results().stream()
                        .mapToDouble(EvaluationResult::score).average().orElse(0.0))
                .orElse(0.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("passed", passed(evaluation));
        out.put("score", Math.round(score * 1000) / 1000.0);
        out.put("durationMs", run.durationMs());
        return out;
    }

    /** Judge-facing reason: failing judge/deepeval-ish result first, then any failure. */
    private static String judgeReason(Optional<com.agentlens.core.EvaluationRun> evaluation) {
        if (evaluation.isEmpty()) return null;
        var results = evaluation.get().results();
        var judgeish = results.stream().filter(r -> {
            String name = r.evaluator().toLowerCase();
            return name.contains("judge") || name.contains("deepeval");
        }).toList();
        var failedJudge = judgeish.stream().filter(r -> !r.passed()).findFirst();
        if (failedJudge.isPresent()) return failedJudge.get().reason();
        if (!judgeish.isEmpty()) return judgeish.get(0).reason();
        return results.stream().filter(r -> !r.passed()).findFirst()
                .map(EvaluationResult::reason).orElse(null);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    // ------------------------------------------------------------ helpers

    private List<Map.Entry<AgentRun, Optional<com.agentlens.core.EvaluationRun>>> allEntries()
            throws IOException {
        return store.loadAll();
    }

    private static boolean passed(Optional<com.agentlens.core.EvaluationRun> evaluation) {
        return evaluation.isPresent()
                && !evaluation.get().results().isEmpty()
                && evaluation.get().results().stream().allMatch(EvaluationResult::passed);
    }

    private Map<String, Object> runSummary(Map.Entry<AgentRun,
            Optional<com.agentlens.core.EvaluationRun>> entry) throws IOException {
        AgentRun run = entry.getKey();
        Optional<com.agentlens.core.EvaluationRun> evaluation = entry.getValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("taskId", run.taskId());
        out.put("status", run.status().name());
        out.put("passed", passed(evaluation));
        out.put("durationMs", run.durationMs());
        out.put("totalTokens", run.totalTokens());
        out.put("toolCalls", run.toolCallCount());
        out.put("events", run.events().size());
        out.put("startedAt", run.startedAt().toString());
        out.put("agent", evaluation.<Object>map(com.agentlens.core.EvaluationRun::agentName).orElse(null));
        out.put("agentVersion", evaluation.<Object>map(com.agentlens.core.EvaluationRun::agentVersion).orElse(null));
        out.put("observedModel", evaluation.map(e -> e.metadata().get("observedModel")).orElse(null));
        out.put("source", evaluation
                .map(e -> String.valueOf(e.metadata().getOrDefault("source", "native")))
                .orElse("native"));
        return out;
    }

    /** Flattens an import outcome for the /api/import response body. */
    private static Map<String, Object> importOutcomeMap(SessionImportService.Outcome outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", outcome.fileName());
        out.put("kind", outcome.kind().name());
        out.put("imported", outcome.runs().stream().map(r -> {
            Map<String, Object> run = new LinkedHashMap<String, Object>();
            run.put("runId", r.run().runId());
            run.put("taskId", r.run().taskId());
            run.put("agent", r.agentName());
            run.put("status", r.run().status().name());
            run.put("passed", r.results().stream().allMatch(EvaluationResult::passed));
            return run;
        }).toList());
        out.put("skipped", outcome.skipped());
        if (outcome.error() != null) {
            out.put("error", outcome.error());
        }
        return out;
    }

    private static Map<String, Object> resultMap(EvaluationResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluator", r.evaluator());
        out.put("passed", r.passed());
        out.put("score", r.score());
        out.put("reason", r.reason());
        out.put("evidence", r.evidence());
        // scorer contract (P1): optional answer/explanation dimensions
        out.put("answer", r.answer());
        out.put("explanation", r.explanation());
        return out;
    }

    private static Map<String, Object> diagnosisMap(Diagnosis d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layer", d.layer());
        out.put("issue", d.issue());
        out.put("severity", d.severity());
        out.put("confidence", d.confidence());
        out.put("evidence", d.evidence());
        out.put("recommendation", d.recommendation());
        return out;
    }

    private void respondJson(com.sun.net.httpserver.HttpExchange exchange, int code, Object body)
            throws IOException {
        respond(exchange, code, "application/json; charset=utf-8", json.writeValueAsString(body));
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int code,
                         String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------ frontend

    private static final String INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="zh"><head><meta charset="utf-8">
            <title>AgentLens Dashboard</title>
            <style>
              :root{--bg:#0b101b;--panel:#131a2a;--line:#1f2a44;--text:#e6ebf5;--dim:#8ea0bf;
                    --ok:#34d399;--warn:#fbbf24;--bad:#f87171;--accent:#60a5fa}
              *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--text);
                    font:14px/1.6 "Segoe UI",system-ui,sans-serif}
              header{padding:18px 24px;border-bottom:1px solid var(--line)}
              h1{margin:0;font-size:18px} h1 span{color:var(--dim);font-weight:400;font-size:13px}
              main{padding:18px 24px;max-width:1200px;margin:0 auto}
              .cards{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:18px}
              .card{background:var(--panel);border:1px solid var(--line);border-radius:10px;
                    padding:12px 18px;min-width:110px}
              .card b{display:block;font-size:22px} .card span{color:var(--dim);font-size:12px}
              h2{font-size:14px;color:var(--dim);margin:22px 0 8px;text-transform:uppercase;letter-spacing:.06em}
              table{width:100%;border-collapse:collapse;background:var(--panel);
                    border:1px solid var(--line);border-radius:10px;overflow:hidden}
              th,td{padding:8px 12px;text-align:left;border-bottom:1px solid var(--line)}
              th{color:var(--dim);font-weight:600;font-size:12px}
              tr:last-child td{border-bottom:none} tbody tr{cursor:pointer} tbody tr:hover{background:#182138}
              .pill{padding:1px 9px;border-radius:99px;font-size:11px;font-weight:600}
              .ok{background:#0e3b2e;color:var(--ok)} .warn{background:#3d3317;color:var(--warn)}
              .bad{background:#432424;color:var(--bad)}
              input,select{background:#0b101b;color:var(--text);border:1px solid var(--line);
                    border-radius:8px;padding:6px 10px;margin-right:8px}
              .bars{display:flex;align-items:flex-end;gap:4px;height:110px;background:var(--panel);
                    border:1px solid var(--line);border-radius:10px;padding:12px}
              .bar{flex:1;max-width:26px;border-radius:3px 3px 0 0;background:var(--ok);min-height:4px}
              .bar.bad{background:var(--bad)} .bar:hover{opacity:.8}
              #detail{background:var(--panel);border:1px solid var(--line);border-radius:10px;
                    padding:14px 18px;margin-top:12px;display:none}
              .ev{font:12px/1.7 ui-monospace,Consolas,monospace;color:var(--dim);padding:3px 0;
                    border-bottom:1px dashed var(--line)}
              .ev b{color:var(--text)} .ev .t{color:var(--accent)}
              .diag{border-left:3px solid var(--warn);padding:6px 12px;margin:8px 0;background:#0b101b;border-radius:0 8px 8px 0}
              tr.flip-bad{background:#3a1d1d} tr.flip-good{background:#12331f}
              tr.flip-bad:hover{background:#4a2424} tr.flip-good:hover{background:#184229}
              code{background:#0b101b;padding:1px 6px;border-radius:4px;font-size:12px}
              .muted{color:var(--dim)}
              #drop{border:1.5px dashed var(--line);border-radius:10px;padding:16px;text-align:center;
                    color:var(--dim);cursor:pointer;margin-bottom:10px;transition:.2s}
              #drop.over{border-color:var(--accent);color:var(--accent);background:#12233f}
              .pill.src{background:#0e2e33;color:#2dd4bf}
              .imp{background:var(--panel);border:1px solid var(--line);border-left:3px solid var(--accent);
                   border-radius:8px;padding:8px 14px;margin:6px 0;font:12px ui-monospace,Consolas,monospace}
              .imp.bad{border-left-color:var(--bad)}
              .wf .lane{display:grid;grid-template-columns:180px 1fr;gap:10px;align-items:center;padding:2px 0}
              .wf .lbl{font:11px ui-monospace,Consolas,monospace;color:var(--dim);text-align:right;
                    white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
              .wf .lbl b{color:var(--text)}
              .wf .track{position:relative;height:16px;background:#0b101b;border-radius:4px;overflow:hidden}
              .wfbar{position:absolute;top:3px;height:10px;border-radius:3px;min-width:3px}
              .ev-MODEL_CALL{background:var(--accent)} .ev-TOOL_CALL{background:#2dd4bf}
              .ev-TOOL_RESULT{background:#33506b} .ev-FINAL_ANSWER{background:var(--ok)}
              .ev-ERROR{background:var(--bad)} .ev-PLAN{background:#a78bfa}
              .ev-RETRIEVAL{background:var(--warn)} .ev-MEMORY_READ,.ev-MEMORY_WRITE{background:#64748b}
              .axis{display:grid;grid-template-columns:180px 1fr;gap:10px}
              .axis .ticks{position:relative;height:14px;font:10px ui-monospace,Consolas,monospace;color:var(--dim)}
              .axis .ticks span{position:absolute;transform:translateX(-50%)}
              .legend-ev{display:flex;gap:12px;margin-top:8px;font:11px ui-monospace,Consolas,monospace;
                    color:var(--dim);flex-wrap:wrap}
              .legend-ev i{display:inline-block;width:9px;height:9px;border-radius:2px;margin-right:4px}
              .tgl{display:inline-flex;border:1px solid var(--line);border-radius:8px;overflow:hidden;
                    margin-left:10px;vertical-align:middle}
              .tgl button{background:none;border:none;color:var(--dim);font:11px ui-monospace,Consolas,monospace;
                    padding:3px 10px;cursor:pointer}
              .tgl button.on{background:#12233f;color:var(--accent)}
              .raw{background:#0b101b;border:1px solid var(--line);border-radius:8px;padding:10px;
                    font:11.5px ui-monospace,Consolas,monospace;overflow:auto;max-height:420px;
                    white-space:pre-wrap;word-break:break-all}
              details.rawwrap{margin-top:12px}
              details.rawwrap summary{cursor:pointer}
              </style></head><body>
            <header><h1>AgentLens Dashboard <span id="sub">loading…</span></h1></header>
            <main>
              <div class="cards" id="cards"></div>
              <h2>Runs trend</h2><div class="bars" id="trend"></div>
              <h2>Scenarios (pass^k layers)</h2>
              <table><thead><tr><th>scenario</th><th>runs</th><th>passed</th><th>pass rate</th><th>layer</th></tr></thead>
                <tbody id="scenarios"></tbody></table>
              <h2>Version diff (per-case)</h2>
              <div style="margin-bottom:8px">
                <select id="diffBase"></select>
                <span class="muted">→</span>
                <select id="diffCand"></select>
                <span id="diffSummary" class="muted"></span>
              </div>
              <table><thead><tr><th>task</th><th>baseline</th><th>candidate</th>
                <th>change</th><th>judge reason (failing side)</th></tr></thead>
                <tbody id="diff"></tbody></table>
              <h2>Import history <span class="pill src">M1</span></h2>
              <div id="drop">⇩ drag in session.jsonl(.zstd) / OTLP NDJSON / AIOpsLab JSON - or click to choose · ≤64MB each</div>
              <input type="file" id="fileInput" multiple style="display:none">
              <div id="importOut"></div>
              <table style="margin-top:14px"><thead><tr><th>time</th><th>file</th><th>kind</th><th>source</th>
                <th>imported</th><th>skipped</th><th>error</th></tr></thead>
                <tbody id="importLog"></tbody></table>
              <h2>Review queue <span class="pill src">M3</span></h2>
              <table><thead><tr><th>run</th><th>task</th><th>label</th><th>note</th><th>by</th><th>at</th></tr></thead>
                <tbody id="reviewLog"></tbody></table>
              <h2>Artifacts <span class="pill src">M2</span></h2>
              <div style="margin-bottom:8px">
                <select id="artifactSel"><option value="">choose artifact…</option></select>
                <span id="artifactMeta" class="muted"></span>
              </div>
              <div id="artifactView"></div>
              <h2>Runs</h2>
              <div style="margin-bottom:8px">
                <input id="q" placeholder="filter by task…">
                <select id="status"><option value="">status: all</option>
                  <option>SUCCESS</option><option>FAILED</option><option>TIMEOUT</option><option>CANCELLED</option></select>
                <select id="version"><option value="">version: all</option></select>
                <select id="source"><option value="">source: all</option>
                  <option>ui-import</option><option value="native">native</option></select>
                <label class="muted"><input type="checkbox" id="onlyFailed" style="margin-right:4px">only failures</label>
              </div>
              <table><thead><tr><th>started</th><th>task</th><th>source</th><th>status</th><th>eval</th>
                <th>version</th><th>model</th><th>dur ms</th><th>events</th></tr></thead>
                <tbody id="runs"></tbody></table>
              <div id="detail"></div>
            </main>
            <script>
            const $ = id => document.getElementById(id);
            const pill = (text, cls) => `<span class="pill ${cls}">${text}</span>`;
            const layerPill = l => l === "STABLE_PASS" ? pill(l, "ok")
                                 : l === "FLAKY" ? pill(l, "warn") : pill(l, "bad");
            const esc = s => String(s ?? "");
            let RUNS = [];

            async function j(url) { const r = await fetch(url); if (!r.ok) throw new Error(url); return r.json(); }

            function renderCards(s) {
              $("sub").textContent = `${s.runs} runs · ${s.scenarios} scenarios`;
              $("cards").innerHTML =
                card(s.runs, "runs") + card((s.passRate * 100).toFixed(1) + "%", "pass rate") +
                card(s.stablePass, "stable-pass") + card(s.flaky, "flaky") + card(s.stableFail, "stable-fail");
            }
            const card = (b, t) => `<div class="card"><b>${b}</b><span>${t}</span></div>`;

            function renderTrend(runs) {
              const sorted = [...runs].sort((a, b) => a.startedAt.localeCompare(b.startedAt));
              $("trend").innerHTML = sorted.map(r =>
                `<div class="bar ${r.passed ? "" : "bad"}" title="${esc(r.taskId)} · ${esc(r.status)} · ${r.durationMs}ms"
                     style="height:${Math.max(6, Math.min(100, r.durationMs / 50))}px"></div>`).join("");
            }

            function renderScenarios(rows) {
              $("scenarios").innerHTML = rows.map(r =>
                `<tr><td><code>${esc(r.scenario)}</code></td><td>${r.runs}</td><td>${r.passed}</td>
                 <td>${(r.passRate * 100).toFixed(1)}%</td><td>${layerPill(r.layer)}</td></tr>`).join("");
            }

            const verdictPill = v => v === "FLIP_TO_PASS" ? pill(v, "ok")
                                 : v === "FLIP_TO_FAIL" ? pill(v, "bad")
                                 : v === "SAME_PASS" ? pill(v, "ok")
                                 : v === "SAME_FAIL" ? pill(v, "warn")
                                 : pill(v, "warn");
            const sidePill = (s, cls) => s ? (s.passed ? pill("PASS", cls || "ok") : pill("FAIL", "bad")) : '<span class="muted">-</span>';

            async function loadDiff() {
              const d = await j("/api/diff");
              const opts = d.versions.map(v => `<option>${esc(v)}</option>`).join("");
              $("diffBase").innerHTML = opts; $("diffCand").innerHTML = opts;
              $("diffBase").value = d.baseline ?? ""; $("diffCand").value = d.candidate ?? "";
              const flips = d.rows.filter(r => r.verdict.startsWith("FLIP"));
              $("diffSummary").textContent = `${d.rows.length} cases · ${flips.length} flips`;
              const drill = r => r.verdict === "FLIP_TO_PASS" || r.verdict === "SAME_FAIL"
                  ? r.baseline?.runId : r.candidate?.runId;
              $("diff").innerHTML = d.rows.map(r => {
                const cls = r.verdict === "FLIP_TO_FAIL" ? ' class="flip-bad"'
                          : r.verdict === "FLIP_TO_PASS" ? ' class="flip-good"' : "";
                const id = drill(r);
                return `<tr${cls} ${id ? `onclick="openRun('${esc(id)}')"` : ""}>
                   <td><code>${esc(r.task)}</code></td>
                   <td>${sidePill(r.baseline)}</td><td>${sidePill(r.candidate)}</td>
                   <td>${verdictPill(r.verdict)}</td>
                   <td class="muted">${esc(r.judgeReason ?? "-")}</td></tr>`;
              }).join("") || '<tr><td colspan="5" class="muted">need two agent versions in the store</td></tr>';
            }

            function renderRuns() {              const q = $("q").value.toLowerCase(), st = $("status").value,
                    ver = $("version").value, src = $("source").value, onlyBad = $("onlyFailed").checked;
              const rows = RUNS.filter(r =>
                (!q || r.taskId.toLowerCase().includes(q)) &&
                (!st || r.status === st) &&
                (!ver || r.agentVersion === ver) &&
                (!src || r.source === src) &&
                (!onlyBad || !r.passed));
              $("runs").innerHTML = rows.map(r =>
                `<tr onclick="openRun('${esc(r.runId)}')">
                   <td class="muted">${esc(r.startedAt.replace("T", " ").slice(0, 19))}</td>
                   <td><code>${esc(r.taskId)}</code></td>
                   <td>${r.source === "ui-import" ? pill("◉ imported", "src")
                        : '<span class="muted">native</span>'}</td>
                   <td>${r.status === "SUCCESS" ? pill(r.status, "ok") : pill(r.status, "bad")}</td>
                   <td>${r.passed ? pill("PASS", "ok") : pill("FAIL", "bad")}</td>
                   <td>${esc(r.agentVersion ?? "-")}</td>
                   <td class="muted">${esc(r.observedModel ?? "-")}</td>
                   <td>${r.durationMs}</td><td>${r.events}</td></tr>`).join("");
            }

            const srcPill = d => (d.evaluation && d.evaluation.metadata && d.evaluation.metadata.source
                && d.evaluation.metadata.source !== "native")
                ? " " + pill("◉ " + d.evaluation.metadata.source, "src") : "";

            async function openRun(id) {
              const d = await j("/api/runs/" + id);
              const el = $("detail");
              el.style.display = "block";
              el.dataset.fa = d.finalAnswer ?? "";
              el.dataset.id = id;
              const r = d.run;
              window.__events = d.events ?? [];
              let html = `<h2 style="margin-top:4px"><code>${esc(r.taskId)}</code>
                          ${r.status === "SUCCESS" ? pill(r.status, "ok") : pill(r.status, "bad")}
                          ${srcPill(d)}
                          ${d.annotation ? (d.annotation.passed ? pill("annotated PASS", "ok") : pill("annotated FAIL", "bad")) : ""}
                          <span class="muted">· ${r.durationMs}ms · ${r.events} events</span></h2>`;
              if (d.finalAnswer) html += `<div class="ev"><b>final answer:</b> ${esc(d.finalAnswer)}</div>`;
              if (d.error) html += `<div class="ev"><b style="color:var(--bad)">error:</b> ${esc(d.error)}</div>`;
              if (d.annotation) html += `<div class="ev"><b>human review:</b> ${esc(d.annotation.note ?? "")}
                 <span class="muted">by ${esc(d.annotation.annotatedBy ?? "-")}
                 at ${esc(String(d.annotation.createdAt).replace("T", " ").slice(0, 19))}</span></div>`;
              html += `<h2 style="margin-top:10px">Trace timeline
                <span class="tgl"><button id="tglWf" class="on" onclick="setTl(true)">waterfall</button>
                <button id="tglLs" onclick="setTl(false)">list</button></span></h2>
                <div id="replayBar"></div><div id="tl"></div>`;
              html += `<h2>Human review <span class="pill src">M3</span></h2>
                <div class="ev" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
                  <select id="annPass"><option value="true">PASS</option><option value="false">FAIL</option></select>
                  <input id="annNote" placeholder="review note / real root cause" style="flex:1;min-width:200px">
                  <button onclick="saveAnnotation('${esc(id)}')">annotate</button>
                  <button onclick="saveScenario('${esc(id)}')">save as scenario</button>
                </div>`;
              if (d.evaluation) {
                html += `<h2>Evaluation (${esc(d.evaluation.agentVersion ?? "")})</h2>` +
                  d.evaluation.results.map(x =>
                    `<div class="ev">${x.passed ? pill("PASS", "ok") : pill("FAIL", "bad")}
                     <b>${esc(x.evaluator)}</b> score=${x.score} — ${esc(x.reason)}</div>` +
                    (x.answer ? `<div class="ev muted">answer: ${esc(x.answer)}</div>` : "") +
                    (x.explanation ? `<div class="ev muted">why: ${esc(x.explanation)}</div>` : "")).join("");
              }
              j("/api/runs/" + id + "/scores").then(s => {
                const src = (label, p) =>
                  `<div class="ev"><b>${label}</b> ` + (p === null || p === undefined
                    ? `<span class="muted">absent</span>`
                    : (p ? pill("PASS", "ok") : pill("FAIL", "bad"))) + `</div>`;
                const human = s.human
                  ? `<div class="ev muted">${esc(s.human.note ?? "")} — by ${esc(s.human.annotatedBy ?? "-")}</div>` : "";
                const agree = s.agreement.all
                  ? pill(s.agreement.sources + " sources agree", "ok")
                  : pill("SOURCES DISAGREE", "bad");
                const wrap = document.createElement("div");
                wrap.innerHTML = `<h2>Scores by source ${agree}</h2>` +
                  src("evaluator", s.evaluator.passed) +
                  src("judge", s.judge.passed) +
                  src("human", s.human ? s.human.passed : null) + human;
                el.appendChild(wrap);
              });
              html += `<h2>Diagnoses (${d.diagnoses.length})</h2>` + (d.diagnoses.length === 0
                ? `<div class="ev muted">none</div>`
                : d.diagnoses.map(x =>
                    `<div class="diag"><b>${esc(x.layer)}</b> — ${esc(x.issue)}
                     <span class="muted">severity=${x.severity} confidence=${x.confidence}</span>
                     ${x.evidence.length ? `<div class="ev muted">evidence: ${x.evidence.map(esc).join(", ")}</div>` : ""}
                     ${x.recommendation ? `<div class="ev"><b>fix:</b> ${esc(x.recommendation)}</div>` : ""}</div>`).join(""));
              html += `<details class="rawwrap"><summary class="muted">raw run JSON</summary>
                       <pre class="raw">${esc(JSON.stringify(d, null, 2))}</pre></details>`;
              el.innerHTML = html;
              renderTl();
              renderReplay(0);
              el.scrollIntoView({behavior: "smooth"});
            }

            /* M3 step-through replay: walk the trace one event at a time with
               the full event payload visible - the "watch it happen" view. */
            function renderReplay(i) {
              const evs = window.__events ?? [];
              const bar = $("replayBar");
              if (!bar || !evs.length) { if (bar) bar.innerHTML = ""; return; }
              const idx = Math.max(0, Math.min(i, evs.length - 1));
              const e = evs[idx];
              bar.innerHTML = `<div class="ev">
                <button onclick="renderReplay(0)">⏮</button>
                <button onclick="renderReplay(${idx - 1})">◀</button>
                <b>#${idx + 1}/${evs.length}</b>
                <button onclick="renderReplay(${idx + 1})">▶</button>
                <button onclick="renderReplay(${evs.length - 1})">⏭</button>
                <b>${esc(e.type)}</b> ${esc(e.name ?? "")}
                ${e.durationMs ? e.durationMs + "ms" : ""}
                <pre class="raw" style="max-height:150px;margin-top:6px">${esc(JSON.stringify(e, null, 2))}</pre>
              </div>`;
            }

            async function saveAnnotation(id) {
              const body = {passed: $("annPass").value === "true",
                            note: $("annNote").value, annotatedBy: "board"};
              const r = await fetch("/api/runs/" + id + "/annotate", {method: "POST",
                headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
              const out = await r.json();
              $("importOut").innerHTML = `<div class="imp${r.ok ? "" : " bad"}">${r.ok
                  ? `<b>annotated</b> ${esc(out.runId)} → ${out.passed ? "PASS" : "FAIL"}`
                  : `<b>annotate failed</b>: ${esc(out.error ?? "")}`}</div>`;
              if (r.ok) openRun(id);
            }

            async function saveScenario(id) {
              const expected = prompt("expected root cause (golden answer of the saved case):",
                  document.getElementById("detail").dataset.fa ?? "");
              if (expected == null) return;
              const instruction = prompt("instruction for the saved case:", "Diagnose this incident");
              if (instruction == null) return;
              const r = await fetch("/api/scenarios", {method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({runId: id, instruction: instruction, expected: expected})});
              const out = await r.json();
              $("importOut").innerHTML = `<div class="imp${r.ok ? "" : " bad"}">${r.ok
                  ? `<b>scenario saved</b>: ${esc(out.id)} → ${esc(out.file)} (${out.total} cases)`
                  : `<b>save failed</b>: ${esc(out.error ?? "")}`}</div>`;
            }

            let tlWaterfall = true;
            function setTl(waterfall) {
              tlWaterfall = waterfall;
              $("tglWf").classList.toggle("on", waterfall);
              $("tglLs").classList.toggle("on", !waterfall);
              renderTl();
            }
            function renderTl() {
              const events = window.__events ?? [];
              const wf = renderWaterfall(events);
              $("tl").innerHTML = (tlWaterfall && wf) ? wf : events.map(e =>
                `<div class="ev"><span class="t">#${e.index}</span> <b>${esc(e.type)}</b> ${esc(e.name ?? "")}
                 ${e.durationMs ? e.durationMs + "ms" : ""} ${esc(JSON.stringify(e.attributes))}</div>`).join("");
            }

            /* Jaeger-style time-proportional bars; falls back to a sequential
               layout when all event timestamps coincide (e.g. mock runs). */
            function renderWaterfall(events) {
              if (!events.length) return "";
              const times = events.map(e => Date.parse(e.timestamp));
              const realTime = times.every(t => !isNaN(t)) &&
                  Math.max(...times) > Math.min(...times);
              const durs = events.map(e => Math.max(e.durationMs ?? 0, 0));
              const t0 = Math.min(...times);
              const total = realTime
                  ? Math.max(...times) + Math.max(...durs) - t0
                  : durs.reduce((a, b) => a + b, 0);
              if (total <= 0) return "";
              let cursor = 0;
              const fmt = ms => ms >= 1000 ? (ms / 1000).toFixed(2) + "s" : ms + "ms";
              const bars = events.map((e, i) => {
                const left = realTime ? (times[i] - t0) / total * 100 : cursor / total * 100;
                const w = Math.max((e.durationMs ?? 0) / total * 100, 0.6);
                cursor += durs[i];
                return `<div class="lane"><div class="lbl"><b>${esc(e.type)}</b> ${esc(e.name ?? "")}</div>
                  <div class="track"><div class="wfbar ev-${esc(e.type)}"
                    title="${esc(e.type)} ${esc(e.name ?? "")} · ${e.durationMs ?? 0}ms"
                    style="left:${left}%;width:${w}%"></div></div></div>`;
              }).join("");
              const legend = [...new Set(events.map(e => e.type))].map(t =>
                `<span><i class="ev-${esc(t)}"></i>${esc(t)}</span>`).join("");
              return `<div class="wf">${bars}</div>
                <div class="axis"><div></div><div class="ticks">
                  <span style="left:0">0</span><span style="left:50%">${fmt(total / 2)}</span>
                  <span style="left:100%">${fmt(total)}</span></div></div>
                <div class="legend-ev">${legend}</div>`;
            }

            const drop = $("drop");
            drop.onclick = () => $("fileInput").click();
            drop.ondragover = e => { e.preventDefault(); drop.classList.add("over"); };
            drop.ondragleave = () => drop.classList.remove("over");
            drop.ondrop = e => { e.preventDefault(); drop.classList.remove("over"); importFiles(e.dataTransfer.files); };
            $("fileInput").onchange = e => { importFiles(e.target.files); e.target.value = ""; };

            async function importFiles(files) {
              for (const f of files) {
                const ph = document.createElement("div");
                ph.className = "imp"; ph.textContent = "◌ " + f.name + " - importing…";
                $("importOut").prepend(ph);
                try {
                  const r = await fetch("/api/import?name=" + encodeURIComponent(f.name),
                      { method: "POST", body: await f.arrayBuffer(),
                        headers: { "Content-Type": "application/octet-stream" } });
                  ph.remove();
                  renderImport(await r.json(), r.ok);
                } catch (err) {
                  ph.remove();
                  $("importOut").innerHTML =
                    `<div class="imp bad">✕ ${esc(f.name)} - ${esc(err)}</div>` + $("importOut").innerHTML;
                }
              }
              await boot();
            }

            function renderImport(o, ok) {
              const rows = (o.imported ?? []).map(r =>
                `<div class="imp">✓ ${pill(o.kind ?? "?", "src")} <code>${esc(r.runId)}</code>
                 ${esc(r.taskId)} ${r.passed ? pill("PASS", "ok") : pill("FAIL", "bad")}
                 <span class="muted">${esc(r.agent ?? "")} · source=ui-import</span></div>`);
              (o.skipped ?? []).forEach(s =>
                rows.push(`<div class="imp">⤼ ${pill("SKIPPED", "warn")}
                 <span class="muted">${esc(s)}</span></div>`));
              if (o.error) rows.push(`<div class="imp bad">✕ ${esc(o.file ?? "")}
                 [${esc(o.kind ?? "?")}] ${esc(o.error)}</div>`);
              $("importOut").innerHTML = rows.join("") + $("importOut").innerHTML;
            }

            const stat = (b, t) => `<div class="card" style="min-width:96px"><b>${b}</b><span>${t}</span></div>`;
            const pct = v => v == null ? '<span class="muted">-</span>' : (v * 100).toFixed(0) + "%";

            async function loadArtifacts() {
              const list = await j("/api/artifacts");
              const sel = $("artifactSel");
              sel.innerHTML = '<option value="">choose artifact…</option>' +
                list.map(a => `<option value="${a.id}">${esc(a.name)} · ${esc(a.kind)}</option>`).join("");
              sel.onchange = () => renderArtifact(sel.value);
            }

            async function renderArtifact(id) {
              $("artifactMeta").textContent = "";
              if (id === "") { $("artifactView").innerHTML = ""; return; }
              const a = await j("/api/artifacts/" + id);
              $("artifactMeta").textContent = `${a.name} · ${a.kind}`;
              const c = a.content;
              if (a.kind === "approved-baseline") $("artifactView").innerHTML = renderBaseline(c);
              else if (a.kind === "compare") $("artifactView").innerHTML = renderCompare(c);
              else if (a.kind === "calibration") $("artifactView").innerHTML = renderCalibration(c);
              else $("artifactView").innerHTML = `<pre class="raw">${esc(JSON.stringify(c, null, 2))}</pre>`;
            }

            function renderBaseline(b) {
              const rows = Object.entries(b.casePassRates ?? {}).map(([k, v]) =>
                `<tr><td><code>${esc(k)}</code></td><td>${pct(v)}</td></tr>`).join("");
              return `<div class="cards">${stat(pct(b.passPowK), "pass^k")}
                  ${stat(pct(b.avgPassRate), "avg pass rate")}
                  ${stat("x" + (b.repeats ?? "?"), "repeats")}
                  ${stat(esc(b.approvedBy ?? "-"), "approved by")}</div>
                <div class="muted" style="margin:6px 0">dataset sha256 <code>${esc(b.datasetSha256 ?? "-")}</code>
                 · evaluators: ${esc((b.evaluatorNames ?? []).join(", "))} · approved at ${esc(b.createdAt ?? "-")}
                 · format v${esc(b.formatVersion ?? "?")}</div>
                <table><thead><tr><th>case</th><th>pass rate</th></tr></thead><tbody>${rows}</tbody></table>`;
            }

            function renderCompare(c) {
              const base = c.baseline?.cases ?? {}, cand = c.candidate?.cases ?? {};
              const cases = [...new Set([...Object.keys(base), ...Object.keys(cand)])].sort();
              const rows = cases.map(k => {
                const bv = base[k], cv = cand[k];
                const d = (cv ?? 0) - (bv ?? 0);
                const cls = d < 0 ? ' class="flip-bad"' : d > 0 ? ' class="flip-good"' : "";
                return `<tr${cls}><td><code>${esc(k)}</code></td><td>${pct(bv)}</td><td>${pct(cv)}</td>
                  <td>${d === 0 ? "—" : (d > 0 ? "+" : "") + (d * 100).toFixed(0) + "%"}</td></tr>`;
              }).join("");
              const g = c.gate ?? {};
              return `<div class="cards">${stat(esc(c.baseline?.label ?? "-"), "baseline")}
                  ${stat(esc(c.candidate?.label ?? "-"), "candidate")}
                  ${stat(pct(c.baseline?.passPowK), "baseline pass^k")}
                  ${stat(pct(c.candidate?.passPowK), "candidate pass^k")}</div>
                <div style="margin:6px 0">gate: ${g.passed ? pill("PASSED", "ok") : pill("FAILED", "bad")}
                  <span class="muted">${esc(g.reason ?? "")} · adapter=${esc(c.adapter ?? "-")}
                  · repeats=${esc(c.repeats ?? "-")} · ${esc((c.evaluators ?? []).join(", "))}</span></div>
                ${c.review ? `<div class="imp"><b>gate review:</b><br>${esc(c.review)}</div>` : ""}
                <table><thead><tr><th>case</th><th>baseline</th><th>candidate</th><th>Δ</th></tr></thead>
                  <tbody>${rows}</tbody></table>`;
            }

            function renderCalibration(c) {
              const samples = c.samples ?? [];
              const agree = samples.filter(s => !!s.judgedPass === !!s.expectedPass).length;
              const byBehavior = {};
              samples.forEach(s => {
                const key = s.behavior ?? "?";
                const b = byBehavior[key] ??= {n: 0, score: 0, agree: 0};
                b.n++; b.score += s.score ?? 0;
                if (!!s.judgedPass === !!s.expectedPass) b.agree++;
              });
              const rows = Object.entries(byBehavior).sort((x, y) => x[0].localeCompare(y[0])).map(([k, b]) =>
                `<tr><td><code>${esc(k)}</code></td><td>${b.n}</td>
                 <td>${(b.score / b.n).toFixed(3)}</td><td>${b.agree}/${b.n}</td></tr>`).join("");
              const sampleRows = samples.map(s =>
                `<tr><td><code>${esc(s.behavior ?? "?")}</code></td>
                 <td>${s.expectedPass ? pill("want PASS", "ok") : pill("want FAIL", "bad")}
                     ${s.judgedPass ? pill("judge PASS", "ok") : pill("judge FAIL", "bad")}</td>
                 <td>${(s.score ?? 0).toFixed(3)}</td>
                 <td class="muted" style="max-width:460px;overflow:hidden;text-overflow:ellipsis;
                     white-space:nowrap" title="${esc(s.reason ?? "")}">${esc((s.reason ?? "").slice(0, 140))}</td></tr>`).join("");
              return `<div class="cards">${stat(samples.length, "samples")}
                  ${stat(samples.length ? (agree / samples.length * 100).toFixed(0) + "%" : "-", "judge agreement")}
                  ${stat("v" + (c.corpusVersion ?? "?"), "corpus version")}</div>
                <table><thead><tr><th>behavior</th><th>n</th><th>avg score</th><th>agreement</th></tr></thead>
                  <tbody>${rows}</tbody></table>
                <h2 style="margin-top:14px">samples</h2>
                <table><thead><tr><th>behavior</th><th>expected / judged</th><th>score</th><th>reason</th></tr></thead>
                  <tbody>${sampleRows}</tbody></table>`;
            }

            async function boot() {
              renderCards(await j("/api/summary"));
              renderScenarios(await j("/api/scenarios"));
              RUNS = await j("/api/runs");
              $("version").innerHTML = '<option value="">version: all</option>';
              [...new Set(RUNS.map(r => r.agentVersion).filter(Boolean))].forEach(v => {
                const o = document.createElement("option"); o.textContent = v; $("version").appendChild(o);
              });
              renderTrend(RUNS); renderRuns(); loadDiff();
              j("/api/imports").then(d => {
                $("importLog").innerHTML = (d.imports ?? []).map(r =>
                  `<tr><td class="muted">${esc(String(r.importedAt).replace("T", " ").slice(0, 19))}</td>
                   <td><code>${esc(r.file)}</code></td><td>${pill(r.kind, "src")}</td>
                   <td class="muted">${esc(r.source)}</td><td>${r.imported}</td><td>${r.skipped}</td>
                   <td class="muted">${esc(r.error ?? "")}</td></tr>`).join("")
                  || '<tr><td colspan="7" class="muted">no imports yet</td></tr>';
              }).catch(() => {});
              j("/api/annotations").then(d => {
                $("reviewLog").innerHTML = (d.annotations ?? []).map(a =>
                  `<tr><td><a href="#" onclick="openRun('${esc(a.runId)}');return false"><code>${esc(a.runId)}</code></a></td>
                   <td><code>${esc(a.taskId ?? "-")}</code></td>
                   <td>${a.passed ? pill("PASS", "ok") : pill("FAIL", "bad")}</td>
                   <td class="muted">${esc(a.note ?? "")}</td><td class="muted">${esc(a.annotatedBy ?? "")}</td>
                   <td class="muted">${esc(String(a.createdAt).replace("T", " ").slice(0, 19))}</td></tr>`).join("")
                  || '<tr><td colspan="6" class="muted">no annotations yet</td></tr>';
              }).catch(() => {});
              loadArtifacts().catch(() => {});
            }

            function wireOnce() {
              ["diffBase", "diffCand"].forEach(id => $(id).addEventListener("change", () =>
                j(`/api/diff?baseline=${encodeURIComponent($("diffBase").value)}&candidate=${encodeURIComponent($("diffCand").value)}`)
                  .then(d => {
                    const flips = d.rows.filter(r => r.verdict.startsWith("FLIP"));
                    $("diffSummary").textContent = `${d.rows.length} cases · ${flips.length} flips`;
                    $("diff").innerHTML = d.rows.map(r => {
                      const cls = r.verdict === "FLIP_TO_FAIL" ? ' class="flip-bad"'
                                : r.verdict === "FLIP_TO_PASS" ? ' class="flip-good"' : "";
                      const id = r.verdict === "FLIP_TO_PASS" || r.verdict === "SAME_FAIL"
                          ? r.baseline?.runId : r.candidate?.runId;
                      return `<tr${cls} ${id ? `onclick="openRun('${esc(id)}')"` : ""}>
                         <td><code>${esc(r.task)}</code></td>
                         <td>${sidePill(r.baseline)}</td><td>${sidePill(r.candidate)}</td>
                         <td>${verdictPill(r.verdict)}</td>
                         <td class="muted">${esc(r.judgeReason ?? "-")}</td></tr>`;
                    }).join("") || '<tr><td colspan="5" class="muted">no cases</td></tr>';
                  })));
              ["q", "status", "version", "source", "onlyFailed"].forEach(id =>
                $(id).addEventListener("input", renderRuns));
            }
            wireOnce();
            boot().catch(e => { $("sub").textContent = "load failed: " + e; });
            </script></body></html>
            """;
}
