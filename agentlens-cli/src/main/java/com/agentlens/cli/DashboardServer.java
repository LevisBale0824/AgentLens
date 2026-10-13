package com.agentlens.cli;

import com.agentlens.core.AgentRun;
import com.agentlens.core.Diagnosis;
import com.agentlens.core.EvaluationResult;
import com.agentlens.eval.DiagnosisEngine;
import com.agentlens.eval.SqliteRunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * </pre>
 *
 * Read-only by design: the dashboard never mutates the store.
 */
public final class DashboardServer implements AutoCloseable {
    private final SqliteRunStore store;
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final DiagnosisEngine diagnosisEngine = new DiagnosisEngine();

    public DashboardServer(SqliteRunStore store, int port) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> respond(exchange, 200, "text/html; charset=utf-8", INDEX_HTML));
        server.createContext("/api/summary", exchange ->
                respondJson(exchange, 200, summary()));
        server.createContext("/api/runs", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/runs")) {
                respondJson(exchange, 200, runSummaries());
            } else if (path.startsWith("/api/runs/")) {
                String id = path.substring("/api/runs/".length());
                var detail = runDetail(id);
                respondJson(exchange, detail == null ? 404 : 200, detail == null
                        ? Map.of("error", "run not found: " + id) : detail);
            } else {
                respondJson(exchange, 404, Map.of("error", "not found"));
            }
        });
        server.createContext("/api/scenarios", exchange ->
                respondJson(exchange, 200, scenarios()));
        server.start();
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
        return out;
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
        return out;
    }

    private static Map<String, Object> resultMap(EvaluationResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluator", r.evaluator());
        out.put("passed", r.passed());
        out.put("score", r.score());
        out.put("reason", r.reason());
        out.put("evidence", r.evidence());
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
              code{background:#0b101b;padding:1px 6px;border-radius:4px;font-size:12px}
              .muted{color:var(--dim)}
              </style></head><body>
            <header><h1>AgentLens Dashboard <span id="sub">loading…</span></h1></header>
            <main>
              <div class="cards" id="cards"></div>
              <h2>Runs trend</h2><div class="bars" id="trend"></div>
              <h2>Scenarios (pass^k layers)</h2>
              <table><thead><tr><th>scenario</th><th>runs</th><th>passed</th><th>pass rate</th><th>layer</th></tr></thead>
                <tbody id="scenarios"></tbody></table>
              <h2>Runs</h2>
              <div style="margin-bottom:8px">
                <input id="q" placeholder="filter by task…">
                <select id="status"><option value="">status: all</option>
                  <option>SUCCESS</option><option>FAILED</option><option>TIMEOUT</option><option>CANCELLED</option></select>
                <select id="version"><option value="">version: all</option></select>
                <label class="muted"><input type="checkbox" id="onlyFailed" style="margin-right:4px">only failures</label>
              </div>
              <table><thead><tr><th>started</th><th>task</th><th>status</th><th>eval</th>
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

            function renderRuns() {
              const q = $("q").value.toLowerCase(), st = $("status").value,
                    ver = $("version").value, onlyBad = $("onlyFailed").checked;
              const rows = RUNS.filter(r =>
                (!q || r.taskId.toLowerCase().includes(q)) &&
                (!st || r.status === st) &&
                (!ver || r.agentVersion === ver) &&
                (!onlyBad || !r.passed));
              $("runs").innerHTML = rows.map(r =>
                `<tr onclick="openRun('${esc(r.runId)}')">
                   <td class="muted">${esc(r.startedAt.replace("T", " ").slice(0, 19))}</td>
                   <td><code>${esc(r.taskId)}</code></td>
                   <td>${r.status === "SUCCESS" ? pill(r.status, "ok") : pill(r.status, "bad")}</td>
                   <td>${r.passed ? pill("PASS", "ok") : pill("FAIL", "bad")}</td>
                   <td>${esc(r.agentVersion ?? "-")}</td>
                   <td class="muted">${esc(r.observedModel ?? "-")}</td>
                   <td>${r.durationMs}</td><td>${r.events}</td></tr>`).join("");
            }

            async function openRun(id) {
              const d = await j("/api/runs/" + id);
              const el = $("detail");
              el.style.display = "block";
              const r = d.run;
              let html = `<h2 style="margin-top:4px"><code>${esc(r.taskId)}</code>
                          ${r.status === "SUCCESS" ? pill(r.status, "ok") : pill(r.status, "bad")}
                          <span class="muted">· ${r.durationMs}ms · ${r.events} events</span></h2>`;
              if (d.finalAnswer) html += `<div class="ev"><b>final answer:</b> ${esc(d.finalAnswer)}</div>`;
              if (d.error) html += `<div class="ev"><b style="color:var(--bad)">error:</b> ${esc(d.error)}</div>`;
              html += `<h2>Trace timeline</h2>` + d.events.map(e =>
                `<div class="ev"><span class="t">#${e.index}</span> <b>${esc(e.type)}</b> ${esc(e.name ?? "")}
                 ${e.durationMs ? e.durationMs + "ms" : ""} ${esc(JSON.stringify(e.attributes))}</div>`).join("");
              if (d.evaluation) {
                html += `<h2>Evaluation (${esc(d.evaluation.agentVersion ?? "")})</h2>` +
                  d.evaluation.results.map(x =>
                    `<div class="ev">${x.passed ? pill("PASS", "ok") : pill("FAIL", "bad")}
                     <b>${esc(x.evaluator)}</b> score=${x.score} — ${esc(x.reason)}</div>`).join("");
              }
              html += `<h2>Diagnoses (${d.diagnoses.length})</h2>` + (d.diagnoses.length === 0
                ? `<div class="ev muted">none</div>`
                : d.diagnoses.map(x =>
                    `<div class="diag"><b>${esc(x.layer)}</b> — ${esc(x.issue)}
                     <span class="muted">severity=${x.severity} confidence=${x.confidence}</span>
                     ${x.evidence.length ? `<div class="ev muted">evidence: ${x.evidence.map(esc).join(", ")}</div>` : ""}
                     ${x.recommendation ? `<div class="ev"><b>fix:</b> ${esc(x.recommendation)}</div>` : ""}</div>`).join(""));
              el.innerHTML = html;
              el.scrollIntoView({behavior: "smooth"});
            }

            async function boot() {
              renderCards(await j("/api/summary"));
              renderScenarios(await j("/api/scenarios"));
              RUNS = await j("/api/runs");
              [...new Set(RUNS.map(r => r.agentVersion).filter(Boolean))].forEach(v => {
                const o = document.createElement("option"); o.textContent = v; $("version").appendChild(o);
              });
              renderTrend(RUNS); renderRuns();
              ["q", "status", "version", "onlyFailed"].forEach(id =>
                $(id).addEventListener("input", renderRuns));
            }
            boot().catch(e => { $("sub").textContent = "load failed: " + e; });
            </script></body></html>
            """;
}
