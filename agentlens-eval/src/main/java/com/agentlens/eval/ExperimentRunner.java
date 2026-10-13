package com.agentlens.eval;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationResult;
import com.agentlens.core.EvaluationRun;
import com.agentlens.core.TraceValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ExperimentRunner {
    private final AgentAdapter adapter;
    private final EvaluatorRegistry evaluators;
    private final JsonlRunStore store;
    private final int concurrency;
    private final long timeoutMs;
    private java.util.function.BiConsumer<AgentRun, EvaluationRun> runListener;

    public ExperimentRunner(AgentAdapter adapter, EvaluatorRegistry evaluators, JsonlRunStore store, int concurrency) {
        this(adapter, evaluators, store, concurrency, 0);
    }

    public ExperimentRunner(AgentAdapter adapter, EvaluatorRegistry evaluators, JsonlRunStore store,
                            int concurrency, long timeoutMs) {
        this.adapter = adapter;
        this.evaluators = evaluators;
        this.store = store;
        this.concurrency = Math.max(1, concurrency);
        this.timeoutMs = timeoutMs;
    }

    /**
     * Optional observer invoked after each successful run+evaluation, e.g. to
     * persist full traces into SqliteRunStore. Exceptions propagate as task failures.
     */
    public ExperimentRunner onRunCompleted(java.util.function.BiConsumer<AgentRun, EvaluationRun> listener) {
        this.runListener = listener;
        return this;
    }

    /**
     * Failure-isolated run: a single task failure is recorded and skipped
     * instead of aborting the whole batch (P1-3 acceptance criteria).
     */
    public BatchResult run(List<AgentTask> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<java.util.concurrent.Future<TaskOutcome>> futures = new ArrayList<>();
            for (AgentTask task : tasks) futures.add(pool.submit(() -> runOne(task)));
            List<EvaluationRun> results = new ArrayList<>();
            List<TaskFailure> failures = new ArrayList<>();
            for (var future : futures) {
                TaskOutcome outcome = join(future);
                if (outcome.run() != null) results.add(outcome.run());
                else failures.add(outcome.failure());
            }
            return new BatchResult(List.copyOf(results), List.copyOf(failures), stats(results));
        } finally {
            pool.shutdownNow();
            if (timeoutMs > 0) pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Repeated runs for pass^k (ROADMAP 7.1): runs each task k times and
     * returns per-task lists of evaluation results, one per repetition.
     */
    public Map<String, List<List<EvaluationResult>>> runRepeated(List<AgentTask> tasks, int repeats)
            throws InterruptedException {
        if (repeats < 1) throw new IllegalArgumentException("repeats must be >= 1");
        List<AgentTask> expanded = new ArrayList<>();
        for (AgentTask task : tasks) for (int i = 0; i < repeats; i++) expanded.add(task);
        BatchResult batch = run(expanded);
        Map<String, List<List<EvaluationResult>>> byTask = new java.util.LinkedHashMap<>();
        for (EvaluationRun evaluationRun : batch.runs()) {
            byTask.computeIfAbsent(evaluationRun.taskId(), t -> new ArrayList<>())
                    .add(evaluationRun.results());
        }
        // Tasks whose every repetition failed to even execute get an empty-fail entry.
        for (AgentTask task : tasks) byTask.putIfAbsent(task.id(), List.of());
        return byTask;
    }

    private TaskOutcome join(java.util.concurrent.Future<TaskOutcome> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return new TaskOutcome(null, new TaskFailure("unknown", "execution failed: " + rootMessage(e)));
        }
    }

    private TaskOutcome runOne(AgentTask task) {
        try {
            AgentRun run = adapter.run(task);
            var errors = TraceValidator.validate(run);
            if (!errors.isEmpty()) throw new IllegalStateException(String.join(", ", errors));
            store.append(run);
            EvaluationRun evaluation = new EvaluationRun(run.runId(), task.id(), adapter.name(), "dev",
                    run.startedAt(), evaluators.evaluate(task, run),
                    Map.of("durationMs", run.durationMs(), "totalTokens", run.totalTokens()));
            if (runListener != null) runListener.accept(run, evaluation);
            return new TaskOutcome(evaluation, null);
        } catch (Exception e) {
            return new TaskOutcome(null, new TaskFailure(task.id(), rootMessage(e)));
        }
    }

    private static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static BatchStats stats(List<EvaluationRun> runs) {
        if (runs.isEmpty()) return new BatchStats(0, 0, 0, 0, 0, 0);
        long[] durations = runs.stream().mapToLong(r -> durationOf(r)).sorted().toArray();
        long totalTokens = runs.stream().mapToLong(ExperimentRunner::tokensOf).sum();
        return new BatchStats(
                durations.length,
                percentile(durations, 0.50),
                percentile(durations, 0.95),
                runs.stream().mapToInt(r -> r.results().size()).sum(),
                totalTokens,
                durations[durations.length - 1]);
    }

    private static long durationOf(EvaluationRun r) {
        Object v = r.metadata().get("durationMs");
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static long tokensOf(EvaluationRun r) {
        Object v = r.metadata().get("totalTokens");
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static long percentile(long[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private record TaskOutcome(EvaluationRun run, TaskFailure failure) {}

    public record TaskFailure(String taskId, String error) {}

    public record BatchStats(int runs, long p50DurationMs, long p95DurationMs,
                             long totalResults, long totalTokens, long maxDurationMs) {}

    public record BatchResult(List<EvaluationRun> runs, List<TaskFailure> failures, BatchStats stats) {
        public BatchResult {
            runs = List.copyOf(runs);
            failures = List.copyOf(failures);
        }

        public boolean allSucceeded() { return failures.isEmpty(); }
    }
}
