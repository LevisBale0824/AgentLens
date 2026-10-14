# AgentLens 近期任务清单

> **状态速览（2026-09）**：P0/P1 全部完成；pass^k 指标与发版门禁落地（ROADMAP 7.1）；诊断引擎升级为 trace 级规则引擎并完成 callId 感知重复调用检测（真重试/无进展/并行三分类）。适配器插件化（SPI）+ DSH Trace Adapter（7.2）：导入/受控/live 三模式均经真机端到端验证（live 落库 + pass^k 摘要 + 门禁；受控模式与 DSH 版 compare 见 docs/reports/dsh-controlled-compare-e2e.md）；compliance 红线评测器、Docker Demo、自包含 HTML 报告均已落地。**P2-1 LLM Judge 已落地**（docs/reports/llm-judge-design.md：结构化 JSON+重试+缓存+版本记录+fail-open，本地 Ollama 真机验证）。**P2-2 AIOpsLab 适配层已落地**（docs/reports/aiopslab-integration.md：97 题官方目录 → 场景数据集、session JSON → 统一 Trace 导入、SPI 集群桥接；完整集群实测待环境）。CI 全链路自动验证。**compare per-side 配置已落地**（`--baseline-override/--candidate-override` → 任务 context → DSH 适配器 `dshArgs`/`env.*`，逐 run 打印实测模型 + 覆盖项落库审计）。**P2-3 OTLP 导出阶段一已落地**（`OtelSpanMapper` Event→Span 确定性映射 + `OtlpExporter` OTLP/JSON `--otel-endpoint/--otel-out`，重试+fail-open，mock 冒烟验证结构；collector 对接实测待环境）。**P2-4 迷你 Web Dashboard 已落地**（`serve` 命令，运行筛选/趋势/trace+诊断详情/版本对比，见 ROADMAP 7.6）。**P2-5 配置校验 + 结构化日志 + SpotBugs/Checkstyle 门禁已落地**（fail-fast 校验 + `--log-json` 请求 ID NDJSON + `mvn -Pquality verify` 双静态分析门禁接入 CI，见 ROADMAP 7.7）。**Judge 标定框架 + DeepEval 风格指标适配器已落地**（`judge-calibrate` + `--deepeval`，qwen2.5:7b 基线报告 docs/reports/judge-calibration-qwen25-7b.md，见 ROADMAP 7.8/7.9）。**dirToken 路径碰撞已修复**（对照真机 DSH 编码收紧匹配）。**剩余重点：AIOpsLab 集群实测 + K8sGPT、collector 对接实测、更大 judge 模型 κ 对比、--repeats>=2 真机 pass^k。**

## 优先级定义

- **P0：必须优先完成**。阻塞后续开发，或决定项目架构是否成立。
- **P1：V1 必须完成**。没有这些能力，项目难以作为完整作品展示。
- **P2：重要增强项**。提升可信度和求职竞争力，但不阻塞核心闭环。

## 当前执行顺序

```text
P0 核心模型与 Trace
  ↓
P0 Agent Adapter
  ↓
P1 确定性评测
  ↓
P1 实验运行与持久化
  ↓
P1 诊断与回归门禁
  ↓
P1 运维 Demo / Benchmark
  ↓
P2 LLM Judge / Web UI / 外部集成
```

## P0：立即执行

### P0-1：完善核心领域模型

目标：让评测器不依赖具体 Agent 框架。

任务：

- 完善 `AgentTask`。
- 完善 `AgentEvent` 的事件类型和属性模型。
- 完善 `AgentRun` 状态枚举。
- 增加 `ToolCall`、`Assertion`、`EvaluationRun`、`Diagnosis` 模型。
- 统一错误类型和状态转换。
- 为领域模型增加单元测试。

完成标准：核心模块不依赖 Ollama、Prometheus、Kubernetes 或具体 Agent 框架。

### P0-2：固定统一 Trace 协议

目标：所有 Agent 都转换为统一事件格式。

任务：

- 定义 `model_call`、`tool_call`、`tool_result`、`retrieval`、`plan`、`error`、`final_answer` 事件。
- 定义必填字段、可选字段和版本号。
- 支持事件时间、耗时、输入输出摘要和敏感信息脱敏。
- 增加 JSON Schema 或等价校验。
- 编写 3 组固定 Trace 样例。

