# 原始调研记录 ①：Langfuse 与 Arize Phoenix 深析

> 本文为 2026-10-13 同类项目调研的**原始记录**（AI 辅助在线调研，官方文档与 GitHub 为准），
> 是 [peer-analysis.md](../reports/peer-analysis.md) 的来源材料之一。未经本地部署验证；
> 数字为公开资料约数；细节以各项目官方文档当前状态为准。

Snapshot: **Langfuse** — github.com/langfuse/langfuse, 35.8k★, TypeScript. **Phoenix** — github.com/Arize-ai/phoenix, 11.8k★, Python. Both publish docs as plain markdown for agents ([langfuse.com/llms.txt](https://langfuse.com/llms.txt), [arize.com/docs/phoenix/llms.txt](https://arize.com/docs/phoenix/llms.txt)).

---

## 1. Langfuse

### 1.1 Report & UI presentation (views shipped)
- **Traces list** with filter/search query bar, **Pulse** outlier strip (count/cost/latency spikes; drag to filter), and "chart any table" — [filter-search-bar](https://langfuse.com/docs/observability/features/filter-search-bar), [pulse](https://langfuse.com/docs/observability/features/pulse), [events-table-charts](https://langfuse.com/docs/observability/features/events-table-charts)
- **Trace detail**: observation tree (span/generation/event types) + **Agent Graphs** (aggregated or as-it-ran DAG view) — [agent-graphs](https://langfuse.com/docs/observability/features/agent-graphs), [data-model](https://langfuse.com/docs/observability/data-model)
- **Sessions** view: groups traces by `session.id`; "session replay" of whole conversation; publish public link; bookmark; annotate sessions with scores — [sessions](https://langfuse.com/docs/observability/features/sessions)
- **Scores → Analytics tab**: pick 1–2 scores of same type over traces/observations/sessions/dataset-run-items; stats card (count, mean/mode, stdev); distribution chart; trend-over-time chart (5m→1y intervals); 2-score comparison with heatmap/confusion-matrix and stats: Pearson/Spearman, MAE/RMSE (numeric), Cohen's Kappa, F1, overall agreement (categorical/boolean); "matched vs all" tabs to expose coverage gaps — [score-analytics](https://langfuse.com/docs/evaluation/scores/score-analytics)
- **Evaluators page + Rules**: build LLM-as-a-judge, code, and decision-model evaluators in the UI with variable mapping and sample-based test runs; every evaluator execution writes a debug trace — [llm-as-a-judge](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge), [code-evaluators](https://langfuse.com/docs/evaluation/evaluation-methods/code-evaluators)
- **Experiments view + Compare Experiments**: select multiple dataset runs, pick a baseline, side-by-side table of inputs/outputs/scores/cost/latency deltas per case with score thresholds, trace "peek" beside the table for failing items; explicit guidance that equal averages can hide regressions (Pass→Fail case table) — [compare-experiments](https://langfuse.com/docs/evaluation/experiments/compare-experiments)
- **Datasets & Dataset Runs**, **Annotation Queues**, **Prompts + Playground**, **Custom Dashboards**, **Users/Costs views**, **Alerts** — [annotation-queues](https://langfuse.com/docs/evaluation/evaluation-methods/annotation-queues), [custom-dashboards](https://langfuse.com/docs/metrics/features/custom-dashboards), [alerts](https://langfuse.com/docs/observability/features/alerts), [playground](https://langfuse.com/docs/prompt-management/features/playground)
- **Eval result presentation**: individual scores (value + comment + reasoning) on trace/observation/session/dataset-run-item; aggregates via Score Analytics and dashboards; cross-experiment comparison in compare view — [scores/overview](https://langfuse.com/docs/evaluation/scores/overview), [metrics-api](https://langfuse.com/docs/metrics/features/metrics-api)
- **Export**: CSV + JSON from most UI tables; scheduled export to S3/GCS/Azure Blob; full Public API; Metrics API for BI — [export-from-ui](https://langfuse.com/docs/api-and-data-platform/features/export-from-ui), [export-to-blob-storage](https://langfuse.com/docs/api-and-data-platform/features/export-to-blob-storage). Also CLI ([`npx @langfuse/cli`](https://langfuse.com/docs/api-and-data-platform/features/cli)) and MCP server.

### 1.2 Evaluation features
- **Score model**: universal Score object, 4 data types: `NUMERIC`, `CATEGORICAL`, `BOOLEAN` (0/1), `TEXT` (excluded from experiments/analytics); optional comment = judge reasoning; ScoreConfigs define allowed values — [scores/overview](https://langfuse.com/docs/evaluation/scores/overview), [data-model](https://langfuse.com/docs/evaluation/scores/data-model)
- **Dataset/experiment model**: Dataset → DatasetItems (input/expectedOutput/metadata, versioned) → DatasetRun → DatasetRunItems → Scores on traces; Task / Evaluator / **RunEvaluator** (run-level aggregates); local-data experiments; **no repetitions — each item appears once per experiment** ([issue #5855](https://github.com/langfuse/langfuse/issues/5855)) — [data-model](https://langfuse.com/docs/evaluation/experiments/data-model)
- **LLM-as-a-judge**: model via LLM Connection, rubric prompt with `{{variables}}`, numeric/categorical/boolean structured output; server-side worker, triggered by filter Rules (sampling, `isRootObservation`); multimodal judging; batch backfill; judge execution fully traced — [llm-as-a-judge](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge)
- **Code evaluators**: Python/TS sandbox, stdlib only, no network, 2 s limit, ≤256KB source; return multiple typed scores — [code-evaluators](https://langfuse.com/docs/evaluation/evaluation-methods/code-evaluators)
- **Decision-model evaluators**: typed structured verdicts — same docs section.
- **Gating/regression**: [`langfuse/experiment-action`](https://langfuse.com/docs/evaluation/experiments/experiments-ci-cd) GitHub Action; raise `RegressionError` to fail CI; two release policies ("every required case must pass"; "no approved-passing case may regress" vs reviewed `approved-baseline.json` pinned to dataset-version + evaluator-version); missing/invalid evaluator results must fail the gate; PR comment with per-case table + compare-view link; Pytest/Vitest patterns.
- **Judge calibration**: human-vs-judge agreement (Cohen's κ, confusion matrix) — [score-analytics#human-vs-ai-annotation-agreement](https://langfuse.com/docs/evaluation/scores/score-analytics#human-vs-ai-annotation-agreement)

### 1.3 Ingestion
- OTel-native backend: OTLP **HTTP only** (protobuf or JSON; **gRPC NOT supported**) at `/api/public/otel` (`/v1/traces`), Basic auth from project keys; spans with `gen_ai.*` render as generations with model/token/cost; `user.id`/`session.id` drive grouping — [opentelemetry-languages](https://langfuse.com/resources/engineering/opentelemetry-languages)
- First-party SDKs: **Python, JS/TS** (both OTel-based); documented recipes for Go, **Java via javaagent/OpenLLMetry**, C#, Ruby; LiteLLM proxy ingestion; native framework integrations — same source, [sdk/overview](https://langfuse.com/docs/observability/sdk/overview)
- **Java relevance**: no first-party Java SDK; documented path is OTel Java agent + `gen_ai.*` attributes.

### 1.4 Ready-made datasets
User-defined only; public **demo project** with live traces/feedback, not an eval benchmark — [demo](https://langfuse.com/docs/demo). No curated benchmark datasets documented (unverified whether any exist).

### 1.5 License / self-host
- Pricing page: **OSS tier = MIT, "all core platform features and APIs"**; Docker Compose/K8s Helm/Terraform — [pricing-self-host](https://langfuse.com/pricing-self-host)
- Enterprise: management APIs, project-level RBAC, retention policies, audit logs, SOC2/ISO, bundled ClickHouse — same source. GitHub API license = NOASSERTION (ee/ split unverified — LICENSE fetch failed).

---

## 2. Arize Phoenix

### 2.1 Report & UI presentation (views shipped)
- **Projects → Traces** with Python-style **filter expressions** + **project metric charts** (latency, token, cost, error rates; drag-to-zoom) — [metrics](https://arize.com/docs/phoenix/tracing/llm-traces/metrics), [analytics SQL release](https://www.arize.com/docs/phoenix/release-notes/08-17-2026-trace-filters-and-analytics-sql)
- **Span/trace detail (waterfall)**; **Sessions** (multi-turn grouping, session annotations/notes) — [sessions](https://arize.com/docs/phoenix/tracing/llm-traces/sessions)
- **Annotations UI**: label/score traces, spans, retrieved documents; annotations carry explanations; annotation charts — [annotating-in-the-ui](https://arize.com/docs/phoenix/tracing/how-to-tracing/feedback-and-annotations/annotating-in-the-ui)
- **Datasets**: Examples / Versions / **Experiments** / **Metrics** tabs; **baseline badge** ("Mark as baseline" persists) + compare view; **Metrics tab = time-series bar charts of run latency/error/cost/tokens across recent experiments** — [running-experiments](https://arize.com/docs/phoenix/datasets-and-experiments/how-to-experiments/running-experiments)
- **Experiment detail**: results table sortable/filterable by evaluator score; **repetition carousel** (per-rep scores + average); **optimization direction** (maximize/minimize/none) marks better/worse runs; drill into linked traces
- **Prompt Playground** (variants over datasets, evaluator attach, results as experiments) + **Span Replay**; **Embeddings visualizer** — [playground](https://arize.com/docs/phoenix/prompt-engineering/overview-prompts/prompt-playground), [user-guide](https://arize.com/docs/phoenix/user-guide)
- **Evaluator tracing**: every eval run OTel-traced into a dedicated project (input, judge prompt, reasoning, scores, timing) — [llm-evals](https://arize.com/docs/phoenix/evaluation/llm-evals)
- **PXI** built-in AI agent (inspects traces, iterates prompts, runs experiments, files GitHub issues) — [pxi](https://arize.com/docs/phoenix/pxi)
- **Export**: REST `GET /v1/experiments/{id}/json|csv`, `GET /v1/datasets/{id}/csv|jsonl`; `SpanQuery` → dataframe; `px` CLI `--format json` — [experiments CSV](https://arize.com/docs/phoenix/sdk-api-reference/rest-api/api-reference/experiments/download-experiment-runs-as-a-csv-file), [import-export](https://arize.com/docs/phoenix/tracing/how-to-tracing/importing-and-exporting-traces)
- No general user-built dashboards; alerting/monitoring in commercial **Arize AX** — [llm-evals note](https://arize.com/docs/phoenix/evaluation/llm-evals)

### 2.2 Evaluation features
- **Output model**: `{label, score, explanation, metadata}`; LLM judges via **tool-calling structured output** (label enum → numeric score), explanation always — [llm-evals](https://arize.com/docs/phoenix/evaluation/llm-evals)
- **Dataset/experiment model**: versioned Examples (input/reference/metadata) → Task → Evaluators → experiment per dataset version; **dataset evaluators auto-run on every experiment**; post-hoc `evaluate_experiment`; `dry_run` — [overview-datasets](https://arize.com/docs/phoenix/datasets-and-experiments/overview-datasets)
- **Repetitions (native)**: `repetitions=N` per example (Phoenix ≥11.37.0); UI carousel + average; built for nondeterminism measurement. **pass^k not built-in** — [repetitions](https://arize.com/docs/phoenix/datasets-and-experiments/how-to-experiments/repetitions)
- **Splits**: train/test/validation — [splits](https://arize.com/docs/phoenix/datasets-and-experiments/how-to-experiments/splits)
- **Pre-built evaluators**: faithfulness, hallucination, toxicity, QA correctness, retrieval relevance, completeness, conciseness, refusal, PII, user friction, tool selection/invocation/response-handling, exact match, regex, precision/recall/F; custom via `create_classifier`; executors handle rate limits/concurrency — [pre-built-metrics](https://arize.com/docs/phoenix/evaluation/pre-built-metrics)
- **Server-side evals**: UI-attached LLM/code evaluators per dataset, managed sandbox (Python/TS); server code metrics: contains, exact match, Levenshtein, JSON distance, regex; recipes: pairwise blind judge, **LLM jury**, composite weighted scorer, embedding distance — [server-evals](https://arize.com/docs/phoenix/evaluation/server-evals/overview), [llm-jury](https://arize.com/docs/phoenix/evaluation/server-evals/code-evaluators/llm-jury)
- **Evals-as-tests / gating**: pytest plugin (`arize-phoenix-client[pytest]`) — file→dataset, parametrized case→example, run→experiment, assert→`pass` annotation; philosophy **"assert hard invariants (gate CI), score quality signals (trend)"**; repetitions + pytest-xdist; Vitest/Jest equivalent; gate = pytest exit code — [pytest](https://arize.com/docs/phoenix/evaluation/integrations/pytest)
- **Baseline/regression**: persistent baseline per dataset + compare view; no server-enforced threshold gate in UI (unverified any in-UI gate).

### 2.3 Ingestion
- Built on **OpenTelemetry**: OTLP **HTTP :6006 `/v1/traces` + gRPC :4317**; `phoenix.otel.register()` / `@arizeai/phoenix-otel` — [get-started](https://arize.com/docs/phoenix/get-started)
- **OpenInference** conventions native; **auto-converts OTel GenAI semconv → OpenInference** — [semantic-conventions](https://arize.com/docs/phoenix/tracing/concepts-tracing/otel-openinference/semantic-conventions)
- SDKs: **Python** (`arize-phoenix-client`/`-evals`/`-otel`), **TypeScript**, `px` CLI + MCP; any OTel-language app via plain OTLP; no first-party Java SDK — [integrations](https://arize.com/docs/phoenix/integrations)

### 2.4 Ready-made datasets
None bundled; user-defined (traces, CSV, code) — [creating-datasets](https://arize.com/docs/phoenix/datasets-and-experiments/how-to-datasets/creating-datasets). Verified-by-absence in docs index.

### 2.5 License / self-host
- **Elastic License 2.0 (ELv2)**; self-host free, "no feature gates"; pip/`phoenix serve`, Docker, K8s/Helm; PostgreSQL; OAuth2/LDAP + RBAC self-hosted — [license](https://arize.com/docs/phoenix/self-hosting/license)

---

## 3. Adopt list for a Java agent-eval tool

**From Langfuse:**
1. Typed Score object (numeric/categorical/boolean/text) + comment carrying judge reasoning, attachable at trace/span/session/experiment-item level.
2. Approved-baseline CI gate: pinned dataset+evaluator versions, two release policies, missing eval results fail the gate; newer successful run never auto-becomes baseline.
3. RunEvaluator contract: run-level aggregate evaluators (natural home for pass^k).
4. Score Analytics: distribution + trend + judge-vs-human agreement stats (κ, confusion matrix).
5. Evaluator-execution debug traces.

**From Phoenix:**
1. Native `repetitions` + per-repetition carousel with average — data backbone for pass^k.
2. Optimization-direction metadata (maximize/minimize/none).
3. "Assert invariants, score signals" eval-as-tests model — maps to a JUnit 5 extension.
4. Dataset-attached evaluators auto-running on every experiment + deterministic pre-builts.
5. Diagnosis UX: Metrics tab trending across experiment versions + drill-through from failing case to trace.

**Key gaps vs. the target tool**: Langfuse has no experiment repetitions (#5855) and no pass^k; Phoenix has no server-enforced release gate and no user-built dashboards (commercial AX); neither ships curated eval datasets; Langfuse rejects OTLP gRPC, Phoenix accepts both.
