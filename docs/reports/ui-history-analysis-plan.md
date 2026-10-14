# 看板式历史数据分析（UI Import）设计与规划

- 日期：2026-10-14 ｜ 状态：规划（未实施）
- 交互原型：[docs/prototypes/ui-history-analysis.html](../prototypes/ui-history-analysis.html)（纯前端 Mock，未接后端）
- 背景：P0 交付后复盘——历史数据（DSH 会话 JSONL、OTLP 轨迹 NDJSON、标定/对比工件）的分析入口全部在 CLI，
  用户期望"界面上做到这些事"。对标业界三个流派后立项。

## 1. 竞品参考与提炼的 UI 模式

| 模式 | 来源产品 | 对 AgentLens 的借鉴 |
|---|---|---|
| **拖拽即导入**：文件拖入 → 自动探测类型 → 入库 → 出现在列表 | Jaeger UI 的 trace JSON 文件导入（jaeger-ui PR #327，支持 OTLP JSON 文件格式；`.jsonl` 尚不支持，见 issue #2458） | 导入零表单：拖入 `session.jsonl(.zstd)` / OTLP NDJSON → 服务端探测 → 落库 → runs 列表即刻可见 |
| **本地 CLI 配 Web Viewer**：JSON 日志即界面数据源 | Inspect AI `inspect view`（JSON 评测日志→本地 Web UI）；promptfoo `promptfoo view`（eval 结果 JSON→逐用例表格+跨 eval 对比） | 我们的 `serve` 已是这个形态的一半；缺口只是"把文件喂进来"的入口 |
| **三层下钻：列表 → 详情 → 原文** | promptfoo / Inspect / Langfuse | runs 列表与详情已有；补"原始 JSON 抽屉"（M2） |
| **跨版本逐用例对比** | promptfoo Compare / Langfuse Experiments | 已有（P0-C diff），增加"任意两次导入直接对比"的入口 |
| **从历史 trace 建 dataset case** | LangSmith / Braintrust（"Add to dataset"） | M3：详情页一键"保存为评测场景"，历史会话沉淀为回归用例 |
| **人工标注/评审** | Langfuse annotations、LangSmith feedback | M3：UI 标注 pass/fail + 备注，喂给 approved-baseline 复审流程 |
| **会话回放** | AgentOps session replay | M3+：事件按时间步进回放（低优先） |
| **摄取走 API/OTLP，无文件上传** | Langfuse（仅 HTTP OTLP）、Phoenix（双栈） | 反面参照：它们没有文件入口，历史数据只能重新上报——我们以文件导入为差异化 |

## 2. 现状与能力缺口

已有：`serve` 看板（runs 列表/筛选/趋势/场景分层、run 详情=时间线+评测+诊断、P0-C 逐用例 diff）、
`import --session`（DSH JSONL/zstd → 统一 Trace → 评测+诊断）、`import --aiopslab`（同上且落库）、
`otel-receive`（活流 OTLP→评测→落库）、`DshSessionLogReader`（zstd 容器解码）、`OtlpIngestMapper`。

| # | 缺口 | 说明 | 里程碑 |
|---|---|---|---|
| G1 | **UI 导入入口** | 看板无任何文件导入；历史数据只能 CLI | M1 |
| G2 | **DSH import 不落库** | `importSession` 只打印评测结果（AIOpsLab 路径落库，不对称），分析结果进不了 show/report/serve | M1 |
| G3 | **共享落库服务层缺失** | 评测+saveRun+saveEvaluation 逻辑散在 run/compare/otel-receive/import 四处 | M1 |
| G4 | **source 溯源** | 落库数据无法区分原生/导入，看板无法筛选 | M1 |
| G5 | **类型探测与冲突策略** | 多种历史 JSON 共用一个入口需要自动探测；runId 冲突需 skip/overwrite 语义 | M1 |
| G6 | **时间线可视化弱** | 现为行式列表，无时间比例感（对标 Jaeger waterfall / Langfuse 树） | M2 |
| G7 | **工件查看页** | 标定 JSON / compare 工件 / approved-baseline 只能 CLI 或脚本读 | M2 |
| G8 | **trace → 评测场景沉淀** | 历史会话不能一键变成回归用例 | M3 |
| G9 | **人工标注** | 评审结论（pass/fail+理由）无 UI 入口，approved-baseline 复审靠 CLI | M3 |

