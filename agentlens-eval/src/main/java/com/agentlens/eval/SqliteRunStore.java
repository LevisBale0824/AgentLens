package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EvaluationRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite-backed run store (ROADMAP P1-2): persists full runs (trace events
 * included) and evaluation results so any run can be reproduced and audited
 * by run id. Complements JsonlRunStore, which only keeps a summary line.
 *
 * Schema:
 *   runs(id, task_id, agent, status, final_answer, started_at, duration_ms,
 *        total_tokens, error)
 *   events(run_id, seq, type, name, attributes_json, timestamp, duration_ms)
 *   evaluations(run_id, task_id, agent, agent_version, started_at, results_json,
 *        metadata_json) - metadata carries audit fields such as per-side compare
 *        overrides and the observed model; older stores are migrated in place.
 */
public final class SqliteRunStore implements AutoCloseable {
    private final Connection conn;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule());

    public SqliteRunStore(Path file) throws IOException {
        try {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IOException("sqlite-jdbc driver not on classpath", e);
            }
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);
        // Throughput pragmas (credibility plan S3 smoke finding): WAL + NORMAL
        // fsync semantics collapse the per-statement disk sync cost - on slow
        // (spinning-disk class) storage one saveRun otherwise costs ~600ms,
        // making any 200-run compare take minutes. WAL keeps crash safety for
        // the evidence chain; NORMAL fsyncs at checkpoint, losing at most the
        // latest transaction on power loss, never corrupting the store.
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA journal_mode = WAL");
            pragma.execute("PRAGMA synchronous = NORMAL");
        }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS runs (
                        run_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        agent TEXT NOT NULL,
                        status TEXT NOT NULL,
                        final_answer TEXT,
                        started_at TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        total_tokens INTEGER NOT NULL,
                        error TEXT
                    )""");
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS events (
                        run_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        name TEXT,
                        attributes_json TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        PRIMARY KEY (run_id, seq)
                    )""");
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evaluations (
                        run_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        agent TEXT NOT NULL,
                        agent_version TEXT,
                        started_at TEXT NOT NULL,
                        results_json TEXT NOT NULL
                    )""");
            }
            ensureEvaluationMetadataColumn();
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS import_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        imported_at TEXT NOT NULL,
                        file_name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        source TEXT NOT NULL,
                        imported INTEGER NOT NULL,
                        skipped INTEGER NOT NULL,
                        error TEXT
                    )""");
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS annotations (
                        run_id TEXT PRIMARY KEY,
                        passed INTEGER NOT NULL,
                        note TEXT,
                        annotated_by TEXT,
                        created_at TEXT NOT NULL
                    )""");
            }
        } catch (SQLException e) {
            throw new IOException("cannot open sqlite run store: " + file, e);
        }
    }

    /**
     * In-place migration: stores created before evaluation metadata persistence
     * lack the metadata_json column. ALTER TABLE keeps every existing row intact.
     */
    private void ensureEvaluationMetadataColumn() throws SQLException {
        boolean hasMetadata = false;
        try (Statement pragma = conn.createStatement();
             ResultSet rs = pragma.executeQuery("PRAGMA table_info(evaluations)")) {
            while (rs.next()) {
                if ("metadata_json".equalsIgnoreCase(rs.getString("name"))) {
                    hasMetadata = true;
                    break;
                }
            }
        }
        if (!hasMetadata) {
            try (Statement alter = conn.createStatement()) {
                alter.executeUpdate("ALTER TABLE evaluations ADD COLUMN metadata_json TEXT");
            }
        }
    }

    /** Persists a full run with its trace events. Explicit single transaction. */
    public synchronized void saveRun(AgentRun run, String agentName) throws IOException {
        boolean autoCommit = true;
        try {
            autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement runStmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO runs VALUES (?,?,?,?,?,?,?,?,?)")) {
                runStmt.setString(1, run.runId());
                runStmt.setString(2, run.taskId());
                runStmt.setString(3, agentName);
                runStmt.setString(4, run.status().name());
                runStmt.setString(5, run.finalAnswer());
                runStmt.setString(6, run.startedAt().toString());
                runStmt.setLong(7, run.durationMs());
                runStmt.setLong(8, run.totalTokens());
                runStmt.setString(9, run.error());
                runStmt.executeUpdate();
            }

            try (PreparedStatement delEvents = conn.prepareStatement("DELETE FROM events WHERE run_id = ?")) {
                delEvents.setString(1, run.runId());
                delEvents.executeUpdate();
            }

            List<AgentEvent> events = run.events();
            try (PreparedStatement evStmt = conn.prepareStatement("INSERT INTO events VALUES (?,?,?,?,?,?,?)")) {
                for (int i = 0; i < events.size(); i++) {
                    AgentEvent e = events.get(i);
                    evStmt.setString(1, run.runId());
                    evStmt.setInt(2, i);
                    evStmt.setString(3, e.type().name());
                    evStmt.setString(4, e.name());
                    evStmt.setString(5, mapper.writeValueAsString(e.attributes()));
                    evStmt.setString(6, e.timestamp().toString());
                    evStmt.setLong(7, e.durationMs());
                    evStmt.addBatch();
                }
                evStmt.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IOException("failed to save run " + run.runId(), e);
        } finally {
            restoreAutoCommit(autoCommit);
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // best effort; the original failure propagates
        }
    }

    private void restoreAutoCommit(boolean autoCommit) {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // best effort on the cleanup path
        }
    }

    /** Persists the evaluation outcome (results + audit metadata) attached to a run. Explicit transaction. */
    public synchronized void saveEvaluation(EvaluationRun evaluation) throws IOException {
        boolean autoCommit = true;
        try {
            autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement("""
                    INSERT OR REPLACE INTO evaluations
                        (run_id, task_id, agent, agent_version, started_at, results_json, metadata_json)
                    VALUES (?,?,?,?,?,?,?)""")) {
                stmt.setString(1, evaluation.id());
                stmt.setString(2, evaluation.taskId());
                stmt.setString(3, evaluation.agentName());
                stmt.setString(4, evaluation.agentVersion());
                stmt.setString(5, evaluation.startedAt().toString());
                stmt.setString(6, mapper.writeValueAsString(evaluation.results()));
                stmt.setString(7, evaluation.metadata().isEmpty()
                        ? null : mapper.writeValueAsString(evaluation.metadata()));
                stmt.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IOException("failed to save evaluation " + evaluation.id(), e);
        } finally {
            restoreAutoCommit(autoCommit);
        }
    }

    /** Restores a full run (trace events included) by run id. */
    public Optional<AgentRun> loadRun(String runId) throws IOException {
        try {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM runs WHERE run_id = ?")) {
                stmt.setString(1, runId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    AgentRun run = new AgentRun(
                            rs.getString("run_id"), rs.getString("task_id"),
                            RunStatus.valueOf(rs.getString("status")), rs.getString("final_answer"),
                            loadEvents(runId), Instant.parse(rs.getString("started_at")),
                            rs.getLong("duration_ms"), (int) rs.getLong("total_tokens"), rs.getString("error"));
                    return Optional.of(run);
                }
            }
        } catch (SQLException e) {
            throw new IOException("failed to load run " + runId, e);
        }
    }

    /** Restores the evaluation recorded for a run id, if any. */
    @SuppressWarnings("unchecked")
    public Optional<EvaluationRun> loadEvaluation(String runId) throws IOException {
        try {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM evaluations WHERE run_id = ?")) {
                stmt.setString(1, runId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    List<com.agentlens.core.EvaluationResult> results = mapper.readValue(
                            rs.getString("results_json"),
                            mapper.getTypeFactory().constructCollectionType(List.class,
                                    com.agentlens.core.EvaluationResult.class));
                    String metadataJson = rs.getString("metadata_json");
                    Map<String, Object> metadata = metadataJson == null ? Map.of()
                            : mapper.readValue(metadataJson,
                                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                    return Optional.of(new EvaluationRun(rs.getString("run_id"), rs.getString("task_id"),
                            rs.getString("agent"), rs.getString("agent_version"),
                            Instant.parse(rs.getString("started_at")), results, metadata));
                }
            }
        } catch (SQLException e) {
            throw new IOException("failed to load evaluation " + runId, e);
        }
    }

    /** Lists stored run ids newest-first with their task and status. */
    public List<String> listRuns() throws IOException {
        try {
            List<String> ids = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT run_id FROM runs ORDER BY started_at DESC")) {
                while (rs.next()) ids.add(rs.getString(1));
            }
            return List.copyOf(ids);
        } catch (SQLException e) {
            throw new IOException("failed to list runs", e);
        }
    }

    /** Loads every run with its evaluation, newest first. Report source of truth. */
    public List<Map.Entry<AgentRun, Optional<EvaluationRun>>> loadAll() throws IOException {
        List<Map.Entry<AgentRun, Optional<EvaluationRun>>> all = new ArrayList<>();
        for (String runId : listRuns()) {
            all.add(Map.entry(loadRun(runId).orElseThrow(), loadEvaluation(runId)));
        }
        return List.copyOf(all);
    }

    /**
     * M2: appends one row to the import history log (what was imported from
     * which file, under which source tag, with what outcome).
     */
    public void recordImport(String fileName, String kind, String source,
                             int imported, int skipped, String error) throws IOException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO import_log (imported_at, file_name, kind, source, imported, skipped, error)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            stmt.setString(1, java.time.Instant.now().toString());
            stmt.setString(2, fileName);
            stmt.setString(3, kind);
            stmt.setString(4, source);
            stmt.setInt(5, imported);
            stmt.setInt(6, skipped);
            stmt.setString(7, error);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("failed to record import", e);
        }
    }

    /** M2: the import history log, newest first, capped at {@code limit} rows. */
    public List<Map<String, Object>> importHistory(int limit) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT imported_at, file_name, kind, source, imported, skipped, error"
                        + " FROM import_log ORDER BY id DESC LIMIT ?")) {
            stmt.setInt(1, Math.max(1, limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("importedAt", rs.getString("imported_at"));
                    row.put("file", rs.getString("file_name"));
                    row.put("kind", rs.getString("kind"));
                    row.put("source", rs.getString("source"));
                    row.put("imported", rs.getInt("imported"));
                    row.put("skipped", rs.getInt("skipped"));
                    row.put("error", rs.getString("error"));
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IOException("failed to read import history", e);
        }
        return List.copyOf(out);
    }

    /** M3: one human review verdict for a run (re-annotating overwrites). */
    public record Annotation(String runId, boolean passed, String note,
                             String annotatedBy, String createdAt) {
    }

    /** M3: inserts or replaces the human annotation for a run. */
    public void saveAnnotation(Annotation annotation) throws IOException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO annotations (run_id, passed, note, annotated_by, created_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT(run_id) DO UPDATE SET passed=excluded.passed, note=excluded.note,
                    annotated_by=excluded.annotated_by, created_at=excluded.created_at""")) {
            stmt.setString(1, annotation.runId());
            stmt.setInt(2, annotation.passed() ? 1 : 0);
            stmt.setString(3, annotation.note());
            stmt.setString(4, annotation.annotatedBy());
            stmt.setString(5, java.time.Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("failed to save annotation for " + annotation.runId(), e);
        }
    }

    /** M3: the annotation for one run, or empty when a human has not reviewed it. */
    public Optional<Annotation> loadAnnotation(String runId) throws IOException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT run_id, passed, note, annotated_by, created_at FROM annotations WHERE run_id = ?")) {
            stmt.setString(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Annotation(rs.getString("run_id"),
                            rs.getInt("passed") == 1, rs.getString("note"),
                            rs.getString("annotated_by"), rs.getString("created_at")));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IOException("failed to load annotation for " + runId, e);
        }
    }

    /** M3: every human annotation, newest first - the review queue. */
    public List<Annotation> annotationHistory() throws IOException {
        List<Annotation> out = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT run_id, passed, note, annotated_by, created_at FROM annotations"
                             + " ORDER BY created_at DESC")) {
            while (rs.next()) {
                out.add(new Annotation(rs.getString("run_id"), rs.getInt("passed") == 1,
                        rs.getString("note"), rs.getString("annotated_by"),
                        rs.getString("created_at")));
            }
        } catch (SQLException e) {
            throw new IOException("failed to read annotations", e);
        }
        return List.copyOf(out);
    }

    private List<AgentEvent> loadEvents(String runId) throws SQLException, IOException {
        List<AgentEvent> events = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM events WHERE run_id = ? ORDER BY seq")) {
            stmt.setString(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> attrs = mapper.readValue(rs.getString("attributes_json"),
                            new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                    events.add(new AgentEvent(EventType.valueOf(rs.getString("type")), rs.getString("name"),
                            attrs, Instant.parse(rs.getString("timestamp")), rs.getLong("duration_ms")));
                }
            }
        }
        return List.copyOf(events);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IOException("failed to close sqlite store", e);
        }
    }
}
