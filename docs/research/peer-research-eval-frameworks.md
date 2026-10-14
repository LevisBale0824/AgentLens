# 原始调研记录 ②：评测框架（promptfoo / DeepEval / TruLens / Inspect AI）

> 本文为 2026-10-13 同类项目调研的**原始记录**（AI 辅助在线调研，官方文档为准），
> 是 [peer-analysis.md](../reports/peer-analysis.md) 的来源材料之一。未经本地部署验证；
> 标注 unverified 的条目为未能从官方文档确认的内容。

## 1. promptfoo

### 1) Report & presentation
- CLI prints a results table during `promptfoo eval` (`--table/--no-table`); `-o` exports to csv/txt/json/jsonl/yaml/html/**junit.xml**. https://www.promptfoo.dev/docs/usage/command-line/
- `promptfoo view` starts a local web viewer. Views: eval selector (history switcher); display modes **All/Failures/Passes/Errors/Different/Highlights**; text/regex search; filter by metrics/metadata/pass-fail; table settings show **tokens, latency, cost, tokens/sec**; per-cell actions: details (full output + per-assertion grading results), human override pass/fail/score/comment (persisted, exportable as training data); eval-level **Compare = diff against another eval (green added / red removed)**; export "failed tests only"; charts: pass-rate %, **score-distribution histogram per prompt**, head-to-head prompt **scatter plot**; shareable URLs with filter state in URL. https://www.promptfoo.dev/docs/usage/web-ui/
- Trend/history = eval history list + eval-vs-eval diff; no time-series dashboard (charts are per-run).

### 2) Assertions/metrics worth stealing
- Model-graded: `llm-rubric`, `agent-rubric` (coding-agent grader inspects workspace/tool evidence), `search-rubric`, `model-graded-closedqa`, `factuality` (OpenAI public-evals prompt), `g-eval`, `answer-relevance`, `similar` (embeddings), `classifier` (HuggingFace), `moderation`, `select-best` (cross-prompt comparison), `max-score`, RAG trio `context-recall`/`context-relevance`/`context-faithfulness`, `conversation-relevance`, **`trajectory:goal-success`** (LLM judge over traced agent run). https://www.promptfoo.dev/docs/configuration/expected-outputs/model-graded/
- Deterministic: `contains/-all/-any`+`i*`, `equals`, `starts-with`, `regex`, `levenshtein`, `rouge-n/-l/-s`, `is-json`/`contains-json` (JSON-schema), `is-xml/html/sql`, `word-count`, `latency`, `cost`, `perplexity`, `finish-reason`, `is-valid-openai-tools-call`, **`tool-call-f1`**, **`trajectory:tool-used` / `trajectory:tool-args-match` / `trajectory:tool-sequence` / `trajectory:step-count`**, **`trace-span-count` / `trace-span-duration` (percentiles) / `trace-error-spans`**, `webhook`, JS/Python/Ruby asserts, `assert-set` (threshold of grouped asserts). **Every type negatable via `not-` prefix.** Grader override chain: `--grader` > `assertion.provider` > `test.options.provider` > `defaultTest`. https://www.promptfoo.dev/docs/configuration/expected-outputs/deterministic/

### 3) Datasets
- No shipped benchmarks; loader-centric: tests inline in YAML/JSON, `file://*.yaml`, **CSV/XLSX/Google Sheets with column headers as vars** and special columns `__expected`, `__expected1..N` (assertion strings like `contains: Paris`), `__description`, `__prefix/__suffix`, `__metric`, `__threshold`, `__metadata:*`; `scenarios`, `defaultTest`, `--filter-metadata`, `--repeat`, `generate dataset` (synthetic), OpenAI Evals dashboard JSONL importer. https://www.promptfoo.dev/docs/configuration/test-cases/

### 4) Release gating
- `promptfoo eval` exits **100** on ≥1 failure or pass-rate < `PROMPTFOO_PASS_RATE_THRESHOLD`; **1** for other errors; override via `PROMPTFOO_FAILED_TEST_EXIT_CODE`; junit.xml for CI; `--filter-failing <evalId>` re-runs only previous failures; official **`promptfoo/promptfoo-action@v1`** GitHub Action runs before/after eval on PRs touching prompts and posts a comparison comment linking the web viewer. **unverified**: per-metric regression gates.

## 2. DeepEval

### 1) Report & presentation
- pytest runner `deepeval test run` (terminal per-test scores + reasons; all metrics output **0–1 score + reasoning**, threshold default 0.5). Local storage: JSON `test_run_<timestamp>.json` per run or **SQLite `deepeval.db`** (tables: test_runs, test_cases, traces, spans, metric_data — BI-queryable); **`deepeval inspect` TUI** browses saved runs. Cloud web reports require **Confident AI**. **unverified**: whether a fully local web viewer still exists. https://deepeval.com/docs/evaluation-local-backend-storage

### 2) Assertions/metrics worth stealing
- 50+ metrics: Custom (JevEval, **G-Eval**, **DAG deep-acyclic-graph**, Arena G-Eval, DIY); **Agentic trajectory: Task Completion, Step Efficiency, Plan Adherence, Plan Quality; component-level: Tool Correctness, Argument Correctness**; RAG (Contextual Precision/Recall/Relevancy, Answer Relevancy, Faithfulness); multi-turn (Knowledge Retention, Role Adherence, Conversation Completeness/Relevancy); Safety (Bias, Toxicity, PIILeakage…); image metrics; others (Hallucination, Json Correctness, Summarization, Ragas); Non-LLM & classifier categories. https://deepeval.com/docs/metrics-introduction
- pytest: `assert_test(test_case, [metrics])` + `@pytest.mark.parametrize`, `deepeval test run` in GitHub Actions (exit code ⇒ gate). https://deepeval.com/docs/evaluation-unit-testing-in-ci-cd

### 3) Datasets
- `Golden`/`ConversationalGolden` + `EvaluationDataset`: in code, pull by alias from Confident AI cloud, `add_goldens_from_csv_file`, JSON load. Synthetic generation: Golden Synthesizer (`deepeval generate --method docs|contexts|scratch|goldens`), Conversation Simulator. Built-in **benchmarks**: BIG-Bench Hard, HellaSwag, MMLU, DROP, TruthfulQA, HumanEval, GSM8K. https://deepeval.com/docs/benchmarks-introduction

### 4) Release gating
- Per-metric `threshold` (default 0.5); pytest exit codes gate CI; baseline/regression-comparison guide; **`deepeval gate`** CLI — exits **0 only when the whole governance policy passes** (pass-rate bars, config filters; Enterprise platform feature). https://deepeval.com/docs/command-line-interface , https://www.confident-ai.com/docs/guides/gate-red-teamed-agents

## 3. TruLens

### 1) Report & presentation
- Local **Streamlit dashboard** (`run_dashboard()`; 2.x in trulens/dashboard/streamlit.py): **leaderboard comparing app versions** (score, latency, cost per version), **Trends page with time-series charts (Evaluation Metrics, Latency, App Cost, Evaluation Cost) + filters + record/conversation drill-down**; per-record feedback scores beside full traces; human feedback logging; OTel-native export. **unverified**: exact 2.x API name. https://www.trulens.org/getting_started/dashboard/ , https://www.trulens.org/getting_started/dashboard/trends/

### 2) Assertions/metrics worth stealing
- **Feedback functions** with a **Selector API** binding any metric input to trace-span attributes, plus aggregation; **RAG Triad: Context Relevance, Groundedness, Answer Relevance** (vendor-published judge-accuracy numbers: Groundedness F1 0.81 on LLM-AggreFact); stock metric families (classification/generation/embedding/ground-truth agreement); **2.x agentic evaluators: LogicalConsistency, ExecutionEfficiency, PlanAdherence, PlanQuality, ToolSelection, ToolCalling, ToolQuality**; batch/in-line evaluation; blocking guardrails. https://www.trulens.org/component_guides/evaluation/feedback_implementations/stock/

### 3) Datasets
- No shipped benchmark datasets. Batch eval via **Run API** over DB tables (Snowflake/Postgres); ground-truth-eval quickstarts; "Generating Test Cases" docs.

### 4) Release gating
- **No built-in CI gate/threshold exit code documented** — positioning is trace/monitor/compare. **unverified**: any `trulens` CLI gate.

## 4. Inspect AI

### 1) Report & presentation
- Terminal per-task results + log link; **`inspect view`** local web log viewer (port 7575, auto-refresh): log-history with per-run summaries, **live view of in-progress evals**, sample drill-down (full **message/tool-call transcript**, scoring explanation, tool metadata), filter by score, **sort to view one sample across epochs**, Info panel (dataset/solver/scorer/git revision/token usage); VS Code extension; **`inspect view bundle` → fully static viewer** deployable to GitHub Pages/S3; `--no-trust-content`; EvalLog API + `evals_df()` + Inspect Viz. https://inspect.aisi.org.uk/log-viewer.html

### 2) Assertions/metrics worth stealing
- Built-in scorers: `includes`, `match`, `pattern`, `answer`, `exact`, `f1`, `choice`, `math` (SymPy equivalence, sandboxed), `perplexity`/`target_perplexity`, **`model_graded_qa` / `model_graded_fact`**; scorer contract = **value + `answer` (extracted) + `explanation` (why)**, `reason="invalid_response_format"/"no_response"`.
- Metrics: accuracy, stderr (**clustered**), bootstrap_stderr, `ci`/`ci_wilson`, frequency, **`krippendorff_alpha` multi-judge agreement**, `grouped()` subgroup metrics; **epochs reducers: mean/median/mode/majority/max, `pass_at_{k}`, `pass_k_{k}` (pass^k = all k succeed), `at_least_{k}`, collect**; headline-metric declaration; `multi_scorer`; offline **`inspect score` re-scoring of logs** (swap judge without re-running agent). https://inspect.aisi.org.uk/scorers.html , https://inspect.aisi.org.uk/metrics.html

### 3) Datasets
- Loaders: `csv_dataset`/`json_dataset`, **HuggingFace `hf_dataset`** (FieldSpec mapping), **S3**, custom `record_to_sample`, `MemoryDataset`; rich `Sample` (input/choices/target/id/metadata/**sandbox/files/setup scripts**); filter/shuffle with seeds, `--sample-id` globs, epochs. **inspect_evals**: ~107 community benchmarks (Agent 25, Coding 14, Cybersecurity 11, Knowledge 20, Reasoning 20, Multimodal 9, Safeguards 15; HumanEval, MBPP, SWE-bench Verified, MLE-bench, GAIA, Cybench…). https://inspect.aisi.org.uk/datasets.html , https://github.com/UKGovernmentBEIS/inspect_evals

### 4) Release gating
- **No first-class release gate in core**; consumers build gates on logs/dataframes; pass^k is a scoring reducer, not a CI gate. **unverified**: `--fail-on-error` flag existence.

## 5. Cross-cutting matrix

| Capability | promptfoo | DeepEval | TruLens | Inspect AI |
|---|---|---|---|---|
| Local web viewer | ✅ `view`, matrix+diff+charts | ❌ (TUI + cloud) | ✅ Streamlit dashboard+leaderboard | ✅ `view`, transcripts+live |
| Run-vs-run diff | ✅ Compare mode + PR Action | ✅ (cloud/baseline guide) | ✅ version leaderboard | ⚠️ via EvalLog/evals_df |
| Trajectory/tool asserts | ✅ trajectory:*, trace-span:*, tool-call-f1 | ✅ Tool/Argument Correctness, plan metrics | ✅ ToolSelection/Calling/Quality, selectors | ✅ transcripts; scorers on state |
| Built-in benchmarks | ❌ | ✅ MMLU etc. (7) | ❌ | ✅ inspect_evals (~107) |
| Dataset loaders | ✅ CSV/XLSX/Sheets/YAML/JSON | ✅ CSV/JSON/cloud | ⚠️ DB tables | ✅ CSV/JSON/HF/S3 |
| Release gate | ✅ exit 100 + pass-rate env + GH Action | ✅ pytest exit + `deepeval gate` (platform) | ❌ | ❌ (reducers only) |
| pass^k | ⚠️ --repeat + assert-set | ❌ | ❌ | ✅ pass_at_k/pass_k_k/at_least_k |

## 6. Top adoptable ideas for a Java agent-eval tool

1. **promptfoo's assertion algebra**: one schema mixing deterministic + judge + cost/latency/trajectory asserts per case; `not-` prefix inversion; per-assert `threshold`/`weight`/`metric` labels; `assert-set` (k-of-n group thresholds); grader override chain.
2. **Two-tier dataset story**: spreadsheet-first goldens (CSV/XLSX with `__expected` columns) so PMs can author tests, plus named pull/push dataset registry for CI; synthetic golden generation from docs for bootstrap.
3. **Explicit CI gate contract** (promptfoo): exit **100** = quality regression vs exit 1 = harness error; JUnit XML; `--filter-failing <runId>`; GitHub Action posting before/after diff comment with deep link into the viewer.
4. **Inspect's epoch-reducer axis + scorer contract**: store *unreduced* k-run scores so pass@k / pass^k / at_least_k / mean are recomputable post-hoc; declare `headlineMetric` per suite; every scorer returns {value, answer, explanation, reason} — feeds the diagnosis engine (format-error vs wrong-answer vs judge-noise) and enables Krippendorff-α multi-judge calibration and offline `rescore`.
5. **Dashboard feature set**: matrix table with Failure/Different/Highlight display modes, eval-vs-eval cell diff, per-cell judge reasoning, score-distribution histogram + prompt-vs-prompt scatter, version leaderboard (score/latency/cost per build) + trends, static `bundle` export so CI can attach the whole dashboard as a PR artifact.

## Sources
[promptfoo web UI](https://www.promptfoo.dev/docs/usage/web-ui/) / [model-graded](https://www.promptfoo.dev/docs/configuration/expected-outputs/model-graded/) / [deterministic](https://www.promptfoo.dev/docs/configuration/expected-outputs/deterministic/) / [CLI](https://www.promptfoo.dev/docs/usage/command-line/) / [test-cases](https://www.promptfoo.dev/docs/configuration/test-cases/) / [GH Action](https://www.promptfoo.dev/docs/integrations/github-action/)；[DeepEval metrics](https://deepeval.com/docs/metrics-introduction) / [CI-CD](https://deepeval.com/docs/evaluation-unit-testing-in-ci-cd) / [local storage](https://deepeval.com/docs/evaluation-local-backend-storage) / [CLI](https://deepeval.com/docs/command-line-interface) / [gate](https://www.confident-ai.com/docs/guides/gate-red-teamed-agents)；[TruLens RAG triad](https://www.trulens.org/getting_started/core_concepts/rag_triad/) / [stock metrics](https://www.trulens.org/component_guides/evaluation/feedback_implementations/stock/) / [trends](https://www.trulens.org/getting_started/dashboard/trends/)；[Inspect log viewer](https://inspect.aisi.org.uk/log-viewer.html) / [scorers](https://inspect.aisi.org.uk/scorers.html) / [metrics](https://inspect.aisi.org.uk/metrics.html) / [datasets](https://inspect.aisi.org.uk/datasets.html) / [inspect_evals](https://github.com/UKGovernmentBEIS/inspect_evals)