## 3. 目标体验（用户旅程）

1. 打开看板 → "导入历史"标签 → 把 `session.jsonl.zstd`、`otel-traces.ndjson` 拖进导入区；
2. 服务端逐文件探测类型（文件名 + 首行内容嗅探），显示"识别为：DSH 会话 / OTLP 轨迹 / AIOpsLab 结果"；
3. 确认导入 → 逐条落库（评测器照跑、source=ui-import）→ 结果卡列出每个文件的 runId / 评测摘要 / 跳过与冲突；
4. 新数据即时出现在 runs 列表（source 徽标可筛）→ 点开详情（M2 起为瀑布时间线）→ 进入 diff 选为对比侧；
5. （M3）详情页"保存为场景"把这条历史会话沉淀进回归语料。

## 4. API 与数据流设计

```
POST /api/import          multipart/form-data, files[]（上限 64MB/文件）
  → 探测：文件名后缀（.zstd/.jsonl/.json）+ 首行嗅探
      contains "resourceSpans"            → otlp-ndjson（多 run）
      DSH 会话事件字段（uuid/type=...）    → dsh-session（zstd 走后端 DshSessionLogReader）
      AIOpsLab results 结构               → aiopslab
  → 复用 SessionImportService：map → evaluate(确定性评测器) → saveRun(source 标记) → saveEvaluation
  → ?onConflict=skip|overwrite（默认 skip；runId 已存在则记 skipped）
  → 响应 [{file, detectedType, runs:[{runId, taskId, status}], skipped:[...], error}]

GET  /api/runs            （现有）增加 source 字段与 ?source= 过滤
GET  /api/runs/{id}       （现有）M2 增加 raw JSON 抽屉数据
```

新增 `SessionImportService`（cli 层，G3 收编）：`detect(Path/text) → ImportOutcome`，
CLI `import --session` 改为调用它并落库（修 G2），`OtelReceiveServer.persist` 的落库逻辑同源。

写路径安全（对 read-only by design 的让步）：仅新增 `/api/import` 一个写端点；所有导入数据
`evaluation.metadata.source = ui-import|cli-import`；UI 明显位置标识"导入数据"徽标；不做删除。

## 5. 里程碑

- **M1 导入闭环**：SessionImportService + DSH import 落库 + `POST /api/import` + 看板拖拽面板 +
  source 列/筛选。验收：拖入真实 `session.jsonl.zstd` → 列表出现 → 详情可看 → 可选入 diff。
- **M2 分析增强**：瀑布时间线（时间比例条+类型着色，对标 Jaeger）、原始 JSON 抽屉、
  标定/compare/approved-baseline 工件查看页、导入历史记录表。
- **M3 数据沉淀**：详情页"保存为评测场景"（trace→dataset case）、人工标注（进 approved-baseline
  复审）、事件步进回放。

## 6. 风险与决策点

- **read-only 原则让步**：唯一写端点 `/api/import`，默认 skip 冲突、无删除，风险可控。
- **大文件**：64MB/文件上限 + NDJSON 逐行流式解析；zstd 必须原文件上传（浏览器不解 zstd）。
- **评测器集合**：导入场景无 scenario context，沿用 otel-receive 的合成 task 语义
  （task-completion + run-limits，宽松 maxSteps）；`--judge` 化为导入面板可选开关（M2）。
- **隐私**：会话 JSON 可能含真实代码/日志，看板仅绑定 127.0.0.1（现状即如此），不做远程共享。
