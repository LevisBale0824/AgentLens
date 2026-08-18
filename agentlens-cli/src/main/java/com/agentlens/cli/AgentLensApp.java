package com.agentlens.cli;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentLens CLI. Usage:
 *   run [--scenarios PATH] [--repeats K] [--allowed T1,T2] [--forbidden T1,T2]
 *   demo
 */
public final class AgentLensApp {
    private static final String DEFAULT_SCENARIOS = "data/scenarios/ops-scenarios.json";

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
        Map<String, String> opts = parseOptions(args, command);
        switch (command) {
            case "run" -> run(opts);
            case "demo" -> demo(opts);
            default -> {
                System.err.println("unknown command: " + command);
                System.err.println("usage: run [--scenarios PATH] [--repeats K] | demo");
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

        List<AgentTask> tasks = scenarios.stream().map(Scenario::toTask).toList();
        var runner = new ExperimentRunner(new MockAgentAdapter(),
                new EvaluatorRegistry()
                        .register(new TaskCompletionEvaluator())
                        .register(new RunLimitEvaluator())
                        .register(new ToolPolicyEvaluator(extraAllowed, extraForbidden)),
                new com.agentlens.eval.JsonlRunStore(Path.of("output/runs.jsonl")), 4);

        var batch = runner.run(tasks);
        System.out.printf("single batch: runs=%d, failures=%d, p50=%dms, p95=%dms, max=%dms%n",
                batch.runs().size(), batch.failures().size(),
                batch.stats().p50DurationMs(), batch.stats().p95DurationMs(), batch.stats().maxDurationMs());
        batch.failures().forEach(f -> System.out.printf("  task-failure: %s: %s%n", f.taskId(), f.error()));

        Map<String, List<List<com.agentlens.core.EvaluationResult>>> byTask = repeats > 1
                ? runner.runRepeated(tasks, repeats)
                : singleRunAsRepeated(batch);
        var summary = PassK.summarize(byTask);
        System.out.printf("%npass^k summary: scenarios=%d, pass^k=%.3f, avgPassRate=%.3f%n",
                summary.scenarios(), summary.passPowK(), summary.avgPassRate());
        for (var layer : List.of(PassK.FailureLayer.STABLE_PASS,
                PassK.FailureLayer.FLAKY, PassK.FailureLayer.STABLE_FAIL)) {
            List<String> ids = summary.layered().getOrDefault(layer, List.of());
            if (!ids.isEmpty()) System.out.printf("  %-12s %d %s%n", layer, ids.size(), ids);
        }

        var gate = new RegressionGate(List.of("tool-policy"), 0.0).requireStable(summary);
        System.out.printf("%nrelease gate: passed=%s, reason=%s%n", gate.passed(), gate.reason());
        if (!gate.passed()) System.exit(1);
    }

    /** Original single-task demo, kept for smoke testing. */
    private static void demo(Map<String, String> opts) {
        AgentTask task = new AgentTask("demo-001", "inspect the service health", Map.of(), 10);
        AgentRun run = new MockAgentAdapter().run(task);
        var results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of("dangerous_tool")))
                .evaluate(task, run);
        System.out.printf("AgentLens demo: adapter=mock, status=%s, evaluations=%d, passed=%s%n",
                run.status(), results.size(), results.stream().allMatch(r -> r.passed()));
    }

    /** Wraps a single-run batch into the repeated-runs shape (k=1) for PassK. */
    private static Map<String, List<List<com.agentlens.core.EvaluationResult>>> singleRunAsRepeated(
            ExperimentRunner.BatchResult batch) {
        Map<String, List<List<com.agentlens.core.EvaluationResult>>> byTask = new java.util.LinkedHashMap<>();
        for (var evaluationRun : batch.runs())
                byTask.put(evaluationRun.taskId(), List.of(evaluationRun.results()));
        return byTask;
    }

    private static Map<String, String> parseOptions(String[] args, String command) {
        Map<String, String> opts = new java.util.HashMap<>();
        int start = "run".equals(command) || "demo".equals(command) ? 1 : 0;
        for (int i = start; i < args.length - 1; i++)
            if (args[i].startsWith("--")) opts.put(args[i].substring(2), args[i + 1]);
        return opts;
    }

    private static int intOf(String value, int defaultVal) {
        if (value == null) return defaultVal;
        try { return Math.max(1, Integer.parseInt(value)); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static Set<String> csv(String value) {
        return value == null || value.isBlank() ? Set.of()
                : Set.of(value.split(","));
    }
}