完成标准：HTTP、CLI、Mock Agent 输出相同语义的 Trace。

### P0-3：完成 Adapter 抽象

目标：接入 Agent 时只增加 Adapter，不修改评测器。

任务：

- 保留 `AgentAdapter` 接口。
- 实现 `MockAgentAdapter`。
- 实现 `HttpAgentAdapter`。
- 实现 `CliAgentAdapter`。
- 增加超时、退出码、异常和取消处理。
- 增加 Adapter 集成测试。

完成标准：至少两种 Agent 接入方式可以运行同一个 `AgentTask`。

## P1：V1 主线任务

### P1-1：实现确定性评测器

目标：先建立稳定、可复现的评测基础。

任务：

- 任务完成度。
- 期望工具调用。
- 禁止工具调用。
- 工具参数校验。
- PromQL 查询校验。
- 最大步数和循环检测。
- 超时和异常检测。
- 必须输出字段检查。

完成标准：同一 Trace 重复评测结果完全一致。

### P1-2：实现 Trace 与实验存储

目标：保留每次运行的完整证据。

任务：

- JSONL Trace Store。
- SQLite Run Store。
- run ID、task ID、agent 版本和配置快照。
- 评测结果和诊断结果持久化。
- 查询单次运行和批量运行。
- 敏感字段脱敏策略。

完成标准：可以根据 run ID 还原一次完整评测。

### P1-3：实现实验运行器

目标：支持批量、并发和失败隔离。

任务：

- 单任务运行。
- 数据集批量运行。
- 并发上限。
- 超时和有限重试。
- 单条任务失败不影响其他任务。
- P50/P95 延迟、步数、Token 和成本统计。

完成标准：一次运行至少支持 20 个测试任务，并输出汇总结果。

### P1-4：实现诊断引擎

目标：回答“为什么失败”。

首批诊断层级：

- `planning`
- `tool_selection`
- `tool_arguments`
- `retrieval`
- `execution`
- `final_answer`
- `safety`

诊断输出必须包含：

- 问题类型。
- 严重度。
- 置信度。
- 原始 Trace 证据。
- 建议动作。

完成标准：每个失败用例都能输出结构化诊断，而不是只有自然语言描述。

### P1-5：实现 baseline/candidate 回归门禁

目标：防止 Prompt、模型或 Agent 代码更新导致质量下降。

门禁规则：

- 任务成功率不得下降。
- 安全违规必须为 0。
- 禁止工具调用必须为 0。
- Faithfulness 下降不得超过 5%。
- P95 延迟不得增加超过 30%。

完成标准：命令行返回明确的通过/失败状态，能够在 CI 中使用。

### P1-6：构造最小运维评测数据集

目标：让项目有真实、可复现、可展示的数据。

数据来源：

- Mock Agent 行为样例。
- 自研最小运维 Agent。
- AIOpsLab 公开任务和环境。
- OpenTelemetry Demo。
- 本地 Prometheus/Kubernetes 故障注入。

首批场景：

- Pod 崩溃。
- 镜像拉取失败。
- Service 配置错误。
- CPU 过高。
- OOM。
- HTTP 5xx。
- 网络延迟。
- 数据库连接池耗尽。
- 错误工具调用。
- Prompt Injection。
- 危险操作未审批。

完成标准：至少 20 个场景，每个场景包含标准根因、允许动作、禁止动作和成功断言。

## P2：增强任务

### P2-1：集成 LLM Judge

- 设计 `LlmJudge` 接口。
- 支持 Ollama 和 OpenAI-compatible API。
- 结构化 JSON 输出。
- Schema 校验、重试和缓存。
- 记录 Judge 模型和 Prompt 版本。
- ✅ 复用 DeepEval 指标通过 Adapter 接入（board T7 阶段一，ROADMAP 7.9）：`DeepEvalAdapter`
  ——faithfulness / answer_relevancy / hallucination 三大经典指标跑在共享 `LlmJudge` 后端
  （OpenAI-compatible 或本地 Ollama），零 Python 依赖；`--deepeval` 启用、`--deepeval-metrics`
  选子集；fail-open 语义与 LlmJudgeEvaluator 一致（指标降级不拖垮 run）；6 单测。
  阶段二（原生 DeepEval/Ragas subprocess，需 Python 环境）待环境确认。
