# AgentLens 近期任务清单

> **状态速览（2026-09）**：P0/P1 全部完成；pass^k 指标与发版门禁落地（ROADMAP 7.1）；诊断引擎升级为 trace 级规则引擎并完成 callId 感知重复调用检测（真重试/无进展/并行三分类）。适配器插件化（SPI）+ DSH Trace Adapter（7.2）：导入/受控/live 三模式均经真机端到端验证（live 落库 + pass^k 摘要 + 门禁；受控模式与 DSH 版 compare 见 docs/reports/dsh-controlled-compare-e2e.md）；compliance 红线评测器、Docker Demo、自包含 HTML 报告均已落地。**P2-1 LLM Judge 已落地**（docs/reports/llm-judge-design.md：结构化 JSON+重试+缓存+版本记录+fail-open，本地 Ollama 真机验证）。CI 全链路自动验证。**剩余重点：AIOpsLab 接入（P2-2）、OpenTelemetry 兼容（P2-3）、DeepEval/Ragas 指标接入、judge 标定。**

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
- 复用 DeepEval/Ragas 指标时通过 Adapter 接入。

### P2-2：接入 AIOpsLab 和 K8sGPT

- 实现 AIOpsLab 任务 Adapter。
- 运行可复现 Kubernetes 故障场景。
- 接入 K8sGPT 作为外部被测 Agent。
- 比较 Mock Agent、自研 Agent 和外部 Agent。

### P2-3：增加 OpenTelemetry 兼容

- 将 Agent Event 映射为 Span。
- 支持 OTLP 导出。
- 对接 Phoenix 或 Langfuse。
- 保留 AgentLens 自己的领域事件语义。

### P2-4：增加 Web 报告

- 运行列表。
- 单次 Trace 时间线。
- 指标趋势。
- 失败样例。
- 诊断证据。
- baseline/candidate 对比。

### P2-5：增加工程质量保障

- JUnit 单元测试。
- Adapter 集成测试。
- Docker Compose。
- GitHub Actions。
- SpotBugs / Checkstyle。
- 运行时配置校验。
- 结构化日志和请求 ID。

## 当前最应该做的 5 件事

如果只能从少量任务开始，按以下顺序执行：

1. **P0-1：完善领域模型。**
2. **P0-2：固定统一 Trace 协议。**
3. **P0-3：完成 HTTP 和 CLI Adapter。**
4. **P1-1：完成确定性评测器。**
5. **P1-5：完成回归门禁。**

这五项完成后，AgentLens 就从项目骨架进入可运行的评测闭环；之后再接入 AIOpsLab、K8sGPT 和 LLM Judge。

