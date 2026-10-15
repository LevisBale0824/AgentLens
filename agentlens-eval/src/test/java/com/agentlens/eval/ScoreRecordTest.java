package com.agentlens.eval;

import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EvaluationRun;
import com.agentlens.core.EvaluationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1-B: the raw k-times scores artifact - capture, roundtrip, post-hoc recompute. */
class ScoreRecordTest {

    @TempDir
    Path tempDir;

    private static EvaluationResult result(boolean passed) {
        return new EvaluationResult("task-completion", passed, passed ? 1.0 : 0.0,
                passed ? "ok" : "no", Map.of());
    }

    @Test
    void fromStoreCapturesRawOutcomesPerCaseAndVersion() throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("scores.db"))) {
            AgentTask task = new AgentTask("case-a", "diagnose", Map.of(), 10);
            // v1: pass then fail (flaky); v2: pass then pass (stable)
            for (int repeat = 0; repeat < 2; repeat++) {
                boolean v1Pass = repeat == 0;
                AgentRun run = new MockAgentAdapter().run(task);
                AgentRun timed = new AgentRun(run.runId() + "-" + repeat, run.taskId(), run.status(),
                        run.finalAnswer(), run.events(), Instant.now().plusSeconds(repeat),
                        run.durationMs(), run.totalTokens(), run.error());
                store.saveRun(timed, "mock");
                store.saveEvaluation(new EvaluationRun(timed.runId(), "case-a", "mock",
                        v1Pass ? "v1" : "v2", timed.startedAt(),
                        List.of(result(v1Pass)), Map.of()));
            }
            var v1 = ScoreRecord.fromStore(store, "v1", "v1");
            assertEquals(List.of(true), v1.caseOutcomes().get("case-a"));
            var v2 = ScoreRecord.fromStore(store, "v2", "v2");
            assertEquals(List.of(false), v2.caseOutcomes().get("case-a"));
            var all = ScoreRecord.fromStore(store, "all", null);
            assertEquals(List.of(true, false), all.caseOutcomes().get("case-a"));
            assertEquals(2, all.repeats());
            assertTrue(all.summarize().layered().get(PassK.FailureLayer.FLAKY).contains("case-a"));
        }
    }

    @Test
    void writeReadRoundtripAndSummarize() throws Exception {
        var record = new ScoreRecord(ScoreRecord.FORMAT_VERSION, "mock-correct", 2,
                Map.of("case-a", List.of(true, true), "case-b", List.of(true, false)), null);
        Path out = tempDir.resolve("scores.json");
        record.write(out);
        var back = ScoreRecord.read(out);
        assertEquals(record.label(), back.label());
        assertEquals(record.repeats(), back.repeats());
        assertEquals(record.caseOutcomes(), back.caseOutcomes());
        var summary = back.summarize();
        assertEquals(0.5, summary.passPowK(), 1e-9);
        assertEquals(0.75, summary.avgPassRate(), 1e-9);
    }
}