- ✅ 标定框架（board T6，ROADMAP 7.8）：`judge-calibrate` 命令——mock 行为语料
  （correct/wrong_tool/forbidden_action/loop/cheat_tamper/cheat_hardcode，确定性生成、
  零 Agent 侧成本）× N 次 × LLM judge（本地 Ollama），输出 accuracy/precision/recall/F1/
  **Cohen's kappa**（总体 + 分行为）与混淆矩阵，结果落 `output/judge-calibration.json`。
- ✅ `JudgeCalibration` 纯指标类（eval，5 单测：完美一致 κ=1、全 pass judge 高准确率低 κ、
  反向 judge κ<0、分组保序、空语料）。
- ⬜ 换更大 judge 模型对比 κ；标定结论写入 docs/reports/。

### P2-2：接入 AIOpsLab 和 K8sGPT（适配层已完成；集群实测待环境）

- ✅ 实现 AIOpsLab 任务 Adapter：官方 registry.py → 97 题目录（31 故障 × 4 任务类型），
  `aiopslab-catalog` 再生成 `data/scenarios/aiopslab-scenarios.json`。
- ✅ AIOpsLab session JSON → 统一 Trace（`import --aiopslab`）：ACI 动作解析、callId 配对、
  评测 + 诊断 + SQLite 持久化；重复调用检测在导入轨迹上实测触发。
- ✅ SPI 集群桥接（`AGENTLENS_AIOPSLAB_HOME` → 官方 clients/client.py → 自动导入）。
- ⬜ 运行可复现 Kubernetes 故障场景：待 kind + OpenEBS + Prometheus 环境就绪后实测桥接路径。
- ⬜ 接入 K8sGPT 作为外部被测 Agent（cli/http 适配器或 session 导入对比）。
- ⬜ 比较 Mock Agent、自研 Agent 和外部 Agent（依赖上两项）。

### P2-3：增加 OpenTelemetry 兼容

- ✅ 将 Agent Event 映射为 Span（`OtelSpanMapper`，core）：run = 根 Span（SERVER）+ 每事件
  子 Span（TOOL_CALL/MODEL_CALL 为 CLIENT），traceId/spanId 由 runId SHA-256 确定性派生，
  纳秒时间戳、属性 `agentlens.*` 前缀、ERROR 事件映射 Span error status。
- ✅ 支持 OTLP 导出（`OtlpExporter`，eval + CLI `--otel-endpoint` / `--otel-out`）：OTLP/JSON
  proto3 映射（hex id、int64 纳秒字符串、数字枚举），重试 + fail-open；NDJSON 文件模式可
  离线检查（mock 冒烟已验证结构）。
- ⬜ 对接 Phoenix 或 Langfuse（collector 端到端实测待环境；任何 OTLP/HTTP collector 通用）。
- ✅ 保留 AgentLens 自己的领域事件语义（OTel 仅为导出视图，统一 Trace 仍是唯一权威模型）。

### P2-4：增加 Web 报告

- ✅ 静态自包含 HTML 报告（`report` 命令，汇总卡片 / pass^k 分层表 / trace 时间线 / 评测明细）。
- ✅ 迷你 Web Dashboard（`serve` 命令，ROADMAP 7.6）：JDK HttpServer + 内嵌单页前端（零新
  依赖），运行列表按 task/状态/版本筛选、失败过滤、趋势条形图、单 run 详情（trace 时间线 +
  评测 + 现算诊断证据链）、baseline/candidate 版本对比、pass^k 分层视图；只读不落库。
- ⬜ V2 完整 Web 平台（多实验管理、鉴权、历史趋势持久化）。

### P2-5：增加工程质量保障

- ✅ JUnit 单元测试（190+，26+ 测试类）。
- ✅ Adapter 集成测试。
- ✅ Docker Compose / GitHub Actions。
- ✅ 运行时配置校验（ROADMAP 7.7）：`validate()` 命令分发前 fail-fast——场景文件存在性、
  repeats≥1、端口 1-65535、`--adapter` 名 SPI 可解析（报错附可用列表）、otel/judge URL 必须
  http(s)、show/report 数据库存在性；数值 flag 严格解析（`--repeats abc` 报 config error
  而非静默回落默认值）。
