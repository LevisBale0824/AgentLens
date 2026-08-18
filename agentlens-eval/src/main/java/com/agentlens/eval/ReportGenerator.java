package com.agentlens.eval;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EvaluationRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Self-contained HTML report generator (ROADMAP P2-4): renders everything in
 * the SQLite store into one static file - run table with per-scenario pass
 * rates, pass^k layered view, trace timelines with evaluation details, and
 * per-scenario drill-down. No server or external assets.
 */
public final class ReportGenerator {
    private static final String CSS = """
            :root{--bg:#0f1420;--card:#171e2e;--border:#28324a;--text:#dbe4f3;--dim:#8294b0;
            --green:#34d399;--red:#f87171;--amber:#fbbf24;--blue:#60a5fa}
            *{margin:0;padding:0;box-sizing:border-box}
            body{background:var(--bg);color:var(--text);font:14px/1.5 'Segoe UI',system-ui,sans-serif;padding:32px}
            h1{font-size:22px;margin-bottom:4px} h2{font-size:16px;margin:28px 0 12px;color:var(--blue)}
            .sub{color:var(--dim);margin-bottom:24px}
            .cards{display:flex;gap:16px;flex-wrap:wrap;margin-bottom:8px}
            .card{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:16px 22px;min-width:140px}
            .card .num{font-size:26px;font-weight:600} .card .lbl{color:var(--dim);font-size:12px}
            table{border-collapse:collapse;width:100%;background:var(--card);border-radius:10px;overflow:hidden}
            th{background:#1c2538;text-align:left;padding:8px 12px;font-size:12px;color:var(--dim)}
            td{padding:8px 12px;border-top:1px solid var(--border);vertical-align:top}
            .pill{display:inline-block;padding:2px 10px;border-radius:999px;font-size:11px;font-weight:600}
            .pass{background:#052e1c;color:var(--green)} .fail{background:#3b0d0d;color:var(--red)}
            .flaky{background:#3a2c05;color:var(--amber)}
            details{background:var(--card);border:1px solid var(--border);border-radius:10px;margin:8px 0;padding:0}
            summary{cursor:pointer;padding:10px 14px;font-weight:600;list-style:none}
            summary:hover{background:#1c2538}
            .ev{font:12px/1.7 ui-monospace,Consolas,monospace;padding:6px 18px;color:var(--dim)}
            .ev b{color:var(--text);font-weight:600}
            code{background:#0b101b;padding:1px 6px;border-radius:4px;font-size:12px}
            """;

