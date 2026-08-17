package com.agentlens.cli;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.eval.TaskCompletionEvaluator;
import com.agentlens.eval.EvaluatorRegistry;
import com.agentlens.eval.RunLimitEvaluator;
import com.agentlens.eval.ToolPolicyEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;

public final class AgentLensApp {
    private AgentLensApp() {}

    public static void main(String[] args) throws Exception {
        int repeats = intArg(args, "--repeats", 1);

        AgentTask task = new AgentTask("demo-001", "inspect the service health", Map.of(), 10);
        AgentRun run = new MockAgentAdapter().run(task);
        var results = new EvaluatorRegistry()
                .register(new TaskCompletionEvaluator())
                .register(new RunLimitEvaluator())
                .register(new ToolPolicyEvaluator(Set.of(), Set.of("dangerous_tool")))
                .evaluate(task, run);
        System.out.printf("AgentLens demo: adapter=%s, status=%s, evaluations=%d, passed=%s%n",
                "mock", run.status(), results.size(), results.stream().allMatch(r -> r.passed()));

        var runner = new com.agentlens.eval.ExperimentRunner(new MockAgentAdapter(),
                new EvaluatorRegistry().register(new TaskCompletionEvaluator()),
                new com.agentlens.eval.JsonlRunStore(Path.of("output/runs.jsonl")), 1);
        var batch = runner.run(List.of(task));
        System.out.printf("batch: runs=%d, failures=%d, p50=%dms, p95=%dms%n",
                batch.runs().size(), batch.failures().size(),
                batch.stats().p50DurationMs(), batch.stats().p95DurationMs());

        // pass^k (ROADMAP 7.1): repeat the fixed scenario set k times and gate on stability.
        var byTask = runner.runRepeated(List.of(task), repeats);
        var summary = com.agentlens.eval.PassK.summarize(byTask);
        System.out.printf("pass^k summary: scenarios=%d, pass^k=%.2f, avgPassRate=%.2f%n",
                summary.scenarios(), summary.passPowK(), summary.avgPassRate());
        summary.layered().forEach((layer, ids) -> System.out.printf("  %-12s %s%n", layer, ids));
        var gate = new com.agentlens.eval.RegressionGate(List.of("tool-policy"), 0.0)
                .requireStable(summary);
        System.out.printf("release gate: passed=%s, reason=%s%n", gate.passed(), gate.reason());

        var diagnoses = new com.agentlens.eval.DiagnosisEngine().diagnose(task, run, results);
        System.out.printf("diagnoses=%d, traceValid=%s%n", diagnoses.size(),
                com.agentlens.core.TraceValidator.validate(run).isEmpty());
    }

    private static int intArg(String[] args, String flag, int defaultVal) {
        for (int i = 0; i < args.length - 1; i++) if (flag.equals(args[i])) {
            try { return Math.max(1, Integer.parseInt(args[i + 1])); }
            catch (NumberFormatException ignored) { return defaultVal; }
        }
        return defaultVal;
    }
}