- ✅ 结构化日志 + 请求 ID（`--log-json`）：每次调用生成 `req-` 请求 ID（始终回显），
  stderr NDJSON（ts/level/event/requestId/kv），覆盖 cli.start/end、run.completed、
  gate.verdict、dashboard.start、otel.export_failed、run.persist_failed；默认输出不变。
- ✅ SpotBugs / Checkstyle 静态检查接入 Maven 与 CI（ROADMAP 7.7）：`mvn -Pquality verify`
  双门禁（Checkstyle 极简规则集 + SpotBugs effort=Max/threshold=Medium，全模块 0 违规 0 bug；
  修掉 52 处风格违规与 43 处真实缺陷——JDBC 资源泄漏、平台默认编码、Path.getFileName 空指针、
  min∘max 死代码等），CI 新增 quality 作业先行把关。
- ⬜ 日志级别过滤与文件输出（可选）。

## 借鉴落地清单（2026-10 深度调研，详见 docs/reports/peer-analysis.md）

对 6 个同类开源项目（Langfuse/Phoenix/promptfoo/DeepEval/TruLens/Inspect AI）+ 公共评测集的调研结论，
按优先级排列的待吸收项：

- **P0**：
  - ⬜ `otel-receive` OTLP/JSON 接收端（traceId 聚合→统一 Trace→自动评测→落库；属性对齐
    OTel GenAI 语义约定与 OpenInference；业界两家平台均原生接入 OTLP，我们目前只有导出侧）。
  - ⬜ 标定语料文件化 + mock 行为扩充：`correct_detailed`（证据丰满答案模板）+
    `wrong_conclusion`/`fabricated_evidence`/`partial_evidence` 三类边界行为 +
    `judge-calibrate --corpus data/judge-calibration-corpus.json`（版本化，回应 qwen2.5:7b κ=0）。
  - ⬜ Dashboard 基线/候选**逐用例 diff**（pass↔fail 高亮 + judge 理由列 + 失败用例 trace 下钻；
    对标 promptfoo Compare / Langfuse Compare Experiments）。
  - ⬜ CI 门禁契约细化：质量回归与工具错误退出码分离（promptfoo 100/1 模式）+
    `approved-baseline.json` 门禁工件固定 dataset/评测器版本（Langfuse 双发布策略）。
- **P1**：分数三来源模型（judge/人工/外部 API 统一挂 run）+ 人工复核队列；存原始 k 次分数、
  pass@k/pass^k/at_least_k 事后重算（Inspect epochs 模式）；p50/p95/成本时间序列；trace 瀑布图；
  断言代数（`not-` 反转/单条权重/assert-set k-of-n）；scorer 契约 {value, answer, explanation, reason}。
- **P2**：`bfcl-import`（BFCL 期望调用序列→Scenario）；τ-bench 政策场景→ComplianceEvaluator 用例；
  语料合成工作流（文档→LLM 生成→人工筛选固化）；Dashboard 静态 bundle 导出（PR 工件）；
  部位级 judge 绑定；失败 run 聚类；原生 DeepEval subprocess 阶段二（G-Eval/DAG）。

**差异化确认**：pass^k 发版门禁 + 诊断证据链在调研范围内仍无平台级同类；Inspect AI 的 `pass_k_k`
是打分归约器而非发布闸门。保持并强化这一优势，同时补齐 OTLP 接入与对比展示两块最大缺口。

## 当前最应该做的 5 件事

如果只能从少量任务开始，按以下顺序执行：

1. **P0-1：完善领域模型。**
2. **P0-2：固定统一 Trace 协议。**
3. **P0-3：完成 HTTP 和 CLI Adapter。**
4. **P1-1：完成确定性评测器。**
5. **P1-5：完成回归门禁。**

这五项完成后，AgentLens 就从项目骨架进入可运行的评测闭环；之后再接入 AIOpsLab、K8sGPT 和 LLM Judge。

