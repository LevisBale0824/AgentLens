package com.agentlens.cli;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.AdapterRegistry;
import com.agentlens.eval.ComplianceEvaluator;
import com.agentlens.eval.EvaluatorRegistry;
import com.agentlens.eval.ExperimentRunner;
import com.agentlens.eval.PassK;
import com.agentlens.eval.RegressionGate;
import com.agentlens.eval.RunLimitEvaluator;
import com.agentlens.eval.Scenario;
import com.agentlens.eval.ScenarioLoader;
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
 *   compare --baseline BEHAVIOR --candidate BEHAVIOR [--scenarios PATH] [--repeats K]
 *           [--adapter dsh]   (both sides = real headless DSH sessions; --judge supported)
 *           [--baseline-override k=v,...] [--candidate-override k=v,...]
 *                             (per-side task-context overrides; with dsh: dshArgs=... /
 *                              env.KEY=... switch the candidate's model or launcher config)
 *   demo
 */
public final class AgentLensApp {
    private static final String DEFAULT_SCENARIOS = "data/scenarios/ops-scenarios.json";
    private static final String DEFAULT_DB = "output/runs.db";

    /** Attaches SQLite persistence (full trace + evaluation) to a runner. */
    private static ExperimentRunner withSqlite(ExperimentRunner runner, String dbPath) throws Exception {
        var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(dbPath));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { sqlite.close(); } catch (Exception ignored) {}
        }));
        return runner.onRunCompleted((run, evaluation) -> {
            try {
                sqlite.saveRun(run, evaluation.agentName());
                sqlite.saveEvaluation(evaluation);
            } catch (Exception e) {
                throw new IllegalStateException("sqlite persistence failed for " + run.runId(), e);
            }
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
        if (file != null && file.getParent() != null) java.nio.file.Files.createDirectories(file.getParent());
        var exporter = hasEndpoint ? new com.agentlens.eval.OtlpExporter(
                java.net.URI.create(endpoint),
                java.time.Duration.ofSeconds(intOf(opts.get("otel-timeout-sec"), 10)), 2) : null;
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
            System.err.println("usage: import --session PATH (a DSH session.jsonl or session.jsonl.zstd) [--task ID]");
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
        results.forEach(r -> System.out.printf("    %-18s passed=%-5s %s%n",
                r.evaluator(), r.passed(), r.reason()));
        var diagnoses = new com.agentlens.eval.DiagnosisEngine().diagnose(task, run, results);
        System.out.printf("  diagnoses: %d%n", diagnoses.size());
        diagnoses.forEach(d -> System.out.printf("    %-15s %-45s evidence=%s%n",
                d.layer(), d.issue(), d.evidence()));

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
        results.forEach(r -> System.out.printf("    %-18s passed=%-5s %s%n",
                r.evaluator(), r.passed(), r.reason()));
        var diagnoses = new com.agentlens.eval.DiagnosisEngine().diagnose(task, run, results);
        System.out.printf("  diagnoses: %d%n", diagnoses.size());
        diagnoses.forEach(d -> System.out.printf("    %-15s %-45s evidence=%s%n",
                d.layer(), d.issue(), d.evidence()));

        try (var sqlite = new com.agentlens.eval.SqliteRunStore(Path.of(opts.getOrDefault("db", DEFAULT_DB)))) {
            sqlite.saveRun(run, "aiopslab:" + imported.agentName());
            sqlite.saveEvaluation(new com.agentlens.core.EvaluationRun(run.runId(), run.taskId(),
                    "aiopslab", "imported", run.startedAt(), results,
                    Map.of("durationMs", run.durationMs(), "totalTokens", run.totalTokens(),
                            "problemId", String.valueOf(imported.problemId()))));
        }
        System.out.printf("  persisted run %s (adapter=aiopslab, version=imported)%n", run.runId());
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
        if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
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
        int repeats = intOf(opts.get("repeats"), 1);
        int passed = 0, failed = 0, aborted = 0;

        var registry = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of()))
                .register(new ComplianceEvaluator());
        if (opts.containsKey("judge")) registry.register(judgeEvaluator(opts));
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
        if (!gate.passed() || failed > 0 || aborted > 0) System.exit(1);
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
                ev.results().forEach(r -> System.out.printf("    %-18s passed=%-5s %s%n",
                        r.evaluator(), r.passed(), r.reason()));
                if (!ev.metadata().isEmpty()) {
                    System.out.println("  metadata:");
                    ev.metadata().forEach((k, v) -> System.out.printf("    %-16s %s%n", k, v));
                }
            }, () -> System.out.println("  evaluation: none recorded"));
        }
    }

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
        Map<String, String> opts = parseOptions(args, command);
        switch (command) {
            case "run" -> run(opts);
            case "compare" -> compare(opts);
            case "import" -> importSession(opts);
            case "show" -> show(opts);
            case "report" -> report(opts);
            case "demo" -> demo(opts);
            case "serve" -> serve(opts);
            case "aiopslab-catalog" -> aiopslabCatalog(opts);
            default -> {
                System.err.println("unknown command: " + command);
                System.err.println("usage: run [...] | compare --baseline B --candidate B [--baseline-override k=v,..] [--candidate-override k=v,..] | show --run ID | report [--db PATH] [--out PATH] | serve [--port N] | import --session PATH | import --aiopslab --session PATH | aiopslab-catalog [--registry PATH] [--out PATH] | demo");
                System.exit(2);
            }
        }
    }

    /** Loads the scenario dataset, runs it with the mock agent, aggregates pass^k and gates. */
    private static void run(Map<String, String> opts) throws Exception {
        Path scenariosPath = Path.of(opts.getOrDefault("scenarios", DEFAULT_SCENARIOS));
        int repeats = intOf(opts.get("repeats"), 1);
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
                .register(new ComplianceEvaluator());
        if (opts.containsKey("judge")) evaluators.register(judgeEvaluator(opts));
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
        if (!gate.passed()) System.exit(1);
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
        int port = intOf(opts.get("port"), 8080);
        var sqlite = new com.agentlens.eval.SqliteRunStore(db);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { sqlite.close(); } catch (Exception ignored) {}
        }));
        var dashboard = new DashboardServer(sqlite, port);
        System.out.printf("dashboard: %s  (db=%s) - Ctrl+C to stop%n",
                dashboard.baseUrl() + "/", db.toAbsolutePath());
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
        int repeats = intOf(opts.get("repeats"), "dsh".equals(adapterName) ? 1 : 5);
        String baselineBehavior = opts.getOrDefault("baseline", "correct");
        String candidateBehavior = opts.getOrDefault("candidate", "flaky");
        Map<String, String> baselineOverrides = parseOverrides(opts.get("baseline-override"));
        Map<String, String> candidateOverrides = parseOverrides(opts.get("candidate-override"));

        List<Scenario> scenarios = new ScenarioLoader().load(scenariosPath);
        List<AgentTask> tasks = scenarios.stream().map(Scenario::toTask).toList();
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

        PassK.Summary baseSummary, candSummary;
        String baseLabel, candLabel;
        if ("dsh".equals(adapterName)) {
            var dsh = (com.agentlens.adapters.dsh.DshAgentAdapter) AdapterRegistry.load().get("dsh");
            baseSummary = evaluateDshVersion(dsh, scenarios, repeats, "baseline", baselineOverrides, opts);
            candSummary = evaluateDshVersion(dsh, scenarios, repeats, "candidate", candidateOverrides, opts);
            baseLabel = "dsh-headless";
            candLabel = "dsh-headless";
        } else {
            baseSummary = evaluateVersion(baselineBehavior,
                    applyAll(tasks, baselineOverrides), repeats, opts);
            candSummary = evaluateVersion(candidateBehavior,
                    applyAll(tasks, candidateOverrides), repeats, opts);
            baseLabel = "mock-" + baselineBehavior;
            candLabel = "mock-" + candidateBehavior;
        }

        System.out.printf("%n%-10s %-22s pass^k=%.3f  avgPassRate=%.3f%n",
                "baseline", baseLabel, baseSummary.passPowK(), baseSummary.avgPassRate());
        System.out.printf("%-10s %-22s pass^k=%.3f  avgPassRate=%.3f%n",
                "candidate", candLabel, candSummary.passPowK(), candSummary.avgPassRate());

        printLayered("candidate", candSummary);

        var gate = new RegressionGate(List.of("tool-policy"), 0.0).compare(baseSummary, candSummary);
        System.out.printf("%nrelease gate: passed=%s%n  reason=%s%n", gate.passed(), gate.reason());
        if (!gate.passed()) System.exit(1);
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
    private static PassK.Summary evaluateDshVersion(com.agentlens.adapters.dsh.DshAgentAdapter dsh,
                                                    List<Scenario> scenarios, int repeats, String version,
                                                    Map<String, String> sideOverrides,
                                                    Map<String, String> opts) throws Exception {
        var registry = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of()))
                .register(new ComplianceEvaluator());
        if (opts.containsKey("judge")) registry.register(judgeEvaluator(opts));
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
        return PassK.summarize(byTask);
    }

    /** Applies the same context overrides to every task of a side. */
    private static List<AgentTask> applyAll(List<AgentTask> tasks, Map<String, String> overrides) {
        return overrides.isEmpty() ? tasks : tasks.stream().map(t -> withOverrides(t, overrides)).toList();
    }

    private static PassK.Summary evaluateVersion(String behavior, List<AgentTask> tasks, int repeats,
                                                 Map<String, String> opts)
            throws Exception {
        var runner = withOtel(withSqlite(new ExperimentRunner(new com.agentlens.adapters.MockAgentAdapter(behavior),
                new EvaluatorRegistry()
                        .register(new TaskCompletionEvaluator())
                        .register(new RunLimitEvaluator())
                        .register(new ToolPolicyEvaluator(Set.of(), Set.of()))
                        .register(new ComplianceEvaluator()),
                new com.agentlens.eval.JsonlRunStore(Path.of("output/runs.jsonl")), 4),
                DEFAULT_DB), opts);
        var byTask = runner.runRepeated(tasks, repeats);
        return PassK.summarize(byTask);
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

    /** Builds the LLM judge evaluator from CLI options / environment (ROADMAP P2-1). */
    private static com.agentlens.eval.LlmJudgeEvaluator judgeEvaluator(Map<String, String> opts) {
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
        var judge = new com.agentlens.eval.OpenAiJudge(java.net.URI.create(url), model, key,
                0.0, java.time.Duration.ofSeconds(Integer.parseInt(
                        opts.getOrDefault("judge-timeout-sec", envOr("AGENTLENS_JUDGE_TIMEOUT_SEC", "120")))),
                3, jsonMode, ollamaNative);
        System.out.printf("judge: %s (model=%s, url=%s, jsonMode=%s, ollamaNative=%s, criteria=%s, fail-open)%n",
                com.agentlens.eval.LlmJudgeEvaluator.EVALUATOR_NAME, model, url, jsonMode, ollamaNative,
                com.agentlens.eval.LlmJudgeEvaluator.CRITERIA.keySet());
        return new com.agentlens.eval.LlmJudgeEvaluator(judge);
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
                java.time.Duration.ofSeconds(intOf(opts.get("timeout"), 300)),
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
                && List.of("run", "demo", "compare", "show", "report", "serve", "import", "aiopslab-catalog").contains(args[0]) ? 1 : 0;
        for (int i = start; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            // Boolean flag: no value token, or the next token is another flag.
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) opts.put(key, "true");
            else opts.put(key, args[++i]);
        }
        return opts;
    }

    private static int intOf(String value, int defaultVal) {
        if (value == null) return defaultVal;
        try { return Math.max(1, Integer.parseInt(value)); }
        catch (NumberFormatException e) { return defaultVal; }
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
}
