package com.agentlens.eval;

import com.agentlens.core.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P1-B 口径对齐: the RAW k-times scores of one experiment side, persisted as a
 * versioned artifact. One boolean per (case, repeat) is all post-hoc metric
 * recomputation needs - pass@k, pass^k, at-least-m and the layered view can
 * all be derived later ({@link #summarize()}) without re-running the agent,
 * exactly like Inspect's epochs + reducers pattern. The aggregate-only
 * artifacts (compare / approved-baseline) stay untouched.
 *
 * <pre>
 * agentlens scores --db PATH --version v2 --out output/scores-v2.json   # capture
 * agentlens scores --reduce output/scores-v2.json --at-least 2          # recompute
 * </pre>
 */
public record ScoreRecord(int formatVersion, String label, int repeats,
                          Map<String, List<Boolean>> caseOutcomes, String createdAt) {

    public static final int FORMAT_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ScoreRecord {
        // TreeMap keeps case order deterministic across write/read cycles; the
        // per-value List.copyOf + TreeMap copy are the defensive copies SpotBugs
        // wants to see (deserialized outcome lists would otherwise be mutable).
        caseOutcomes = caseOutcomes == null ? Map.of()
                : caseOutcomes.entrySet().stream().collect(java.util.stream.Collectors
                        .toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()),
                                (a, b) -> a, TreeMap::new));
    }

    /**
     * Captures one side from the store: every (taskId, version) run becomes one
     * boolean (passed = every evaluator result passed), ordered by start time.
     * Runs without an evaluation are skipped - they carry no verdict yet.
     */
    public static ScoreRecord fromStore(SqliteRunStore store, String label, String version)
            throws IOException {
        record Scored(AgentRun run, com.agentlens.core.EvaluationRun evaluation) {}
        Map<String, List<Scored>> byTask = new LinkedHashMap<>();
        for (var entry : store.loadAll()) {
            AgentRun run = entry.getKey();
            var evaluation = entry.getValue();
            if (evaluation.isEmpty()) continue;
            if (version != null && !version.equals(evaluation.get().agentVersion())) continue;
            byTask.computeIfAbsent(run.taskId(), k -> new java.util.ArrayList<>())
                    .add(new Scored(run, evaluation.get()));
        }
        Map<String, List<Boolean>> outcomes = new LinkedHashMap<>();
        int repeats = 0;
        for (var e : byTask.entrySet()) {
            List<Boolean> flags = e.getValue().stream()
                    .sorted(Comparator.comparing(s -> s.run().startedAt()))
                    .map(s -> !s.evaluation().results().isEmpty()
                            && s.evaluation().results().stream()
                                    .allMatch(com.agentlens.core.EvaluationResult::passed))
                    .toList();
            outcomes.put(e.getKey(), flags);
            repeats = Math.max(repeats, flags.size());
        }
        return new ScoreRecord(FORMAT_VERSION, label == null ? "all" : label, repeats, outcomes,
                Instant.now().toString());
    }

    /** Recomputes the aggregate view (pass^k / pass@k / layers) from the raw outcomes. */
    public PassK.Summary summarize() {
        return PassK.summarizeRaw(caseOutcomes);
    }

    public void write(Path out) throws IOException {
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this));
    }

    public static ScoreRecord read(Path in) throws IOException {
        return JSON.readValue(in.toFile(), ScoreRecord.class);
    }
}
