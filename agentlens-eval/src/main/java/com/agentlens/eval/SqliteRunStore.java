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
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + file);
            conn.createStatement().executeUpdate("""
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
            conn.createStatement().executeUpdate("""
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
            conn.createStatement().executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evaluations (
                        run_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        agent TEXT NOT NULL,
                        agent_version TEXT,
                        started_at TEXT NOT NULL,
                        results_json TEXT NOT NULL
                    )""");
            ensureEvaluationMetadataColumn();
        } catch (SQLException e) {
            throw new IOException("cannot open sqlite run store: " + file, e);
        }
    }

    /**
     * In-place migration: stores created before evaluation metadata persistence
     * lack the metadata_json column. ALTER TABLE keeps every existing row intact.
     */
    private void ensureEvaluationMetadataColumn() throws SQLException {
        ResultSet rs = conn.createStatement().executeQuery("PRAGMA table_info(evaluations)");
        boolean hasMetadata = false;
        while (rs.next()) {
            if ("metadata_json".equalsIgnoreCase(rs.getString("name"))) { hasMetadata = true; break; }
        }
        rs.close();
        if (!hasMetadata)
            conn.createStatement().executeUpdate("ALTER TABLE evaluations ADD COLUMN metadata_json TEXT");
    }

    /** Persists a full run with its trace events. */
    public synchronized void saveRun(AgentRun run, String agentName) throws IOException {
        try {
            PreparedStatement runStmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO runs VALUES (?,?,?,?,?,?,?,?,?)");
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

            PreparedStatement delEvents = conn.prepareStatement("DELETE FROM events WHERE run_id = ?");
            delEvents.setString(1, run.runId());
            delEvents.executeUpdate();

            PreparedStatement evStmt = conn.prepareStatement("INSERT INTO events VALUES (?,?,?,?,?,?,?)");
            List<AgentEvent> events = run.events();
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
        } catch (SQLException e) {
            throw new IOException("failed to save run " + run.runId(), e);
        }
    }

    /** Persists the evaluation outcome (results + audit metadata) attached to a run. */
    public synchronized void saveEvaluation(EvaluationRun evaluation) throws IOException {
        try {
            PreparedStatement stmt = conn.prepareStatement("""
                    INSERT OR REPLACE INTO evaluations
                        (run_id, task_id, agent, agent_version, started_at, results_json, metadata_json)
                    VALUES (?,?,?,?,?,?,?)""");
            stmt.setString(1, evaluation.id());
            stmt.setString(2, evaluation.taskId());
            stmt.setString(3, evaluation.agentName());
            stmt.setString(4, evaluation.agentVersion());
            stmt.setString(5, evaluation.startedAt().toString());
            stmt.setString(6, mapper.writeValueAsString(evaluation.results()));
            stmt.setString(7, evaluation.metadata().isEmpty()
                    ? null : mapper.writeValueAsString(evaluation.metadata()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("failed to save evaluation " + evaluation.id(), e);
        }
    }

    /** Restores a full run (trace events included) by run id. */
    public Optional<AgentRun> loadRun(String runId) throws IOException {
        try {
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM runs WHERE run_id = ?");
            stmt.setString(1, runId);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return Optional.empty();
            AgentRun run = new AgentRun(
                    rs.getString("run_id"), rs.getString("task_id"),
                    RunStatus.valueOf(rs.getString("status")), rs.getString("final_answer"),
                    loadEvents(runId), Instant.parse(rs.getString("started_at")),
                    rs.getLong("duration_ms"), (int) rs.getLong("total_tokens"), rs.getString("error"));
            return Optional.of(run);
        } catch (SQLException e) {
            throw new IOException("failed to load run " + runId, e);
        }
    }

    /** Restores the evaluation recorded for a run id, if any. */
    @SuppressWarnings("unchecked")
    public Optional<EvaluationRun> loadEvaluation(String runId) throws IOException {
        try {
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM evaluations WHERE run_id = ?");
            stmt.setString(1, runId);
            ResultSet rs = stmt.executeQuery();
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
        } catch (SQLException e) {
            throw new IOException("failed to load evaluation " + runId, e);
        }
    }

    /** Lists stored run ids newest-first with their task and status. */
    public List<String> listRuns() throws IOException {
        try {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT run_id FROM runs ORDER BY started_at DESC");
            List<String> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getString(1));
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

    private List<AgentEvent> loadEvents(String runId) throws SQLException, IOException {
        PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM events WHERE run_id = ? ORDER BY seq");
        stmt.setString(1, runId);
        ResultSet rs = stmt.executeQuery();
        List<AgentEvent> events = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> attrs = mapper.readValue(rs.getString("attributes_json"),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            events.add(new AgentEvent(EventType.valueOf(rs.getString("type")), rs.getString("name"),
                    attrs, Instant.parse(rs.getString("timestamp")), rs.getLong("duration_ms")));
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