    public Path generate(SqliteRunStore store, Path outFile) throws IOException {
        var data = store.loadAll();
        if (data.isEmpty()) throw new IOException("no runs in store - run the CLI first");

        var byScenario = byScenario(data);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>AgentLens Report</title><style>")
                .append(CSS).append("</style></head><body>");
        html.append("<h1>AgentLens Evaluation Report</h1>");
        html.append("<div class='sub'>").append(data.size()).append(" runs · ")
                .append(byScenario.size()).append(" scenarios · generated ")
                .append(java.time.LocalDateTime.now()).append("</div>");

        summaryCards(html, data, byScenario);
        scenarioTable(html, byScenario);
        runTimelines(html, data);

        html.append("</body></html>");
        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, html.toString());
        return outFile;
    }

    private Map<String, List<RunEval>> byScenario(List<Map.Entry<AgentRun, Optional<EvaluationRun>>> data) {
        return data.stream().collect(Collectors.groupingBy(
                e -> e.getKey().taskId(),
                LinkedHashMap::new,
                Collectors.mapping(RunEval::new, Collectors.toList())));
    }

    private void summaryCards(StringBuilder html, List<Map.Entry<AgentRun, Optional<EvaluationRun>>> data,
                              Map<String, List<RunEval>> byScenario) {
        long passed = data.stream().filter(e -> passed(e.getValue())).count();
        double passRate = data.isEmpty() ? 0 : (double) passed / data.size();
        long stablePass = byScenario.values().stream().filter(l -> l.stream().allMatch(RunEval::passed)).count();
        long stableFail = byScenario.values().stream().filter(l -> l.stream().noneMatch(RunEval::passed)).count();
        long flaky = byScenario.size() - stablePass - stableFail;
        html.append("<div class='cards'>")
                .append(card(String.valueOf(data.size()), "runs"))
                .append(card(pct(passRate), "pass rate"))
                .append(card(String.valueOf(stablePass), "stable-pass"))
                .append(card(String.valueOf(flaky), "flaky"))
                .append(card(String.valueOf(stableFail), "stable-fail"))
                .append("</div>");
    }

    private void scenarioTable(StringBuilder html, Map<String, List<RunEval>> byScenario) {
        html.append("<h2>Scenarios (pass^k view)</h2><table><tr><th>scenario</th><th>runs</th>")
                .append("<th>passed</th><th>pass rate</th><th>layer</th></tr>");
        byScenario.forEach((scenario, runs) -> {
            long passed = runs.stream().filter(RunEval::passed).count();
            String layer = passed == runs.size() ? "STABLE_PASS"
                    : passed == 0 ? "STABLE_FAIL" : "FLAKY";
            String cls = layer.equals("STABLE_PASS") ? "pass" : layer.equals("FLAKY") ? "flaky" : "fail";
            html.append(String.format(
                    "<tr><td><code>%s</code></td><td>%d</td><td>%d</td><td>%s</td><td><span class='pill %s'>%s</span></td></tr>",
                    esc(scenario), runs.size(), passed, pct((double) passed / runs.size()), cls, layer));
        });
        html.append("</table>");
    }

    private void runTimelines(StringBuilder html, List<Map.Entry<AgentRun, Optional<EvaluationRun>>> data) {
        html.append("<h2>Runs (trace timelines)</h2>");
        for (var entry : data) {
            AgentRun run = entry.getKey();
            String statusCls = run.status().name().equals("SUCCESS") ? "pass" : "fail";
            html.append("<details><summary><span class='pill ").append(statusCls).append("'>")
                    .append(run.status()).append("</span>&nbsp;&nbsp;<code>").append(esc(run.taskId()))
                    .append("</code>&nbsp;&nbsp;<span style='color:var(--dim)'>")
                    .append(run.durationMs()).append("ms · ").append(run.totalTokens()).append(" tokens · ")
                    .append(run.startedAt()).append("</span></summary>");
            List<AgentEvent> events = run.events();
            for (int i = 0; i < events.size(); i++) {
                AgentEvent e = events.get(i);
                html.append("<div class='ev'>").append(i).append(". <b>").append(e.type()).append("</b> ")
                        .append(esc(String.valueOf(e.name()))).append(" · ").append(e.durationMs())
                        .append("ms · ").append(esc(String.valueOf(e.attributes()))).append("</div>");
            }
            if (run.finalAnswer() != null)
                html.append("<div class='ev'><b>ANSWER</b> ").append(esc(run.finalAnswer())).append("</div>");
            entry.getValue().ifPresent(ev -> {
                html.append("<div class='ev' style='padding-bottom:12px'><b>EVALUATION</b><ul style='margin:4px 0 0 20px'>");
                ev.results().forEach(r -> html.append("<li>")
                        .append(r.passed() ? "<span class='pill pass'>PASS</span>" : "<span class='pill fail'>FAIL</span>")
                        .append("&nbsp;<b>").append(r.evaluator()).append("</b> — ").append(esc(r.reason()))
                        .append("</li>"));
                html.append("</ul></div>");
            });
            html.append("</details>");
        }
    }

    private record RunEval(AgentRun run, Optional<EvaluationRun> evaluation) {
        RunEval(Map.Entry<AgentRun, Optional<EvaluationRun>> entry) {
            this(entry.getKey(), entry.getValue());
        }
        boolean passed() { return evaluation.isPresent() && evaluation.get().results().stream()
                .allMatch(com.agentlens.core.EvaluationResult::passed); }
    }

    private static boolean passed(Optional<EvaluationRun> evaluation) {
        return evaluation.isPresent() && evaluation.get().results().stream()
                .allMatch(com.agentlens.core.EvaluationResult::passed);
    }

    private static String card(String num, String label) {
        return "<div class='card'><div class='num'>" + num + "</div><div class='lbl'>" + label + "</div></div>";
    }

    private static String pct(double v) { return String.format("%.0f%%", v * 100); }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&#39;");
    }
}
