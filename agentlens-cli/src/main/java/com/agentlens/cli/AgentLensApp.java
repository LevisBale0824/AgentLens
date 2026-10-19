package com.agentlens.cli;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.AdapterRegistry;
import com.agentlens.eval.ApprovedBaseline;
import com.agentlens.eval.ApprovedGate;
import com.agentlens.eval.ComplianceEvaluator;
import com.agentlens.eval.EvaluatorRegistry;
import com.agentlens.eval.ExperimentRunner;
import com.agentlens.eval.GateReview;
import com.agentlens.eval.PassK;
import com.agentlens.eval.RegressionGate;
import com.agentlens.eval.RunLimitEvaluator;
import com.agentlens.eval.Scenario;
import com.agentlens.eval.ScenarioLoader;
import com.agentlens.eval.SuccessAssertionEvaluator;
import com.agentlens.eval.TaskCompletionEvaluator;
import com.agentlens.eval.ToolPolicyEvaluator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentLens CLI. Usage:
 *   run [--scenarios PATH] [--repeats K] [--allowed T1,T2] [--forbidden T1,T2] [--judge]
 *                     (--repeats guidance: 95%-confident stability layering needs
 *                      K=6 for a p=0.5 scenario, K=29 for p=0.9, K=59 for p=0.95;
 *                      misclassification at K=5 is ~8.8% for p=0.6 - see ROADMAP 7.25)
 *   compare --baseline BEHAVIOR --candidate BEHAVIOR [--scenarios PATH] [--repeats K]
 *           [--adapter dsh]   (both sides = real headless DSH sessions; --judge supported)
 *           [--baseline-override k=v,...] [--candidate-override k=v,...]
 *                             (per-side task-context overrides; with dsh: dshArgs=... /
 *                              env.KEY=... switch the candidate's model or launcher config)
 *           [--review]        (P1-B: gate review - pass^k + evidence chain -> LLM
 *                              review when --judge is on, deterministic fallback otherwise)
 *           [--scores-out PATH] (P1-B: persist the candidate's raw k-times scores)
 *   scores --out PATH [--db PATH] [--version V]     (P1-B: capture raw scores artifact)
 *   scores --reduce PATH [--at-least M]             (P1-B: recompute pass@k/pass^k/at-least-m)
 *   opsbench-import --root PATH --out PATH [--limit N]  (P1-C: Cloud-OpsBench 754
 *                              real K8s RCA cases -> ScenarioLoader-readable file)
 *   demo
 */
public final class AgentLensApp {
    private static final String DEFAULT_SCENARIOS = "data/scenarios/ops-scenarios.json";
    private static final String DEFAULT_DB = "output/runs.db";

    /** Attaches SQLite persistence (full trace + evaluation) to a runner. */
    private static ExperimentRunner withSqlite(ExperimentRunner runner, String dbPath) throws Exception {
        var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(dbPath));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sqlite.close();
            } catch (Exception e) {
                System.err.println("warning: failed to close sqlite store: " + e);
            }
        }));
        return runner.onRunCompleted((run, evaluation) -> {
            try {
                sqlite.saveRun(run, evaluation.agentName());
                sqlite.saveEvaluation(evaluation);
            } catch (Exception e) {
                log.event("error", "run.persist_failed", "runId", run.runId(),
                        "taskId", run.taskId(), "error", String.valueOf(e));
                throw new IllegalStateException("sqlite persistence failed for " + run.runId(), e);
            }
            log.event("info", "run.completed", "runId", run.runId(), "taskId", run.taskId(),
                    "status", run.status(), "durationMs", run.durationMs());
        });
    }

    /**
     * ROADMAP P2-3: optional OTLP sink from CLI flags. {@code --otel-endpoint URL}
     * posts each run's OTLP/JSON trace to an OTLP/HTTP collector; {@code --otel-out
     * PATH} appends one JSON line per run (inspectable offline). Both may be combined.
     * Fail-open: export problems warn on stderr and never fail the evaluation.
     * Returns null when neither flag is set.
     */
    private static java.util.function.Consumer<AgentRun> otelSink(Map<String, String> opts) throws Exception {
        String endpoint = opts.get("otel-endpoint");
        String out = opts.get("otel-out");
        boolean hasEndpoint = endpoint != null && !endpoint.isBlank();
        boolean hasOut = out != null && !out.isBlank();
        if (!hasEndpoint && !hasOut) return null;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path file = hasOut ? Path.of(out) : null;
        Path fileParent = file == null ? null : file.getParent();
        if (fileParent != null) java.nio.file.Files.createDirectories(fileParent);
        var exporter = hasEndpoint ? new com.agentlens.eval.OtlpExporter(
                java.net.URI.create(endpoint),
                java.time.Duration.ofSeconds(requireInt(opts, "otel-timeout-sec", 10)), 2) : null;
        System.out.printf("otel: %s%s%s%n",
                hasEndpoint ? "endpoint=" + endpoint : "",
                hasEndpoint && hasOut ? " " : "",
                hasOut ? "file=" + file : "");
        return run -> {
            try {
                Map<String, Object> payload = com.agentlens.otel.OtelSpanMapper.toOtlpJson(run);
                if (file != null) java.nio.file.Files.writeString(file,
                        json.writeValueAsString(payload) + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                if (exporter != null) exporter.export(payload);
            } catch (Exception e) {
                log.event("warn", "otel.export_failed", "error", String.valueOf(e));
                System.err.println("otel export failed (fail-open): " + e);
            }
        };
    }

    /** Chains the optional OTel sink onto a runner (no-op without the flags). */
    private static ExperimentRunner withOtel(ExperimentRunner runner, Map<String, String> opts) throws Exception {
        java.util.function.Consumer<AgentRun> sink = otelSink(opts);
        return sink == null ? runner : runner.onRunCompleted((run, evaluation) -> sink.accept(run));
    }

    /** Generates a self-contained HTML report from the SQLite store. */
    private static void report(Map<String, String> opts) throws Exception {
        Path db = Path.of(opts.getOrDefault("db", DEFAULT_DB));
        Path out = Path.of(opts.getOrDefault("out", "output/report.html"));
        try (var sqlite = new com.agentlens.eval.SqliteRunStore(db)) {
            Path written = new com.agentlens.eval.ReportGenerator().generate(sqlite, out);
            System.out.printf("report written: %s (%d bytes)%n", written.toAbsolutePath(),
                    java.nio.file.Files.size(written));
        }
    }

    /** Imports an existing DSH session log, maps it to the unified trace, and runs evaluators + diagnosis. */
    private static void importSession(Map<String, String> opts) throws Exception {
        if (opts.containsKey("aiopslab")) {
            importAiopslabSession(opts);
            return;
        }
        String session = opts.get("session");
        if (session == null || session.isBlank()) {
            System.err.println("usage: import --session PATH (a DSH session.jsonl or session.jsonl.zstd)"
                    + " [--task ID] [--overwrite] [--db PATH]");
            System.exit(2);
        }
        var events = com.agentlens.adapters.dsh.DshSessionLogReader.read(Path.of(session));
        var mapped = com.agentlens.adapters.dsh.DshTraceMapper.toRun(events,
                opts.getOrDefault("task", "imported"));
        AgentRun run = mapped.run();
        System.out.printf("session %s%n  cwd=%s turns=%d events=%d status=%s duration=%dms%n",
                mapped.sessionId(), mapped.cwd(), mapped.turns(), run.events().size(),
                run.status(), run.durationMs());
        System.out.printf("  instruction=%.120s%n", mapped.instruction());
        System.out.printf("  tools called: %d, tool results: %d%n",
                run.events().stream().filter(e -> e.type() == com.agentlens.core.EventType.TOOL_CALL).count(),
                run.events().stream().filter(e -> e.type() == com.agentlens.core.EventType.TOOL_RESULT).count());
        run.events().stream().limit(40).forEach(e -> System.out.printf("  %-13s %-22s %s%n",
                e.type(), e.name(), e.attributes()));

        int steps = (int) run.events().stream()
                .filter(e -> e.type() == com.agentlens.core.EventType.TOOL_CALL).count();
        var task = new AgentTask(run.taskId(),
                mapped.instruction() == null ? "imported session" : mapped.instruction(),
                Map.of(), steps + 10);
        var registry = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator());
        if (opts.containsKey("judge")) registry.register(judgeEvaluator(opts));
        var results = registry.evaluate(task, run);
        System.out.println("  evaluation:");
        printEvaluationRows(results);
        if (opts.containsKey("judge")) {
            System.out.println("  (judge scores are informational here and not persisted)");
        }
        var diagnoses = new com.agentlens.eval.DiagnosisEngine().diagnose(task, run, results);
        System.out.printf("  diagnoses: %d%n", diagnoses.size());
        diagnoses.forEach(d -> System.out.printf("    %-15s %-45s evidence=%s%n",
                d.layer(), d.issue(), d.evidence()));

        // Persist through the shared import service so CLI history lands in the
        // same store the board reads (M1 of the UI history-analysis plan).
        try (var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(opts.getOrDefault("db", DEFAULT_DB)))) {
            var importer = new SessionImportService(sqlite);
            int savedCount = 0;
            try {
                var saved = importer.importDshRun(events,
                        new SessionImportService.Options("cli-import", opts.get("task"),
                                opts.containsKey("overwrite")));
                savedCount = 1;
                System.out.printf("  saved: %s (agent=%s, source=cli-import, taskId=%s)%n",
                        saved.run().runId(), saved.agentName(), saved.run().taskId());
            } catch (SessionImportService.RunExistsException e) {
                System.out.println("  skipped: " + e.getMessage() + " (--overwrite to re-import)");
            }
            importer.record(String.valueOf(Path.of(session).getFileName()),
                    SessionImportService.Kind.DSH_SESSION, "cli-import", savedCount,
                    savedCount == 0 ? 1 : 0, null);
        }

        java.util.function.Consumer<AgentRun> otel = otelSink(opts);
        if (otel != null) otel.accept(run);
    }

    /**
     * ROADMAP P2-2: imports a real AIOpsLab session JSON (results/&lt;id&gt;_&lt;ts&gt;.json),
     * maps the ACI turns onto the unified trace, and evaluates + diagnoses + persists
     * the run - the offline evaluation path for external agents (no cluster needed).
     */
    private static void importAiopslabSession(Map<String, String> opts) throws Exception {
        String session = opts.get("session");
        if (session == null || session.isBlank()) {
            System.err.println("usage: import --aiopslab --session PATH (an AIOpsLab results/*.json)");
            System.exit(2);
        }
        var imported = new com.agentlens.adapters.aiopslab.AIOpsLabSessionImporter()
                .importSession(Path.of(session));
        AgentRun run = imported.run();
        System.out.printf("aiopslab session %s%n  agent=%s problem=%s events=%d status=%s duration=%dms tokens=%d%n",
                run.runId(), imported.agentName(), imported.problemId(), run.events().size(),
                run.status(), run.durationMs(), run.totalTokens());
        run.events().stream().limit(40).forEach(e -> System.out.printf("  %-13s %-22s %s%n",
                e.type(), e.name(), e.attributes()));

        int steps = (int) run.events().stream()
                .filter(e -> e.type() == com.agentlens.core.EventType.TOOL_CALL).count();
        var task = new AgentTask(run.taskId(),
                "AIOpsLab problem " + run.taskId(), Map.of(), steps + 10);
        var registry = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(
                        new java.util.HashSet<>(com.agentlens.adapters.aiopslab.AIOpsLabProblemCatalog.ACI_ACTIONS),
                        Set.of()));
        if (opts.containsKey("judge")) registry.register(judgeEvaluator(opts));
        var results = registry.evaluate(task, run);
        System.out.println("  evaluation:");
        printEvaluationRows(results);
        var diagnoses = new com.agentlens.eval.DiagnosisEngine().diagnose(task, run, results);
        System.out.printf("  diagnoses: %d%n", diagnoses.size());
        diagnoses.forEach(d -> System.out.printf("    %-15s %-45s evidence=%s%n",
                d.layer(), d.issue(), d.evidence()));

        try (var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(opts.getOrDefault("db", DEFAULT_DB)))) {
            var importer = new SessionImportService(sqlite);
            int savedCount = 0;
            try {
                var saved = importer.importAiopslabRun(imported,
                        new SessionImportService.Options("cli-import", null, opts.containsKey("overwrite")));
                savedCount = 1;
                System.out.printf("  saved: %s (agent=%s, source=cli-import)%n",
                        saved.run().runId(), saved.agentName());
            } catch (SessionImportService.RunExistsException e) {
                System.out.println("  skipped: " + e.getMessage() + " (--overwrite to re-import)");
            }
            importer.record(String.valueOf(Path.of(session).getFileName()),
                    SessionImportService.Kind.AIOPSLAB, "cli-import", savedCount,
                    savedCount == 0 ? 1 : 0, null);
            log.event("info", "run.completed", "runId", run.runId(), "taskId", run.taskId(),
                    "status", run.status(), "durationMs", run.durationMs(), "source", "aiopslab");
        }
        java.util.function.Consumer<AgentRun> aiopslabOtel = otelSink(opts);
        if (aiopslabOtel != null) aiopslabOtel.accept(run);
    }

    /**
     * ROADMAP P2-2: regenerates the AgentLens scenario dataset from AIOpsLab's
     * official problem registry source (defaults to the committed verbatim
     * fixture), so the 81-problem catalog can be refreshed whenever
     * microsoft/AIOpsLab adds problems.
     */
    private static void aiopslabCatalog(Map<String, String> opts) throws Exception {
        Path registry = Path.of(opts.getOrDefault("registry",
                "agentlens-adapter-aiopslab/src/test/resources/aiopslab-registry.py"));
        Path out = Path.of(opts.getOrDefault("out", "data/scenarios/aiopslab-scenarios.json"));
        var problems = new com.agentlens.adapters.aiopslab.AIOpsLabProblemCatalog().parse(registry);
        var scenarios = problems.stream()
                .map(com.agentlens.adapters.aiopslab.AIOpsLabProblemCatalog::toScenario).toList();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Path parent = out.getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), scenarios);
        System.out.printf("aiopslab catalog: %d problems -> %s%n", scenarios.size(), out.toAbsolutePath());
        problems.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        com.agentlens.adapters.aiopslab.AIOpsLabProblemCatalog.Problem::taskType,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()))
                .forEach((type, count) -> System.out.printf("  %-13s %d%n", type, count));
    }

    /** Scenario-set default adapter: used when every scenario names the same adapter. */
    private static String commonAdapter(List<Scenario> scenarios) {
        if (scenarios.isEmpty()) return "mock";
        var names = scenarios.stream().map(Scenario::adapter).collect(java.util.stream.Collectors.toSet());
        return names.size() == 1 ? names.iterator().next() : "mock";
    }

    /**
     * Live mode: runs each scenario with streaming trace display and online verdicts;
     * with --abort-on-violation, hard violations kill the dsh process immediately.
     * Persists every run + evaluation to SQLite (same as the batch path) and prints
     * the same pass^k summary + release gate, so live/batch outputs stay comparable.
     */
    private static void dshLiveRun(com.agentlens.adapters.dsh.DshAgentAdapter dsh,
                                   List<Scenario> scenarios, Map<String, String> opts) throws Exception {
        boolean abort = "true".equalsIgnoreCase(opts.getOrDefault("abort-on-violation", "false"));
        int repeats = requireInt(opts, "repeats", 1);
        int passed = 0, failed = 0, aborted = 0;

        var registry = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of()))
                .register(new ComplianceEvaluator())
                .register(new SuccessAssertionEvaluator());
        if (opts.containsKey("judge")) registry.register(judgeEvaluator(opts));
        if (opts.containsKey("deepeval")) registry.register(deepEvalEvaluator(opts));
        var diagnosis = new com.agentlens.eval.DiagnosisEngine();
        Map<String, List<List<com.agentlens.core.EvaluationResult>>> byTask = new java.util.LinkedHashMap<>();
        java.util.function.Consumer<AgentRun> otel = otelSink(opts);

        try (var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(opts.getOrDefault("db", DEFAULT_DB)))) {
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (Scenario scenario : scenarios) {
                    var task = scenario.toTask();
                    System.out.printf("%n[%s] repeat %d/%d: %s%n", java.time.LocalTime.now(), repeat, repeats, task.id());
                    var live = new com.agentlens.adapters.dsh.DshAgentAdapter.LiveListener() {
                        @Override public void onTraceEvent(com.agentlens.core.AgentEvent e, int i) {
                            System.out.printf("  [%s] %-13s %-20s %s%n", java.time.LocalTime.now().withNano(0),
                                    e.type(), e.name(),
                                    e.attributes().isEmpty() ? "" : String.valueOf(e.attributes()).substring(0,
                                            Math.min(100, String.valueOf(e.attributes()).length())));
                        }
                        @Override public void onVerdict(com.agentlens.core.LiveEvaluator.Verdict v) {
                            System.out.printf("  !! %-10s %-15s %s%n", v.layer(), v.rule(), v.message());
                        }
                        @Override public boolean shouldAbort(com.agentlens.core.LiveEvaluator.Verdict v) {
                            return abort;
                        }
                    };
                    AgentRun run = dsh.run(task, live);
                    boolean hardFail = run.status() != com.agentlens.core.RunStatus.SUCCESS;
                    if (run.status() == com.agentlens.core.RunStatus.CANCELLED) aborted++;
                    else if (hardFail) failed++;
                    var results = registry.evaluate(task, run);
                    var diagnoses = diagnosis.diagnose(task, run, results);
                    System.out.printf("  => status=%s duration=%dms events=%d diagnoses=%d%n",
                            run.status(), run.durationMs(), run.events().size(), diagnoses.size());
                    if (!run.events().isEmpty() && results.stream().noneMatch(r -> r.passed())) failed++;
                    else if (!hardFail) passed++;

                    try {
                        sqlite.saveRun(run, dsh.name());
                        sqlite.saveEvaluation(new com.agentlens.core.EvaluationRun(run.runId(), task.id(),
                                dsh.name(), "dev", run.startedAt(), results,
                                Map.of("durationMs", run.durationMs(), "totalTokens", run.totalTokens())));
                        log.event("info", "run.completed", "runId", run.runId(), "taskId", task.id(),
                                "status", run.status(), "durationMs", run.durationMs(), "source", "dsh");
                    } catch (Exception e) {
                        throw new IllegalStateException("sqlite persistence failed for " + run.runId(), e);
                    }
                    if (otel != null) otel.accept(run);
                    byTask.computeIfAbsent(task.id(), t -> new ArrayList<>()).add(results);
                }
            }
        }
        System.out.printf("%nlive run: scenarios=%d repeats=%d passed=%d failed=%d aborted=%d%n",
                scenarios.size(), repeats, passed, failed, aborted);
        var summary = PassK.summarize(byTask);
        printPassKSummary(summary);
        var gate = new RegressionGate(List.of("tool-policy"), 0.0).requireStable(summary);
        System.out.printf("%nrelease gate: passed=%s, reason=%s%n", gate.passed(), gate.reason());
        logGate("dsh-live", gate);
        // P0-D contract: harness-level failures are tool errors (1), the gate's
        // quality verdict is a regression (100) - CI must be able to tell apart.
        if (failed > 0 || aborted > 0) System.exit(1);
        gateExit("dsh-live", gate);
    }

    /** Restores and prints a full run (trace timeline + evaluation) by run id. */
    private static void show(Map<String, String> opts) throws Exception {
        String runId = opts.get("run");
        if (runId == null || runId.isBlank()) {
            System.err.println("usage: show --run RUN_ID  (run ids listed with no --run)");
            System.exit(2);
        }
        try (var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(opts.getOrDefault("db", DEFAULT_DB)))) {
            if (runId.equals("list")) {
                sqlite.listRuns().forEach(id -> System.out.println(id));
                return;
            }
            var runOpt = sqlite.loadRun(runId);
            if (runOpt.isEmpty()) {
                System.err.println("run not found: " + runId);
                System.exit(2);
            }
            var run = runOpt.get();
            System.out.printf("run %s%n  task=%s status=%s duration=%dms tokens=%d%n",
                    run.runId(), run.taskId(), run.status(), run.durationMs(), run.totalTokens());
            System.out.printf("  answer=%s%n", run.finalAnswer());
            run.events().forEach(e -> System.out.printf("  %-13s %-22s %dms %s%n",
                    e.type(), e.name(), e.durationMs(), e.attributes()));
            sqlite.loadEvaluation(runId).ifPresentOrElse(ev -> {
                System.out.println("  evaluation:");
                printEvaluationRows(ev.results());
                boolean evaluator = ev.results().stream()
                        .filter(r -> !r.evaluator().startsWith("llm-judge"))
                        .allMatch(com.agentlens.core.EvaluationResult::passed);
                var judge = ev.results().stream()
                        .filter(r -> r.evaluator().startsWith("llm-judge"))
                        .toList();
                com.agentlens.eval.SqliteRunStore.Annotation annotation;
                try {
                    annotation = sqlite.loadAnnotation(runId).orElse(null);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                System.out.printf("  scores by source: evaluator=%s judge=%s human=%s%n",
                        evaluator ? "PASS" : "FAIL",
                        judge.isEmpty() ? "absent"
                                : judge.stream().allMatch(com.agentlens.core.EvaluationResult::passed)
                                ? "PASS" : "FAIL",
                        annotation == null ? "absent" : annotation.passed() ? "PASS" : "FAIL");
                if (!ev.metadata().isEmpty()) {
                    System.out.println("  metadata:");
                    ev.metadata().forEach((k, v) -> System.out.printf("    %-16s %s%n", k, v));
                }
            }, () -> System.out.println("  evaluation: none recorded"));
        }
    }

    /**
     * Scorer contract output (P1): one row per score with the optional
     * answer/explanation dimensions when an evaluator fills them.
     */
    private static void printEvaluationRows(List<com.agentlens.core.EvaluationResult> results) {
        results.forEach(r -> {
            System.out.printf("    %-18s passed=%-5s score=%.2f %s%n",
                    r.evaluator(), r.passed(), r.score(), r.reason());
            if (r.answer() != null) System.out.println("      answer: " + r.answer());
            if (r.explanation() != null) System.out.println("      why: " + r.explanation());
        });
    }

    /** Structured logger (active only with --log-json); request id always present. */
    private static JsonLog log = JsonLog.disabled();

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
        Map<String, String> opts = parseOptions(args, command);
        // ROADMAP P2-5: every invocation gets a request id; stderr goes structured NDJSON
        // with --log-json, otherwise the request id is still echoed for correlation.
        log = opts.containsKey("log-json")
                ? JsonLog.to(System.err, JsonLog.newRequestId())
                : JsonLog.to(null, JsonLog.newRequestId());
        System.out.printf("request-id: %s%n", log.requestId());
        log.event("info", "cli.start", "command", command);
        try {
            validate(command, opts);
            switch (command) {
                case "run" -> run(opts);
                case "compare" -> compare(opts);
                case "scores" -> scores(opts);
                case "opsbench-import" -> opsbenchImport(opts);
                case "approve" -> approve(opts);
                case "import" -> importSession(opts);
                case "show" -> show(opts);
                case "report" -> report(opts);
                case "demo" -> demo(opts);
                case "serve" -> serve(opts);
                case "judge-calibrate" -> judgeCalibrate(opts);
                case "diagnose-calibrate" -> diagnoseCalibrate(opts);
                case "otel-receive" -> otelReceive(opts);
                case "aiopslab-catalog" -> aiopslabCatalog(opts);
                default -> {
                    System.err.println("unknown command: " + command);
                    System.err.println("usage: run [...] | compare --baseline B --candidate B"
                            + " [--baseline-override k=v,..] [--candidate-override k=v,..]"
                            + " [--approved baseline/approved-baseline.json] [--out PATH]"
                            + " [--review [--judge]] [--scores-out PATH]"
                            + " | scores --out PATH [--db PATH] [--version V]"
                            + " | scores --reduce PATH [--at-least M]"
                            + " | opsbench-import --root PATH --out PATH [--limit N]"
                            + " | approve --compare PATH --out PATH | show --run ID"
                            + " | report [--db PATH] [--out PATH] | serve [--port N] [--artifacts FILE,FILE]"
                            + " [--scenarios-out PATH] | otel-receive [--port N]"
                            + " [--db PATH] | import --session PATH"
                            + " | import --aiopslab --session PATH | aiopslab-catalog [--registry PATH]"
                            + " [--out PATH] | demo"
                            + " | diagnose-calibrate [--disable-rule A,B] [--json-out PATH]");
                    System.exit(2);
                }
            }
            log.event("info", "cli.end", "command", command);
        } catch (ConfigException e) {
            log.event("error", "cli.config_error", "command", command, "message", e.getMessage());
            System.err.println("config error: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * ROADMAP P2-5: runtime configuration validation - fail fast with actionable
     * messages before any scenario or session starts (package-private for tests).
     */
    static void validate(String command, Map<String, String> opts) {
        switch (command) {
            case "run", "compare" -> {
                Path scenarios = Path.of(opts.getOrDefault("scenarios", DEFAULT_SCENARIOS));
                if (!java.nio.file.Files.isRegularFile(scenarios))
                    throw new ConfigException("scenarios file not found: " + scenarios.toAbsolutePath());
                int repeats = requireInt(opts, "repeats", 1);
                if (repeats < 1) throw new ConfigException("repeats must be >= 1, got: " + repeats);
                validateAdapter(opts.get("adapter"));
                validateOtel(opts);
                validateJudge(opts);
                String approved = opts.get("approved");
                if (approved != null && !java.nio.file.Files.isRegularFile(Path.of(approved)))
                    throw new ConfigException("approved-baseline artifact not found: "
                            + Path.of(approved).toAbsolutePath());
            }
            case "approve" -> {
                String compare = opts.get("compare");
                if (compare == null || compare.isBlank())
                    throw new ConfigException("approve requires --compare PATH (a compare --out artifact)");
                if (!java.nio.file.Files.isRegularFile(Path.of(compare)))
                    throw new ConfigException("compare artifact not found: " + Path.of(compare).toAbsolutePath());
                if (opts.get("out") == null || opts.get("out").isBlank())
                    throw new ConfigException("approve requires --out PATH for the approved-baseline artifact");
            }
            case "serve" -> {
                int port = requireInt(opts, "port", 8080);
                if (port < 1 || port > 65535)
                    throw new ConfigException("port must be within 1-65535, got: " + port);
                String artifacts = opts.get("artifacts");
                if (artifacts != null && !artifacts.isBlank()) {
                    for (String part : artifacts.split(",")) {
                        String trimmed = part.strip();
                        if (!trimmed.isEmpty() && !java.nio.file.Files.isRegularFile(Path.of(trimmed)))
                            throw new ConfigException("artifact not found: "
                                    + Path.of(trimmed).toAbsolutePath());
                    }
                }
                String scenariosOut = opts.get("scenarios-out");
                if (scenariosOut != null && !scenariosOut.isBlank()
                        && java.nio.file.Files.isDirectory(Path.of(scenariosOut)))
                    throw new ConfigException("--scenarios-out must be a .json file, got a directory: "
                            + Path.of(scenariosOut).toAbsolutePath());
            }
            case "otel-receive" -> {
                int port = requireInt(opts, "port", 4318);
                if (port < 1 || port > 65535)
                    throw new ConfigException("port must be within 1-65535, got: " + port);
            }
            case "show", "report" -> {
                String db = opts.get("db");
                if (db != null && !java.nio.file.Files.isRegularFile(Path.of(db)))
                    throw new ConfigException("database not found (run something first): " + db);
            }
            case "import" -> {
                validateOtel(opts);
                validateJudge(opts);
            }
            case "scores" -> {
                boolean hasOut = opts.get("out") != null && !opts.get("out").isBlank();
                boolean hasReduce = opts.get("reduce") != null && !opts.get("reduce").isBlank();
                if (hasOut == hasReduce)
                    throw new ConfigException("scores requires exactly one of "
                            + "--out PATH (capture raw scores from --db) or --reduce PATH (recompute metrics from an artifact)");
                if (hasReduce && !java.nio.file.Files.isRegularFile(Path.of(opts.get("reduce"))))
                    throw new ConfigException("scores artifact not found: "
                            + Path.of(opts.get("reduce")).toAbsolutePath());
                if (requireInt(opts, "at-least", 1) < 1)
                    throw new ConfigException("at-least must be >= 1");
            }
            case "opsbench-import" -> {
                Path root = Path.of(opts.getOrDefault("root", ""));
                if (opts.get("root") == null || opts.get("root").isBlank()
                        || !java.nio.file.Files.isDirectory(root))
                    throw new ConfigException("opsbench-import requires --root PATH "
                            + "(a Cloud-OpsBench checkout containing benchmark/)");
                if (opts.get("out") == null || opts.get("out").isBlank())
                    throw new ConfigException("opsbench-import requires --out PATH "
                            + "(the scenarios JSON file to write)");
                if (requireInt(opts, "limit", 0) < 0)
                    throw new ConfigException("limit must be >= 0 (0 = all cases)");
            }
            case "judge-calibrate" -> {
                int samples = requireInt(opts, "samples", 2);
                if (samples < 1) throw new ConfigException("samples must be >= 1, got: " + samples);
                String corpus = opts.get("corpus");
                if (corpus != null && !java.nio.file.Files.isRegularFile(Path.of(corpus)))
                    throw new ConfigException("corpus file not found: " + Path.of(corpus).toAbsolutePath());
                validateJudge(opts);
            }
            case "demo", "aiopslab-catalog" -> { }
            case "diagnose-calibrate" -> {
                String ruleSpec = opts.get("disable-rule");
                if (ruleSpec != null && !ruleSpec.isBlank()) {
                    for (String rule : ruleSpec.split(",")) {
                        if (!com.agentlens.eval.DiagnosisEngine.RULE_NAMES.contains(rule.trim())) {
                            throw new ConfigException("unknown diagnosis rule: " + rule.trim()
                                    + " (available: " + com.agentlens.eval.DiagnosisEngine.RULE_NAMES + ")");
                        }
                    }
                }
            }
            default -> { } // unknown command is reported by the dispatcher
        }
    }

    private static void validateAdapter(String adapterName) {
        if (adapterName == null || adapterName.isBlank() || adapterName.equals("llm")) return;
        try {
            AdapterRegistry.load().get(adapterName);
        } catch (RuntimeException e) {
            throw new ConfigException(e.getMessage());
        }
    }

    private static void validateOtel(Map<String, String> opts) {
        String endpoint = opts.get("otel-endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(endpoint);
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    throw new IllegalArgumentException("scheme must be http/https");
            } catch (RuntimeException e) {
                throw new ConfigException("otel-endpoint must be an http(s) URL, got: " + endpoint);
            }
        }
        int otelTimeout = requireInt(opts, "otel-timeout-sec", 10);
        if (otelTimeout < 1) throw new ConfigException("otel-timeout-sec must be >= 1, got: " + otelTimeout);
    }

    private static void validateJudge(Map<String, String> opts) {
        String url = opts.getOrDefault("judge-url", System.getenv("AGENTLENS_JUDGE_URL"));
        if (url == null || url.isBlank()) return;
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("scheme must be http/https");
        } catch (RuntimeException e) {
            throw new ConfigException("judge-url must be an http(s) URL, got: " + url);
        }
    }

    /** Strict int option: absent -> default; garbage or out of contract -> ConfigException. */
    static int requireInt(Map<String, String> opts, String key, int defaultVal) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be an integer, got: " + v);
        }
    }

    /** Loads the scenario dataset, runs it with the mock agent, aggregates pass^k and gates. */
    private static void run(Map<String, String> opts) throws Exception {
        Path scenariosPath = Path.of(opts.getOrDefault("scenarios", DEFAULT_SCENARIOS));
        int repeats = requireInt(opts, "repeats", 1);
        Set<String> extraForbidden = csv(opts.get("forbidden"));
        Set<String> extraAllowed = csv(opts.get("allowed"));

        List<Scenario> scenarios = new ScenarioLoader().load(scenariosPath);
        System.out.printf("loaded %d scenarios from %s, repeats=%d%n", scenarios.size(), scenariosPath, repeats);

        AdapterRegistry registry = AdapterRegistry.load();
        String adapterName = opts.getOrDefault("adapter", commonAdapter(scenarios));
        AgentAdapter adapter;
        if ("llm".equals(adapterName)) {
            adapter = llmAdapter(opts);
            System.out.printf("adapter: llm (model=%s, endpoint=%s)%n",
                    opts.getOrDefault("model", "qwen2.5:7b"), opts.getOrDefault("base-url", "http://localhost:11434"));
        } else {
            adapter = registry.get(adapterName);
            System.out.printf("adapter: %s (available: %s)%n", adapterName, registry.names());
        }

        boolean live = "true".equalsIgnoreCase(opts.getOrDefault("live", "false"))
                || "dsh".equals(adapterName) && "true".equalsIgnoreCase(opts.getOrDefault("abort-on-violation", "false"));
        if (adapter instanceof com.agentlens.adapters.dsh.DshAgentAdapter dsh && live) {
            dshLiveRun(dsh, scenarios, opts);
            return;
        }

        List<AgentTask> tasks = scenarios.stream().map(Scenario::toTask).toList();
        var evaluators = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(extraAllowed, extraForbidden))
                .register(new ComplianceEvaluator())
                .register(new SuccessAssertionEvaluator());
        if (opts.containsKey("judge")) evaluators.register(judgeEvaluator(opts));
        if (opts.containsKey("deepeval")) evaluators.register(deepEvalEvaluator(opts));
        var runner = withOtel(withSqlite(new ExperimentRunner(adapter, evaluators,
                new com.agentlens.eval.JsonlRunStore(Path.of("output/runs.jsonl")), 4),
                opts.getOrDefault("db", DEFAULT_DB)), opts);

        var batch = runner.run(tasks);
        System.out.printf("single batch: runs=%d, failures=%d, p50=%dms, p95=%dms, max=%dms%n",
                batch.runs().size(), batch.failures().size(),
                batch.stats().p50DurationMs(), batch.stats().p95DurationMs(), batch.stats().maxDurationMs());
        batch.failures().forEach(f -> System.out.printf("  task-failure: %s: %s%n", f.taskId(), f.error()));

        Map<String, List<List<com.agentlens.core.EvaluationResult>>> byTask = repeats > 1
                ? runner.runRepeated(tasks, repeats)
                : singleRunAsRepeated(batch);
        printPassKSummary(PassK.summarize(byTask));

        var gate = new RegressionGate(List.of("tool-policy"), 0.0).requireStable(PassK.summarize(byTask));
        System.out.printf("%nrelease gate: passed=%s, reason=%s%n", gate.passed(), gate.reason());
        logGate("run", gate);
        gateExit("run", gate);
    }

    /**
     * ROADMAP P2-4 (mini web dashboard): serves the SQLite run store at
     * http://127.0.0.1:{port}/ - runs list with filters, pass^k layers, trend
     * bars, run detail with trace timeline / evaluations / live-computed
     * diagnosis evidence, and baseline-vs-candidate version compare.
     * Read-only; Ctrl+C stops the server.
     */
    private static void serve(Map<String, String> opts) throws Exception {
        Path db = Path.of(opts.getOrDefault("db", DEFAULT_DB));
        int port = requireInt(opts, "port", 8080);
        String artifacts = opts.get("artifacts");
        List<Path> artifactPaths = artifacts == null || artifacts.isBlank()
                ? DashboardServer.defaultArtifacts()
                : java.util.Arrays.stream(artifacts.split(",")).map(String::strip)
                        .filter(s -> !s.isEmpty()).map(Path::of).toList();
        var sqlite = new com.agentlens.eval.SqliteRunStore(db);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sqlite.close();
            } catch (Exception e) {
                System.err.println("warning: failed to close sqlite store: " + e);
            }
        }));
        var dashboard = new DashboardServer(sqlite, port, artifactPaths,
                opts.get("scenarios-out") == null || opts.get("scenarios-out").isBlank()
                        ? null : Path.of(opts.get("scenarios-out")));
        System.out.printf("dashboard: %s  (db=%s, artifacts=%d, scenarios-out=%s) - Ctrl+C to stop%n",
                dashboard.baseUrl() + "/", db.toAbsolutePath(), artifactPaths.size(),
                opts.get("scenarios-out") == null ? "(disabled)" : opts.get("scenarios-out"));
        log.event("info", "dashboard.start", "url", dashboard.baseUrl() + "/",
                "db", db.toAbsolutePath().toString());
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /**
     * P0-B: OTLP/JSON ingestion - agents instrumented with OpenTelemetry point
     * their exporter at this endpoint; each trace becomes a persisted, evaluated
     * run (zero adapter code per new agent). Ingested runs share the store, so
     * show/report/serve surface them like native runs.
     */
    private static void otelReceive(Map<String, String> opts) throws Exception {
        Path db = Path.of(opts.getOrDefault("db", DEFAULT_DB));
        int port = requireInt(opts, "port", 4318);
        var sqlite = new com.agentlens.eval.SqliteRunStore(db);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sqlite.close();
            } catch (Exception e) {
                System.err.println("warning: failed to close sqlite store: " + e);
            }
        }));
        var receiver = new OtelReceiveServer(sqlite, port);
        receiver.start();
        System.out.printf("otel-receive: %s/v1/traces  (db=%s) - Ctrl+C to stop%n",
                receiver.baseUrl(), db.toAbsolutePath());
        System.out.printf("point OTLP/HTTP exporters here (gen_ai.* / OpenInference attributes are understood)%n");
        log.event("info", "otel.receive.start", "url", receiver.baseUrl() + "/v1/traces",
                "db", db.toAbsolutePath().toString());
        new java.util.concurrent.CountDownLatch(1).await();
    }

    /**
     * Release comparison: runs the same fixed dataset with baseline and candidate
     * agent versions, then gates on pass^k / avg pass rate regression.
     *
     * <p>With {@code --adapter dsh} both sides are real headless DSH sessions of
     * the same scenario set (the controlled, non-live path): two independent
     * release measurements of the agent, compared by the same gate. Sessions run
     * sequentially (parallel sessions in one workdir would race on artifacts);
     * each run is persisted with agentVersion=baseline/candidate.
     *
     * <p>Per-side configuration (ROADMAP 7.2): {@code --baseline-override} and
     * {@code --candidate-override} take comma-separated key=value entries merged
     * into that side's task context only - e.g. {@code --candidate-override
     * "dshArgs=--model qwen2.5:3b"} runs the candidate on a smaller model, making
     * a REAL degraded candidate possible (the pass^k gate should reject it). The
     * effective model recorded in each trace (MODEL_CALL attributes) is printed
     * and persisted per run, so the gate decision stays auditable.
     */
    private static void compare(Map<String, String> opts) throws Exception {
        Path scenariosPath = Path.of(opts.getOrDefault("scenarios", DEFAULT_SCENARIOS));
        String adapterName = opts.getOrDefault("adapter", "mock");
        // dsh sessions cost real quota: default to a single repeat unless asked otherwise.
        int repeats = requireInt(opts, "repeats", "dsh".equals(adapterName) ? 1 : 5);
        String baselineBehavior = opts.getOrDefault("baseline", "correct");
        String candidateBehavior = opts.getOrDefault("candidate", "flaky");
        Map<String, String> baselineOverrides = parseOverrides(opts.get("baseline-override"));
        Map<String, String> candidateOverrides = parseOverrides(opts.get("candidate-override"));

        List<Scenario> scenarios = new ScenarioLoader().load(scenariosPath);
        List<AgentTask> tasks = scenarios.stream().map(Scenario::toTask).toList();
        List<String> evaluatorNames = evaluatorNames(opts);
        String datasetSha = ApprovedBaseline.sha256Hex(scenariosPath);
        System.out.printf("comparing releases on %d scenarios from %s, repeats=%d, adapter=%s%n",
                scenarios.size(), scenariosPath, repeats, adapterName);
        if (!baselineOverrides.isEmpty())
            System.out.printf("baseline overrides: %s%n", baselineOverrides);
        if (!candidateOverrides.isEmpty())
            System.out.printf("candidate overrides: %s%n", candidateOverrides);
        if ("dsh".equals(adapterName)) {
            System.out.printf("estimated dsh sessions: %d (sides=2 x scenarios=%d x repeats=%d)%n",
                    2 * scenarios.size() * repeats, scenarios.size(), repeats);
        }

        // P0-D: with an approved baseline, the frozen artifact replaces the live
        // baseline side (Langfuse double-publish): dataset and evaluator set are
        // checked BEFORE any agent runs, so a drifted setup fails fast at zero cost.
        ApprovedBaseline.Baseline approved = null;
        if (opts.get("approved") != null) {
            try {
                approved = ApprovedBaseline.load(Path.of(opts.get("approved")));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(e.getMessage());
            }
            if (!approved.datasetSha256().equals(datasetSha))
                throw new ConfigException(String.format(
                        "approved baseline was pinned to a different dataset (approved sha=%s, current sha=%s)",
                        approved.datasetSha256(), datasetSha));
            var expected = new java.util.TreeSet<>(approved.evaluatorNames());
            var actual = new java.util.TreeSet<>(evaluatorNames);
            if (!expected.equals(actual))
                throw new ConfigException(String.format(
                        "evaluator set changed since approval %s -> %s (pin the evaluator set or re-approve)",
                        expected, actual));
            System.out.printf("approved baseline: %s (pass^k=%.3f, avgPassRate=%.3f, approvedBy=%s)%n",
                    opts.get("approved"), approved.passPowK(), approved.avgPassRate(), approved.approvedBy());
        }

        VersionOutcome baseOutcome, candOutcome;
        String baseLabel, candLabel;
        if (approved != null) {
            candOutcome = runCompareSide(adapterName, "candidate",
                    candidateBehavior, candidateOverrides, scenarios, tasks, repeats, opts);
            candLabel = "dsh".equals(adapterName) ? "dsh-headless" : "mock-" + candidateBehavior;
            baseOutcome = null;
            baseLabel = "approved:" + Path.of(opts.get("approved")).getFileName();
        } else if ("dsh".equals(adapterName)) {
            var dsh = (com.agentlens.adapters.dsh.DshAgentAdapter) AdapterRegistry.load().get("dsh");
            baseOutcome = evaluateDshVersion(dsh, scenarios, repeats, "baseline", baselineOverrides, opts);
            candOutcome = evaluateDshVersion(dsh, scenarios, repeats, "candidate", candidateOverrides, opts);
            baseLabel = "dsh-headless";
            candLabel = "dsh-headless";
        } else {
            baseOutcome = evaluateVersion(baselineBehavior,
                    applyAll(tasks, baselineOverrides), repeats, opts);
            candOutcome = evaluateVersion(candidateBehavior,
                    applyAll(tasks, candidateOverrides), repeats, opts);
            baseLabel = "mock-" + baselineBehavior;
            candLabel = "mock-" + candidateBehavior;
        }
        PassK.Summary baseSummary = baseOutcome == null ? null : baseOutcome.summary();
        PassK.Summary candSummary = candOutcome.summary();

        System.out.printf("%n%-10s %-22s pass^k=%.3f  avgPassRate=%.3f%n",
                "baseline", baseLabel,
                baseSummary != null ? baseSummary.passPowK() : approved.passPowK(),
                baseSummary != null ? baseSummary.avgPassRate() : approved.avgPassRate());
        System.out.printf("%-10s %-22s pass^k=%.3f  avgPassRate=%.3f%n",
                "candidate", candLabel, candSummary.passPowK(), candSummary.avgPassRate());

        printLayered("candidate", candSummary);

        RegressionGate.GateResult gate = approved != null
                ? ApprovedGate.check(approved, datasetSha, evaluatorNames, candSummary)
                : new RegressionGate(List.of("tool-policy"), 0.0).compare(baseSummary, candSummary);
        System.out.printf("%nrelease gate: passed=%s%n  reason=%s%n", gate.passed(), gate.reason());
        logGate("compare", gate);

        // P1-B: persist the candidate's RAW k-times outcomes so the metrics can be
        // recomputed later (pass@k / pass^k / at-least-m) without another run.
        if (opts.get("scores-out") != null && !opts.get("scores-out").isBlank()) {
            if (candOutcome.outcomes() == null) {
                System.out.println("scores-out: candidate side has no raw outcomes (approved-baseline path) - skipped");
            } else {
                new com.agentlens.eval.ScoreRecord(com.agentlens.eval.ScoreRecord.FORMAT_VERSION,
                        candLabel, repeats, candOutcome.outcomes(), null)
                        .write(Path.of(opts.get("scores-out")));
                System.out.printf("raw scores written: %s (cases=%d, repeats=%d)%n",
                        Path.of(opts.get("scores-out")).toAbsolutePath(),
                        candOutcome.outcomes().size(), repeats);
            }
        }

        // P1-B: pass^k + evidence chain injected into an LLM gate review (fail-open).
        String review = null;
        if (opts.containsKey("review")) {
            review = reviewGate(opts, baseLabel, baseOutcome, candLabel, candOutcome, gate);
            System.out.printf("%ngate review:%n%s%n", review);
        }

        if (opts.containsKey("out"))
            writeCompareArtifact(Path.of(opts.get("out")), scenariosPath, datasetSha, adapterName,
                    repeats, evaluatorNames, baseLabel, baseSummary, candLabel, candSummary, gate, review);
        gateExit("compare", gate);
    }

    /**
     * P1-B: asks the configured judge for a narrative gate review built from the
     * verdict, both sides' metrics and the candidate's failing-run evidence.
     * Without --judge (or on judge failure) falls back to the deterministic
     * summary - the review never blocks or crashes the gate.
     */
    private static String reviewGate(Map<String, String> opts, String baseLabel, VersionOutcome base,
                                     String candLabel, VersionOutcome cand,
                                     RegressionGate.GateResult gate) {
        Map<String, String> evidence = GateReview.firstFailureReasons(
                cand == null ? null : cand.rawResults());
        PassK.Summary baseSummary = base == null ? null : base.summary();
        PassK.Summary candSummary = cand == null ? null : cand.summary();
        if (!opts.containsKey("judge")) {
            return GateReview.fallbackReview(baseLabel, candLabel, baseSummary, candSummary, gate, evidence);
        }
        try {
            return buildJudge(opts).review(
                    GateReview.buildPrompt(baseLabel, candLabel, baseSummary, candSummary, gate, evidence));
        } catch (Exception e) {
            return "LLM review unavailable (" + e.getMessage() + "); deterministic summary follows.\n"
                    + GateReview.fallbackReview(baseLabel, candLabel, baseSummary, candSummary, gate, evidence);
        }
    }

    /** One compare side's aggregates plus the RAW outcomes that produced them (P1-B). */
    private record VersionOutcome(PassK.Summary summary,
                                  Map<String, List<Boolean>> outcomes,
                                  Map<String, List<List<com.agentlens.core.EvaluationResult>>> rawResults) {
        /** Sides without raw outcomes (frozen baselines). */
        static VersionOutcome of(PassK.Summary summary) {
            return new VersionOutcome(summary, null, null);
        }
    }

    /** Runs one compare side (mock behaviors or real dsh sessions). */
    private static VersionOutcome runCompareSide(String adapterName, String version, String behavior,
                                                 Map<String, String> overrides, List<Scenario> scenarios,
                                                 List<AgentTask> tasks, int repeats,
                                                 Map<String, String> opts) throws Exception {
        if ("dsh".equals(adapterName)) {
            var dsh = (com.agentlens.adapters.dsh.DshAgentAdapter) AdapterRegistry.load().get("dsh");
            return evaluateDshVersion(dsh, scenarios, repeats, version, overrides, opts);
        }
        return evaluateVersion(behavior, applyAll(tasks, overrides), repeats, opts);
    }

    /** Evaluator set names implied by the CLI flags (must match the registries actually built). */
    private static List<String> evaluatorNames(Map<String, String> opts) {
        List<String> names = new ArrayList<>(List.of("task-completion", "run-limits",
                "tool-policy", "compliance"));
        if (opts.containsKey("judge")) names.add(com.agentlens.eval.LlmJudgeEvaluator.EVALUATOR_NAME);
        if (opts.containsKey("deepeval")) names.add(com.agentlens.eval.DeepEvalAdapter.EVALUATOR_NAME);
        return List.copyOf(names);
    }

    /** Machine-readable comparison artifact (the input for `approve`). */
    private record CompareArtifact(String generatedAt, DatasetRef dataset, String adapter,
                                   int repeats, List<String> evaluators,
                                   SideDoc baseline, SideDoc candidate, GateDoc gate,
                                   String review) {
        record DatasetRef(String path, String sha256) {}
        record SideDoc(String label, Double passPowK, Double avgPassRate, Map<String, Double> cases) {}
        record GateDoc(boolean passed, String reason) {}
    }

    private static void writeCompareArtifact(Path out, Path scenariosPath, String datasetSha,
                                             String adapterName, int repeats,
                                             List<String> evaluatorNames,
                                             String baseLabel, PassK.Summary baseSummary,
                                             String candLabel, PassK.Summary candSummary,
                                             RegressionGate.GateResult gate,
                                             String review) throws Exception {
        var artifact = new CompareArtifact(java.time.Instant.now().toString(),
                new CompareArtifact.DatasetRef(scenariosPath.toString(), datasetSha),
                adapterName, repeats, evaluatorNames,
                sideDoc(baseLabel, baseSummary), sideDoc(candLabel, candSummary),
                new CompareArtifact.GateDoc(gate.passed(), gate.reason()), review);
        Path parent = out.getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
        java.nio.file.Files.writeString(out,
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(artifact));
        System.out.printf("compare artifact written: %s%n", out.toAbsolutePath());
    }

    /** One comparison side as the artifact carries it (null when using an approved baseline). */
    private static CompareArtifact.SideDoc sideDoc(String label, PassK.Summary summary) {
        if (summary == null) return null;
        var cases = new java.util.LinkedHashMap<String, Double>();
        summary.perScenario().forEach(s -> cases.put(s.scenarioId(), s.passRate()));
        return new CompareArtifact.SideDoc(label, summary.passPowK(), summary.avgPassRate(), cases);
    }

    /**
     * P0-D: promotes a reviewed comparison artifact to the approved baseline
     * (the second publish in the Langfuse double-publish flow). The artifact
     * pins dataset sha256, evaluator set, repeats, and per-case pass rates;
     * future `compare --approved` runs gate against it instead of a live
     * baseline re-run.
     */
    private static void approve(Map<String, String> opts) throws Exception {
        Path artifactPath = Path.of(opts.get("compare"));
        Path out = Path.of(opts.get("out"));
        var artifact = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(artifactPath.toFile(), CompareArtifact.class);
        if (artifact.candidate() == null || artifact.candidate().cases() == null
                || artifact.candidate().cases().isEmpty())
            throw new ConfigException("compare artifact has no candidate side to approve: " + artifactPath);
        var baseline = new ApprovedBaseline.Baseline(ApprovedBaseline.FORMAT_VERSION,
                artifact.adapter(), artifact.dataset().sha256(), artifact.evaluators(),
                artifact.repeats(), artifact.candidate().passPowK(), artifact.candidate().avgPassRate(),
                artifact.candidate().cases(), opts.getOrDefault("by", "human-review"),
                java.time.Instant.now().toString());
        ApprovedBaseline.write(baseline, out);
        System.out.printf("approved baseline written: %s%n  agent=%s dataset sha=%s cases=%d"
                        + " pass^k=%.3f avgPassRate=%.3f approvedBy=%s%n",
                out.toAbsolutePath(), artifact.adapter(), artifact.dataset().sha256(),
                baseline.casePassRates().size(), baseline.passPowK(), baseline.avgPassRate(),
                baseline.approvedBy());
        log.event("info", "baseline.approved", "artifact", out.toAbsolutePath().toString(),
                "datasetSha256", artifact.dataset().sha256(), "cases", baseline.casePassRates().size());
    }

    /**
     * P0-D exit-code contract (promptfoo-style separation): 0 = gate passed,
     * 100 = quality regression (the gate failed), 1 = tool/infrastructure error
     * (uncaught exception), 2 = config error (ConfigException). CI jobs can
     * distinguish "the agent got worse" from "the harness broke".
     */
    private static void gateExit(String scope, RegressionGate.GateResult gate) {
        if (gate.passed()) return;
        log.event("error", "gate.regression", "scope", scope, "reason", gate.reason());
        System.err.println("gate regression (exit 100): " + gate.reason());
        System.err.println("exit-code contract: 0=pass, 100=quality regression, 1=tool error, 2=config error");
        System.exit(100);
    }

    /**
     * One compare side on the real DSH agent: runs the scenario set sequentially
     * through headless sessions, evaluates + diagnoses + persists each run, and
     * aggregates the pass^k summary. {@code version} tags the persisted runs
     * (agentVersion=baseline/candidate) so show/report can tell the sides apart.
     * {@code sideOverrides} is merged into every task context of this side only
     * (per-side model/config); each run prints the provider/model the session
     * actually used, and both the overrides and the observed model land in the
     * evaluation metadata for auditability.
     */
    private static VersionOutcome evaluateDshVersion(com.agentlens.adapters.dsh.DshAgentAdapter dsh,
                                                     List<Scenario> scenarios, int repeats, String version,
                                                     Map<String, String> sideOverrides,
                                                     Map<String, String> opts) throws Exception {
        var registry = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of()))
                .register(new ComplianceEvaluator())
                .register(new SuccessAssertionEvaluator());
        if (opts.containsKey("judge")) registry.register(judgeEvaluator(opts));
        if (opts.containsKey("deepeval")) registry.register(deepEvalEvaluator(opts));
        var diagnosis = new com.agentlens.eval.DiagnosisEngine();
        Map<String, List<List<com.agentlens.core.EvaluationResult>>> byTask = new java.util.LinkedHashMap<>();
        java.util.function.Consumer<AgentRun> otel = otelSink(opts);
        try (var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(opts.getOrDefault("db", DEFAULT_DB)))) {
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (Scenario scenario : scenarios) {
                    var task = withOverrides(scenario.toTask(), sideOverrides);
                    System.out.printf("%n[%s] repeat %d/%d: %s%n", version, repeat, repeats, task.id());
                    AgentRun run = dsh.run(task);
                    String model = observedModel(run);
                    var results = registry.evaluate(task, run);
                    var diagnoses = diagnosis.diagnose(task, run, results);
                    System.out.printf("  => status=%s duration=%dms events=%d diagnoses=%d model=%s%n",
                            run.status(), run.durationMs(), run.events().size(), diagnoses.size(),
                            model != null ? model : "n/a");
                    diagnoses.forEach(d -> System.out.printf("     ! %-13s %s%n", d.layer(), d.issue()));
                    var meta = new java.util.LinkedHashMap<String, Object>();
                    meta.put("durationMs", run.durationMs());
                    meta.put("totalTokens", run.totalTokens());
                    if (!sideOverrides.isEmpty()) meta.put("sideOverrides", sideOverrides);
                    if (model != null) meta.put("observedModel", model);
                    sqlite.saveRun(run, dsh.name());
                    sqlite.saveEvaluation(new com.agentlens.core.EvaluationRun(run.runId(), task.id(),
                            dsh.name(), version, run.startedAt(), results, meta));
                    if (otel != null) otel.accept(run);
                    byTask.computeIfAbsent(task.id(), t -> new ArrayList<>()).add(results);
                }
            }
        }
        // P1-B: dsh sessions carry raw outcomes too (repeat loop is in this method).
        Map<String, List<Boolean>> outcomes = new java.util.LinkedHashMap<>();
        byTask.forEach((taskId, repeatResults) -> outcomes.put(taskId, repeatResults.stream()
                .map(r -> !r.isEmpty() && r.stream().allMatch(com.agentlens.core.EvaluationResult::passed))
                .toList()));
        return new VersionOutcome(PassK.summarize(byTask), outcomes, byTask);
    }

    /** Applies the same context overrides to every task of a side. */
    private static List<AgentTask> applyAll(List<AgentTask> tasks, Map<String, String> overrides) {
        return overrides.isEmpty() ? tasks : tasks.stream().map(t -> withOverrides(t, overrides)).toList();
    }

    private static VersionOutcome evaluateVersion(String behavior, List<AgentTask> tasks, int repeats,
                                                  Map<String, String> opts)
            throws Exception {
        var runner = withOtel(withSqlite(new ExperimentRunner(new com.agentlens.adapters.MockAgentAdapter(behavior),
                new EvaluatorRegistry()
                        .register(new TaskCompletionEvaluator())
                        .register(new RunLimitEvaluator())
                        .register(new ToolPolicyEvaluator(Set.of(), Set.of()))
                        .register(new ComplianceEvaluator())
                        .register(new SuccessAssertionEvaluator()),
                new com.agentlens.eval.JsonlRunStore(Path.of("output/runs.jsonl")), 4)
                .withAgentVersion("mock-" + behavior),
                opts.getOrDefault("db", DEFAULT_DB)), opts);
        var byTask = runner.runRepeated(tasks, repeats);
        // P1-B: keep the RAW per-case outcomes (one boolean per repeat) alongside
        // the aggregate - they are what makes post-hoc metric recomputation free.
        Map<String, List<Boolean>> outcomes = new java.util.LinkedHashMap<>();
        byTask.forEach((taskId, repeatResults) -> outcomes.put(taskId, repeatResults.stream()
                .map(r -> !r.isEmpty() && r.stream().allMatch(com.agentlens.core.EvaluationResult::passed))
                .toList()));
        return new VersionOutcome(PassK.summarize(byTask), outcomes, byTask);
    }

    /**
     * P1-B 口径对齐: capture the RAW k-times scores as an artifact (--out) or
     * recompute metrics from one (--reduce) - changing the pass@k / pass^k /
     * at-least-m question never requires re-running the agent.
     */
    /**
     * P1-C: imports LLM4Ops/Cloud-OpsBench (754 real K8s RCA cases, MIT) into
     * a ScenarioLoader-readable scenarios file - snapshots replay, no cluster.
     */
    private static void opsbenchImport(Map<String, String> opts) throws Exception {
        Path root = Path.of(opts.get("root"));
        Path out = Path.of(opts.get("out"));
        int limit = requireInt(opts, "limit", 0);
        var importer = new CloudOpsBenchImporter();
        List<Scenario> scenarios = new ArrayList<>();
        CloudOpsBenchImporter.ImportSummary summary = importer.importRoot(root, limit, scenarios);
        if (summary.imported() == 0) {
            throw new ConfigException("no cases imported from " + root.toAbsolutePath()
                    + (summary.skipReasons().isEmpty() ? "" : " - first reason: "
                    + summary.skipReasons().get(0)));
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        Path parent = out.getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
        java.nio.file.Files.writeString(out,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(scenarios));
        System.out.printf("opsbench import: %d cases imported, %d skipped -> %s%n",
                summary.imported(), summary.skipped(), out.toAbsolutePath());
        summary.byNamespace().forEach((ns, n) -> System.out.printf("  %-12s %d cases%n", ns, n));
        summary.byDifficulty().forEach((d, n) -> System.out.printf("  %-12s %d cases%n", d, n));
        summary.skipReasons().stream().limit(5)
                .forEach(r -> System.out.println("  skipped: " + r));
        // round-trip guarantee: the file must load exactly like run --scenarios would
        List<Scenario> reloaded = new ScenarioLoader().load(out);
        System.out.printf("validated by ScenarioLoader: %d scenarios loadable%n", reloaded.size());
    }

    private static void scores(Map<String, String> opts) throws Exception {
        if (opts.get("out") != null && !opts.get("out").isBlank()) {
            Path db = Path.of(opts.getOrDefault("db", DEFAULT_DB));
            if (!java.nio.file.Files.isRegularFile(db))
                throw new ConfigException("database not found (run something first): " + db.toAbsolutePath());
            String version = opts.get("version");
            try (var store = new com.agentlens.eval.SqliteRunStore(db)) {
                var record = com.agentlens.eval.ScoreRecord.fromStore(store,
                        version == null ? "all" : version, version);
                if (record.caseOutcomes().isEmpty()) {
                    System.out.printf("no evaluated runs found in %s%s - nothing to capture%n",
                            db.toAbsolutePath(),
                            version == null ? "" : " for version " + version);
                    return;
                }
                record.write(Path.of(opts.get("out")));
                System.out.printf("raw scores captured: %s (label=%s, cases=%d, repeats=%d)%n",
                        Path.of(opts.get("out")).toAbsolutePath(), record.label(),
                        record.caseOutcomes().size(), record.repeats());
                printPassKSummary(record.summarize());
            }
            return;
        }
        var record = com.agentlens.eval.ScoreRecord.read(Path.of(opts.get("reduce")));
        int atLeast = requireInt(opts, "at-least", 1);
        System.out.printf("score record: label=%s cases=%d repeats=%d created=%s%n",
                record.label(), record.caseOutcomes().size(), record.repeats(), record.createdAt());
        record.caseOutcomes().forEach((id, outcomes) -> System.out.printf(
                "  %-30s k=%d passed=%d rate=%.3f pass@k=%-5s pass^k=%-5s at-least-%d=%s%n",
                id, outcomes.size(),
                outcomes.stream().filter(Boolean::booleanValue).count(),
                PassK.rawPassRate(outcomes),
                PassK.rawPassAtK(outcomes),
                PassK.rawPassPowK(outcomes),
                atLeast, PassK.rawPassAtLeast(outcomes, atLeast)));
        printPassKSummary(record.summarize());
        System.out.printf("at-least-%d share: %.3f%n", atLeast,
                PassK.rawAtLeastShare(record.caseOutcomes(), atLeast));
    }

    private static void printLayered(String label, PassK.Summary summary) {
        for (var layer : List.of(PassK.FailureLayer.STABLE_PASS,
                PassK.FailureLayer.FLAKY, PassK.FailureLayer.STABLE_FAIL)) {
            List<String> ids = summary.layered().getOrDefault(layer, List.of());
            if (!ids.isEmpty()) System.out.printf("  %s %-12s %d %s%n", label, layer, ids.size(),
                    ids.size() <= 8 ? ids : ids.subList(0, 8) + "...");
        }
    }

    /** Shared pass^k summary + layered failure view (batch run and live run print the same shape). */
    private static void printPassKSummary(PassK.Summary summary) {
        System.out.printf("%npass^k summary: scenarios=%d, pass^k=%.3f, avgPassRate=%.3f%n",
                summary.scenarios(), summary.passPowK(), summary.avgPassRate());
        for (var layer : List.of(PassK.FailureLayer.STABLE_PASS,
                PassK.FailureLayer.FLAKY, PassK.FailureLayer.STABLE_FAIL)) {
            List<String> ids = summary.layered().getOrDefault(layer, List.of());
            if (!ids.isEmpty()) System.out.printf("  %-12s %d %s%n", layer, ids.size(),
                    ids.size() <= 8 ? ids : ids.subList(0, 8) + "...");
        }
    }

    /** Shared judge backend construction from CLI options / environment (ROADMAP P2-1/T7). */
    private static com.agentlens.eval.LlmJudge buildJudge(Map<String, String> opts) {
        String url = opts.getOrDefault("judge-url",
                envOr("AGENTLENS_JUDGE_URL", "http://localhost:11434"));
        String model = opts.getOrDefault("judge-model",
                envOr("AGENTLENS_JUDGE_MODEL", "qwen2.5:7b"));
        String key = opts.containsKey("judge-api-key") ? opts.get("judge-api-key")
                : System.getenv("AGENTLENS_JUDGE_API_KEY");
        boolean jsonMode = opts.containsKey("judge-json-mode")
                || "true".equalsIgnoreCase(System.getenv("AGENTLENS_JUDGE_JSON_MODE"));
        boolean ollamaNative = opts.containsKey("judge-ollama")
                || "true".equalsIgnoreCase(System.getenv("AGENTLENS_JUDGE_OLLAMA"));
        return new com.agentlens.eval.OpenAiJudge(java.net.URI.create(url), model, key,
                0.0, java.time.Duration.ofSeconds(requireInt(opts, "judge-timeout-sec",
                        Integer.parseInt(envOr("AGENTLENS_JUDGE_TIMEOUT_SEC", "120")))),
                3, jsonMode, ollamaNative);
    }

    /** Builds the LLM judge evaluator from CLI options / environment (ROADMAP P2-1). */
    private static com.agentlens.eval.LlmJudgeEvaluator judgeEvaluator(Map<String, String> opts) {
        var judge = buildJudge(opts);
        String url = opts.getOrDefault("judge-url",
                envOr("AGENTLENS_JUDGE_URL", "http://localhost:11434"));
        String model = opts.getOrDefault("judge-model",
                envOr("AGENTLENS_JUDGE_MODEL", "qwen2.5:7b"));
        boolean jsonMode = opts.containsKey("judge-json-mode")
                || "true".equalsIgnoreCase(System.getenv("AGENTLENS_JUDGE_JSON_MODE"));
        boolean ollamaNative = opts.containsKey("judge-ollama")
                || "true".equalsIgnoreCase(System.getenv("AGENTLENS_JUDGE_OLLAMA"));
        System.out.printf("judge: %s (model=%s, url=%s, jsonMode=%s, ollamaNative=%s, criteria=%s, fail-open)%n",
                com.agentlens.eval.LlmJudgeEvaluator.EVALUATOR_NAME, model, url, jsonMode, ollamaNative,
                com.agentlens.eval.LlmJudgeEvaluator.CRITERIA.keySet());
        var criteria = new java.util.ArrayList<>(csv(opts.get("judge-criteria")));
        return criteria.isEmpty() ? new com.agentlens.eval.LlmJudgeEvaluator(judge)
                : new com.agentlens.eval.LlmJudgeEvaluator(judge, criteria);
    }

    /**
     * DeepEval-style metrics evaluator (board T7, phase 1): the canonical
     * faithfulness / answer_relevancy / hallucination rubrics on the shared
     * judge backend. Enabled with --deepeval, narrowed with --deepeval-metrics.
     */
    private static com.agentlens.eval.Evaluator deepEvalEvaluator(Map<String, String> opts) {
        var judge = buildJudge(opts);
        var selected = new java.util.ArrayList<>(csv(opts.get("deepeval-metrics")));
        System.out.printf("deepeval: metrics=%s (judge backend shared with --judge, fail-open)%n",
                selected.isEmpty() ? com.agentlens.eval.DeepEvalAdapter.METRICS.keySet() : selected);
        return selected.isEmpty() ? new com.agentlens.eval.DeepEvalAdapter(judge)
                : new com.agentlens.eval.DeepEvalAdapter(judge, selected);
    }

    /**
     * ROADMAP P2-1 calibration (board T6): runs the LLM judge over a labeled
     * mock-behavior corpus (ground truth = behavior semantics, deterministic runs,
     * zero agent-side cost; only the local judge model is called) and reports
     * accuracy / precision / recall / F1 / Cohen's kappa overall and per behavior.
     *
     * <p>Board P0-A: the corpus can be loaded from a versioned JSON file with
     * --corpus (see data/judge-calibration-corpus.json); entries carry their own
     * instruction, tool context, and ground truth, and the corpus version is
     * recorded in the output. Without --corpus the legacy hardcoded behavior set
     * is used (backwards compatible).
     */
    /**
     * Credibility plan S1: calibration harness for the rule-based diagnosis engine.
     * Runs the deterministic mock-behavior corpus (ground truth by construction) through
     * the engine, prints a markdown report and optionally writes a JSON artifact.
     * {@code --disable-rule A,B} ablates rules to measure each one's contribution.
     */
    private static void diagnoseCalibrate(Map<String, String> opts) throws Exception {
        java.util.Set<String> disabled = new java.util.LinkedHashSet<>();
        String ruleSpec = opts.get("disable-rule");
        if (ruleSpec != null && !ruleSpec.isBlank()) {
            for (String rule : ruleSpec.split(",")) {
                disabled.add(rule.trim());
            }
        }
        var engine = new com.agentlens.eval.DiagnosisEngine(disabled);
        var report = DiagnosisCalibration.calibrate(
                DiagnosisCalibration.defaultCorpus(), engine);
        System.out.print(DiagnosisCalibration.toMarkdown(report));
        String jsonOut = opts.get("json-out");
        if (jsonOut != null && !jsonOut.isBlank()) {
            Path out = Path.of(jsonOut);
            Path parent = out.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValue(out.toFile(), report);
            System.out.println("artifact: " + out.toAbsolutePath());
        }
        if (report.falsePositives().isEmpty() && report.matchedCases() == report.totalCases()) {
            System.out.printf("calibration result: PASS (%d/%d matched, 0 false positives)%n",
                    report.matchedCases(), report.totalCases());
        } else {
            System.out.printf("calibration result: FAIL (%d/%d matched, %d false positives)%n",
                    report.matchedCases(), report.totalCases(), report.falsePositives().size());
        }
    }

    private static void judgeCalibrate(Map<String, String> opts) throws Exception {
        int samples = requireInt(opts, "samples", 2);
        var mock = new com.agentlens.adapters.MockAgentAdapter();
        var evaluator = judgeEvaluator(opts);
        List<com.agentlens.eval.JudgeCalibration.Sample> samplesOut = new ArrayList<>();
        Integer corpusVersion = null;

        if (opts.containsKey("corpus")) {
            Path corpusPath = Path.of(opts.get("corpus"));
            var corpus = new com.agentlens.eval.CorpusLoader().load(corpusPath);
            corpusVersion = corpus.version();
            System.out.printf("corpus: %s (version %d, %d entries)%n",
                    corpusPath, corpus.version(), corpus.entries().size());
            for (var entry : corpus.entries()) {
                if (!com.agentlens.adapters.MockAgentAdapter.KNOWN_BEHAVIORS.contains(entry.behavior()))
                    throw new ConfigException("corpus entry " + entry.id()
                            + " has unknown behavior: " + entry.behavior());
                for (int i = 1; i <= samples; i++) {
                    var context = new java.util.LinkedHashMap<>(entry.context() == null
                            ? Map.of() : entry.context());
                    context.put("agentBehavior", entry.behavior());
                    var task = new com.agentlens.core.AgentTask(
                            entry.id() + "-" + i, entry.instruction(),
                            java.util.Collections.unmodifiableMap(context), 10);
                    samplesOut.add(judgeOnce(evaluator, mock, task, entry.behavior(),
                            entry.expectedPass(), i));
                }
            }
        } else {
            Set<String> wanted = csv(opts.getOrDefault("behaviors",
                    "correct,wrong_tool,forbidden_action,loop,cheat_tamper,cheat_hardcode"));
            // Ground truth: only the correct behavior should pass a well-calibrated judge.
            Map<String, Boolean> expectPass = Map.of(
                    "correct", true, "wrong_tool", false, "forbidden_action", false,
                    "loop", false, "cheat_tamper", false, "cheat_hardcode", false);
            for (var entry : expectPass.entrySet()) {
                String behavior = entry.getKey();
                if (!wanted.contains(behavior)) continue;
                for (int i = 1; i <= samples; i++) {
                    var task = new com.agentlens.core.AgentTask("calib-" + behavior + "-" + i,
                            "diagnose the reported symptom and propose a remediation",
                            Map.of("agentBehavior", behavior), 10);
                    samplesOut.add(judgeOnce(evaluator, mock, task, behavior, entry.getValue(), i));
                }
            }
        }

        var overall = com.agentlens.eval.JudgeCalibration.metrics(samplesOut);
        System.out.printf("%njudge calibration: n=%d  accuracy=%.3f precision=%.3f recall=%.3f f1=%.3f kappa=%.3f%n",
                samplesOut.size(), overall.accuracy(), overall.precision(), overall.recall(),
                overall.f1(), overall.kappa());
        System.out.printf("confusion: TP=%d TN=%d FP=%d FN=%d%n",
                overall.truePositive(), overall.trueNegative(), overall.falsePositive(), overall.falseNegative());
        var expectLookup = new java.util.LinkedHashMap<String, Boolean>();
        for (var s : samplesOut) expectLookup.putIfAbsent(s.behavior(), s.expectedPass());
        com.agentlens.eval.JudgeCalibration.perBehavior(samplesOut).forEach((behavior, m) ->
                System.out.printf("  %-20s n=%d acc=%.2f  judged-pass=%d/%d (expected %s)%n",
                        behavior, m.truePositive() + m.trueNegative() + m.falsePositive() + m.falseNegative(),
                        m.accuracy(),
                        m.truePositive() + m.falsePositive(),
                        m.truePositive() + m.trueNegative() + m.falsePositive() + m.falseNegative(),
                        expectLookup.get(behavior) ? "pass" : "fail"));

        // Persist machine-readable results + a human report.
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Path outJson = Path.of(opts.getOrDefault("out", "output/judge-calibration.json"));
        Path outParent = outJson.getParent();
        if (outParent != null) java.nio.file.Files.createDirectories(outParent);
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        if (corpusVersion != null) doc.put("corpusVersion", corpusVersion);
        doc.put("samples", samplesOut.stream().map(s -> Map.of(
                "behavior", s.behavior(), "expectedPass", s.expectedPass(),
                "judgedPass", s.judgedPass(), "score", s.score(), "reason", String.valueOf(s.reason()))).toList());
        doc.put("metrics", Map.of("accuracy", overall.accuracy(), "precision", overall.precision(),
                "recall", overall.recall(), "f1", overall.f1(), "kappa", overall.kappa()));
        java.nio.file.Files.writeString(outJson, json.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        System.out.printf("calibration written: %s%n", outJson.toAbsolutePath());
        log.event("info", "judge.calibrated", "samples", samplesOut.size(),
                "kappa", overall.kappa(), "accuracy", overall.accuracy());
    }

    /** Runs one judged calibration sample and prints the verdict line. */
    private static com.agentlens.eval.JudgeCalibration.Sample judgeOnce(
            com.agentlens.eval.Evaluator evaluator, com.agentlens.adapters.MockAgentAdapter mock,
            com.agentlens.core.AgentTask task, String behavior, boolean expectedPass,
            int index) throws Exception {
        var run = mock.run(task);
        var result = evaluator.evaluate(task, run);
        boolean judged = result.passed() && result.score() > 0;
        System.out.printf("  %-20s #%d judged=%s score=%.2f%s%n", behavior, index,
                judged ? "PASS" : "FAIL", result.score(),
                result.evidence().containsKey("judge.status")
                        ? " (" + result.evidence().get("judge.status") + ")" : "");
        return new com.agentlens.eval.JudgeCalibration.Sample(
                behavior, expectedPass, judged, result.score(), result.reason());
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Original single-task demo, kept for smoke testing. */
    private static void demo(Map<String, String> opts) throws Exception {
        AgentTask task = new AgentTask("demo-001", "inspect the service health", Map.of(), 10);
        AgentRun run = AdapterRegistry.load().defaultAdapter().run(task);
        var results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of("dangerous_tool")))
                .evaluate(task, run);
        System.out.printf("AgentLens demo: adapter=mock, status=%s, evaluations=%d, passed=%s%n",
                run.status(), results.size(), results.stream().allMatch(r -> r.passed()));
    }

    /** Builds the real-LLM adapter from CLI options (OpenAI-compatible endpoint). */
    private static AgentAdapter llmAdapter(Map<String, String> opts) {
        return new com.agentlens.adapters.LlmAgentAdapter(
                java.net.URI.create(opts.getOrDefault("base-url", "http://localhost:11434")),
                opts.getOrDefault("model", "qwen2.5:7b"),
                Double.parseDouble(opts.getOrDefault("temperature", "0.4")),
                java.time.Duration.ofSeconds(requireInt(opts, "timeout", 300)),
                new com.agentlens.adapters.SimulatedToolExecutor());
    }

    /** Wraps a single-run batch into the repeated-runs shape (k=1) for PassK. */
    private static Map<String, List<List<com.agentlens.core.EvaluationResult>>> singleRunAsRepeated(
            ExperimentRunner.BatchResult batch) {
        Map<String, List<List<com.agentlens.core.EvaluationResult>>> byTask = new java.util.LinkedHashMap<>();
        for (var evaluationRun : batch.runs())
                byTask.put(evaluationRun.taskId(), List.of(evaluationRun.results()));
        return byTask;
    }

    /** Package-private for unit tests (ParseOptionsTest). */
    static Map<String, String> parseOptions(String[] args, String command) {
        Map<String, String> opts = new java.util.HashMap<>();
        // Skip the command word (args[0]) only when it is one, not when it is a flag.
        int start = args.length > 0 && !args[0].startsWith("--")
                && List.of("run", "demo", "compare", "scores", "opsbench-import", "show",
                        "report", "serve", "judge-calibrate", "import", "aiopslab-catalog").contains(args[0]) ? 1 : 0;
        for (int i = start; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            // Boolean flag: no value token, or the next token is another flag.
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) opts.put(key, "true");
            else opts.put(key, args[++i]);
        }
        return opts;
    }

    /**
     * Parses a per-side override spec: comma-separated key=value pairs merged into
     * each task's context before the side runs (values may contain spaces and '=';
     * a token without '=' becomes the flag value "true"). Package-private for tests.
     */
    static Map<String, String> parseOverrides(String spec) {
        if (spec == null || spec.isBlank()) return Map.of();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String token : spec.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq < 0) out.put(t, "true");
            else out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return out;
    }

    /** Returns the task with override entries merged over its context (override wins). */
    static AgentTask withOverrides(AgentTask task, Map<String, String> overrides) {
        if (overrides.isEmpty()) return task;
        var ctx = new java.util.LinkedHashMap<>(task.context());
        ctx.putAll(overrides);
        return new AgentTask(task.id(), task.instruction(), ctx, task.maxSteps());
    }

    /**
     * The provider/model the dsh session actually used, taken from the first
     * MODEL_CALL event carrying a model attribute (request/context mapping).
     * This is the audit hook proving a per-side override took effect.
     */
    private static String observedModel(AgentRun run) {
        return run.events().stream()
                .filter(e -> e.type() == com.agentlens.core.EventType.MODEL_CALL)
                .map(e -> e.attributes().get("model"))
                .filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(String::valueOf)
                .findFirst().orElse(null);
    }

    private static Set<String> csv(String value) {
        return value == null || value.isBlank() ? Set.of()
                : Set.of(value.split(","));
    }

    /** Emits the gate verdict to both console and the structured log. */
    private static void logGate(String scope, com.agentlens.eval.RegressionGate.GateResult gate) {
        log.event(gate.passed() ? "info" : "warn", "gate.verdict",
                "scope", scope, "passed", gate.passed(), "reason", gate.reason());
    }
}
