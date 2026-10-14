# 同类开源项目深度分析：可借鉴功能 · 报告形态 · 评测集

> 调研日期：2026-10-13。方法：三路并行在线调研（官方文档与 GitHub 仓库为准），**未做本地部署验证**；
> 规模数字为公开资料约数，许可/版本以各仓库当前状态为准。回答三个问题：
> ① 哪些功能值得做进 AgentLens；② 业界如何产出报告、是否有专门网页、如何展示；③ 业界有哪些评测集、如何补强本项目 mock 语料。

## 0. 结论速览

**总体判断**：评测"记录与打分"环节业界已经卷得很成熟（Langfuse/Phoenix/promptfoo 三家形态趋同），
但 **"评测作为发版门禁"（pass^k + 基线候选对比 + 诊断证据链）依然没有平台级产品**——Inspect AI 把 pass^k
做成了打分 reducer（`pass_k_k`），τ-bench 把它做成基准指标，但没有任何一家把它做成**发布闸门**。
AgentLens 的差异化定位成立；同时有大量"记录侧"的优秀设计值得吸收。

**三问一句话答案**：
1. **借鉴**：OTLP 接收端（两家都原生支持，我们只有导出）、逐用例对比 diff、分数三来源模型、
   原始 k 次分数存储（事后重算 pass^k/pass@k）、断言代数（not- 前缀/权重/组阈值）、CI 门禁契约（退出码语义）。
2. **报告**：全部有专门 Web 界面（本地 viewer 或自托管 Web 应用）；共同形态是"用例矩阵 + 失败/差异过滤 +
   judge 理由列 + 分数分布图 + 运行间 diff + 版本排行榜"。
3. **评测集**：Langfuse/Phoenix/promptfoo 均不自带评测集（用户自建）；Inspect AI 靠 `inspect_evals` 适配
   ~107 个公共基准；K8s 运维方向唯一成体系的是 AIOpsLab（已接入）。mock 语料补强：BFCL 调用序列导入 +
   τ-bench 政策场景 + 三类边界行为 + 语料文件化版本化。

**落地优先级**：

| 级别 | 项 | 来源 |
|---|---|---|
| P0 | `otel-receive` OTLP/JSON 接收端 | Langfuse(`/api/public/otel`)、Phoenix(:6006/:4317) |
| P0 | 标定语料文件化 + `correct_detailed` + 3 类边界行为（回应 κ=0） | DeepEval golden 工作流、Ragas |
| P0 | Dashboard 基线/候选逐用例 diff（pass↔fail 高亮） | promptfoo Compare、Langfuse Compare Experiments |
| P0 | CI 门禁契约：质量回归 vs 工具错误退出码分离 + 基线工件固定版本 | promptfoo exit 100、Langfuse approved-baseline |
| P1 | 分数三来源（judge/人工/外部 API）+ 人工复核队列 | Langfuse scores、Annotation Queues |
| P1 | 存原始 k 次分数，pass@k/pass^k/at_least_k 事后重算 | Inspect epochs reducers |
| P1 | p50/p95/成本时间序列 + trace 瀑布图 | Langfuse/Phoenix/TruLens |
| P1 | 断言代数：`not-` 反转、单条权重/阈值、assert-set k-of-n | promptfoo |
| P1 | scorer 契约 {value, answer, explanation, reason} 喂给诊断引擎 | Inspect AI |
| P2 | `bfcl-import`（BFCL 期望调用序列→Scenario）；τ-bench 政策场景 | 公共评测集 |
| P2 | 语料合成工作流（文档→LLM 生成→人工筛选固化） | DeepEval Synthesizer |
| P2 | 静态 bundle 导出（Dashboard 打包成 PR 工件）、JUnit XML、部位级 judge 绑定、失败聚类 | Inspect/promptfoo/TruLens/Phoenix |

## 1. 逐项目深析

### 1.1 Langfuse（MIT 核心，TypeScript，约 35.8k★）——评测记录形态的业界标杆

- **定位**：LLM 工程平台（trace + 评测 + prompt 管理），自托管单镜像起步。
- **报告与呈现**（[docs](https://langfuse.com/docs)）：Traces 列表（过滤查询条 + **Pulse 异常条**：count/cost/延迟
  尖峰可拖选过滤）、trace 详情观测树 + **Agent Graphs DAG 视图**、Sessions 会话回放、
  **Score Analytics**（分数分布 + 随时间趋势 + **双分数一致性统计：Cohen's κ、混淆矩阵、F1、Pearson/MAE**——
  正是我们 T6 judge 标定做的事，业界先例确认了方向）、Experiments 对比视图（多 run 选基线、逐用例
  输出/分数/成本/延迟并排、阈值标记、失败用例旁挂 trace 预览）、Annotation Queues 人工标注队列、
  自定义仪表盘、告警；CSV/JSON 导出 + 定时 S3/GCS 导出 + Metrics API。
- **评测与门禁**：Score 对象四类型（numeric/categorical/boolean/text）+ comment 携带 judge 理由，可挂
  trace/span/session/experiment-item；**无 repetitions**（[issue #5855](https://github.com/langfuse/langfuse/issues/5855)）；
  LLM judge 服务端规则触发（模板变量映射 + 抽样率 + 执行全程留 debug trace）；代码评测器跑在沙箱
  （仅标准库、无网络、2s 限）；**CI 门禁**：[experiment-action](https://langfuse.com/docs/evaluation/experiments/experiments-ci-cd)
  GitHub Action，抛 `RegressionError` 即失败；两种发布策略——"必备用例全过"与"已批准基线中通过的用例
  不得回归"，**基线工件 `approved-baseline.json` 固定 dataset+评测器版本，新 run 成功不自动成为基线**，
  评测结果缺失/无效必须 fail gate，PR 评论带逐用例表。
- **接入**：OTLP **仅 HTTP**（protobuf/JSON，无 gRPC）；`gen_ai.*` 语义约定直接渲染为 generation；
  一方 SDK 仅 Python/JS，Java 走 OTel javaagent/OpenLLMetry。
- **评测集**：仅用户自建；有公开 demo 项目但非基准集。
- **值得借鉴**：Score 四类型+comment 模型；**approved-baseline 门禁工件（版本固定、双发布策略、缺结果即失败）**；
  RunEvaluator（run 级聚合评测器契约——pass^k 的自然挂点）；Score Analytics 的 κ/混淆矩阵；评测执行留痕。

### 1.2 Arize Phoenix（Elastic License 2.0，Python，约 11.8k★）——repetitions 与评测即测试

- **定位**：OTel 原生 AI 可观测 + 评测，OpenInference 语义约定的参考实现。
- **报告与呈现**（[docs](https://arize.com/docs/phoenix)）：Projects→Traces（Python 风格过滤表达式 + 延迟/token/
  成本/错误率指标图，拖拽缩放）、span 瀑布详情、Sessions、标注 UI（trace/span/文档级，带解释）、
  Datasets 的 **Experiments/Metrics 页签**——**Metrics 页签跨实验画延迟/错误率/成本/token 时间序列**；
  实验详情表按评测器分数排序/过滤，**repetition 轮播**（逐次分数 + 均值）；**基线实验持久徽标**；
  评测器执行本身 OTel 留痕到专属项目；Embeddings 聚类视图；Playground + Span Replay。
- **评测与门禁**：评测输出 `{label, score, explanation, metadata}`，judge 用 tool-calling 结构化输出；
  **优化方向元数据（maximize/minimize/none）**让对比视图免启发式判断好坏；**repetitions=N 原生支持**
  （每次 N 次任务+评测，专为测非确定性；[docs](https://arize.com/docs/phoenix/datasets-and-experiments/how-to-experiments/repetitions)）；
  数据集评测器挂一次即在每个实验自动跑（"单元测试套件"语义）；pytest 插件哲学：
  **"硬断言做门禁（CI exit code），质量分数做趋势"**；**pass^k 未内建**（repetitions 之上可用户自定义）。
- **接入**：OTLP **HTTP :6006 + gRPC :4317 双栈**；自动转换 OTel GenAI 语义 → OpenInference。
- **评测集**：不自带；cookbook 用公共数据演示。
- **值得借鉴**：repetitions 原生化 + 逐次轮播；优化方向元数据；数据集级评测器自动运行；**"硬断言门禁/软分数趋势"
  的评测即测试模型**（可映射为 JUnit 5 扩展：测试类→数据集、@ParameterizedTest→用例、断言→pass 标注）。

### 1.3 promptfoo（MIT，TypeScript）——断言代数与 CI 门禁契约

- **报告与呈现**（[web UI docs](https://www.promptfoo.dev/docs/usage/web-ui/)）：`promptfoo view` 本地 Web 查看器——
  评测历史切换、显示模式（All/Failures/Passes/Errors/**Different**/Highlights）、**评测间 Compare diff**（绿增红删）、
  通过率/分数直方图/prompt 两两散点、单元格展示 tokens/延迟/成本/tokens每秒、**人工覆盖 pass/fail/分数/评论
  可持久化并导出为训练数据**；`-o` 导出 json/csv/html/**junit.xml**。
- **断言代数**（[deterministic](https://www.promptfoo.dev/docs/configuration/expected-outputs/deterministic/) /
  [model-graded](https://www.promptfoo.dev/docs/configuration/expected-outputs/model-graded/)）：约 40 种确定性断言
  （contains/regex/levenshtein/rouge/is-json(schema)/latency/cost/tool-call-f1/**trajectory:tool-sequence、
  trajectory:goal-success、trace-span-count/duration/error**）+ 约 15 种模型评分断言（llm-rubric/g-eval/factuality/
  RAG 三件套…）；**全部可用 `not-` 前缀反转**；单条断言带 weight/threshold/metric 分组；`assert-set` 组内 k-of-n 阈值；
  grader 覆盖链（全局→套件→用例→断言）。
- **门禁**：`eval` 退出码 **100=质量回归**（有失败或通过率低于 `PROMPTFOO_PASS_RATE_THRESHOLD`）、1=工具错误；
  `--filter-failing <evalId>` 只重跑上次失败；官方 GitHub Action 在 PR 上跑 before/after 评测并贴对比评论。
- **评测集**：不自带；CSV/XLSX/Google Sheets 列头即变量 + `__expected` 断言字符串列（**电子表格优先**的用例创作），
  `generate dataset` 合成。
- **值得借鉴**：断言代数全套；退出码语义分离；junit.xml；`__expected` 电子表格工作流；PR 对比评论。

### 1.4 DeepEval（Apache-2.0，Python）——指标目录与语料合成

- **报告与呈现**（[docs](https://deepeval.com/docs/metrics-introduction)）：pytest 终端逐用例分数+理由；
  本地 JSON/SQLite 运行历史 + `deepeval inspect` TUI；云端（Confident AI）才有跨 commit 对比 Web 报告。
- **评测**：50+ 指标（G-Eval、DAG 分解判定、轨迹类 TaskCompletion/PlanAdherence、**Tool/Argument Correctness**、
  RAG、多轮对话、安全类），统一 0-1 分 + 理由 + 阈值（默认 0.5）。
- **门禁**：pytest 退出码；`deepeval gate`（治理策略门禁，平台功能）。
- **评测集**：Golden/EvaluationDataset（代码/CSV/云端别名拉取）+ **Synthesizer 从文档合成用例再人工筛选**；
  自带 7 个经典基准适配（MMLU/GSM8K/HumanEval 等）。
- **值得借鉴**：**语料合成→人工固化工作流**（直接服务 mock 语料补强）；轨迹/工具正确性指标命名与语义；
  本地 SQLite 运行历史（我们同样是 SQLite，验证了路线）。

### 1.5 TruLens（MIT，Python）——反馈函数与趋势面板

- **报告与呈现**（[docs](https://www.trulens.org)）：Streamlit 仪表盘——**版本排行榜**（各版本分数/延迟/成本对比）、
  **Trends 时间序列**（评测指标/延迟/成本），逐 record 反馈分数 + 完整 trace 下钻。
- **评测**：feedback 函数 + **Selector API**（把指标输入绑定到 trace 的特定 span/属性——部位级评测）；
  RAG 三元组带公开判卷准确率数字；2.x 新增 Agent 评测器（ToolSelection/Calling/Quality、PlanAdherence…）。
- **门禁**：无内建 CI 门禁（定位为监控对比）。
- **值得借鉴**：部位级反馈绑定；版本排行榜 UI 形态。

### 1.6 Inspect AI（Apache-2.0，英国 AI 安全研究院）——打分/归约架构最干净

- **报告与呈现**（[docs](https://inspect.aisi.org.uk)）：`inspect view` 本地日志查看器（运行中评测实时刷新、
  完整消息/工具调用转录、按分数过滤、跨 epoch 对同样本排序）；**`inspect view bundle` 打包成纯静态站点**
  可部署 GitHub Pages/PR 工件；VS Code 扩展；EvalLog API + `evals_df()` 编程访问。
- **评测**：scorer 契约 **{value, answer, explanation, reason}**；**epochs reducers：mean/median/mode/majority、
  `pass_at_{k}`、`pass_k_{k}`（即 pass^k）、`at_least_{k}`**（[metrics](https://inspect.aisi.org.uk/metrics.html)）——
  **业界唯一内建 pass^k 的评测框架（作为打分归约器，非发版门禁）**；`krippendorff_alpha` 多 judge 一致性；
  **离线 `inspect score` 换 judge 重打分而无需重跑 agent**。
- **数据集**：CSV/JSON/HuggingFace/S3 加载器 + `inspect_evals` 附加包适配约 107 个公共基准（含 GAIA、
  Cybench、SWE-bench Verified、MLE-bench）；Sample 支持沙箱/文件/setup 脚本。
- **门禁**：核心无内建（消费方基于日志自建）。
- **值得借鉴**：**原始 k 次分数入库 + 事后归约**（换 judge/换指标不重跑）；scorer 四元组契约喂诊断引擎；
  静态 bundle 导出；Krippendorff α 补充 κ。

## 2. 问题②：业界如何产出报告、有没有专门网页

**结论：全部有专门 Web 界面，分三种形态**：

1. **本地 viewer 命令**（轻量项目）：`promptfoo view`（localhost Web）、`inspect view`（含运行中实时视图）、
   `deepeval inspect`（TUI）、TruLens `run_dashboard()`（Streamlit）、Phoenix `phoenix serve`。
2. **自托管 Web 应用**（平台级）：Langfuse、Phoenix 均为完整 Web 产品（trace/评测/实验/仪表盘/标注）。
3. **静态导出**：`inspect view bundle` 生成可部署纯静态站点——CI 里把整份报告挂成 PR 工件的最佳实践。

**共同展示元素清单**（按出现频率）：
- 逐用例矩阵 + Failure/Different 过滤模式（promptfoo/Inspect/Langfuse）
- **judge 理由列**——四家全部强制展示 explanation（最被一致抄袭的元素）
- 评测间/实验间 diff（基线选择、逐用例 pass↔fail 高亮）（promptfoo/Langfuse/Phoenix）
- 分数分布直方图 + 时间序列趋势（promptfoo/Langfuse/TruLens/Phoenix）
- 版本排行榜（分数/延迟/成本）（TruLens/Langfuse）
- 失败用例→trace 一键下钻（全部）
- 导出：CSV/JSON 普及；junit.xml（promptfoo）；静态站点（Inspect）；OTLP（平台级）

**AgentLens 现状对照**：

| 能力 | 业界 | AgentLens |
|---|---|---|
| 本地 Web viewer | ✅ 普及 | ✅ `serve`（列表/趋势/详情/对比） |
| trace 瀑布/甘特 | ✅ 两家平台 | ⚠️ 事件平铺列表 |
| 逐用例 diff | ✅ 三家 | ❌ 仅汇总对比 —— **P0 补** |
| judge 理由列 | ✅ 全部 | ⚠️ 详情页有，列表无 |
| 分数分布/时间序列 | ✅ | ⚠️ 仅 duration 条形 |
| 静态导出 | ✅ Inspect | ⚠️ 静态 HTML 报告（report 命令） |
| **发版门禁 pass^k** | ❌ 无平台内建 | ✅ **独有** |
| **诊断证据链** | ❌ | ✅ **独有** |

## 3. 问题③：评测集盘点与 mock 语料补强

### 3.1 平台自带评测集：基本没有

Langfuse/Phoenix/promptfoo **均不自带评测集**（用户自建 dataset items；promptfoo 靠电子表格/HF 拉取）。
唯一"自带海量评测集"的是 **Inspect AI 的 `inspect_evals`**（约 107 个公共基准统一适配，数据自动下载缓存）——
这是"评测集适配层"的业界最佳形态。DeepEval 自带 7 个经典 LLM 基准适配。

### 3.2 Agent/工具调用公共基准

| 基准 | 内容 | 规模(约) | 许可 | 导入难度 |
|---|---|---|---|---|
| [τ-bench](https://github.com/sierra-research/tau-bench)（τ²-bench 扩展） | 客服 Agent 多轮+工具+**政策约束**；**pass^k 发源地**（φ^action/φ^response 双校验） | retail ~115 / airline ~50 / telecom(τ²) 新增 | Apache-2.0 | 中：任务可直转 Scenario；用户模拟器需单轮化 |
| [BFCL](https://github.com/ShishirPatil/gorilla) | 函数调用正确性（单/多/并行，AST 或真执行校验） | 数千条（v3） | 数据 CC-BY-4.0 / 代码 Apache-2.0 | **低：期望调用序列直贴我们的工具正确性断言** |
| [GAIA](https://huggingface.co/datasets/gaia-benchmark/GAIA) | 通用助手多步推理（网页/文件/多模态） | 466 题 | Apache-2.0 | 中：无工具环境定义，契合度一般 |
| [AgentBench](https://github.com/THUDM/AgentBench) | 8 环境综合 Agent 能力 | 数百任务 | Apache-2.0 | 高：环境绑定深 |
| [SWE-bench](https://github.com/princeton-nlp/SWE-bench) | issue→补丁→测试验证 | 2294（Verified 500） | MIT | 高：需代码执行沙箱 |

### 3.3 K8s 运维方向（我们的主场景）

**AIOpsLab 是唯一成体系的开源选择**（97 题 = 31 故障 × 4 任务类型，MIT，**已接入**）；
其余 SRE/事件诊断数据集碎片化、无标准格式。τ-bench 的"政策红线硬校验"（φ^action）思想值得吸收进
ComplianceEvaluator 用例设计。

### 3.4 Golden set 标定实践（业界共识）

- 每类 20-50 条边界样本、总体 100-300 条起步；**必须含"看起来对但应判 FAIL"的对抗样本**，否则 judge 只学会说 PASS；
- DeepEval Synthesizer / Ragas TestsetGenerator 的"**LLM 合成→人工筛选→固化版本**"两步工作流是标准做法；
- 语料必须版本化并进结果记录（换 judge/换 prompt 的 κ 才可比）。

### 3.5 mock 语料补强方案（接 judge 标定遗留项）

| 缺口 | 方案 | 来源 |
|---|---|---|
| correct 答案是零信息罐头 | `correct_detailed` 行为：模板嵌入真实工具名/数据点/根因/建议（确定性合成，零 LLM 成本） | — |
| 缺边界用例 | 3 个新 mock 行为：`wrong_conclusion`（证据真结论错）/ `fabricated_evidence`（结论对证据假）/ `partial_evidence`（证据不全） | 业界"对抗样本"共识 |
| 语料硬编码不可版本化 | `data/judge-calibration-corpus.json`（instruction/symptoms/gold answer/期望判定），`judge-calibrate --corpus` 加载 | DeepEval golden 格式 |
| 任务多样性不足 | `bfcl-import`：BFCL 期望调用序列 → Scenario（工具集+期望调用直贴确定性评测器） | BFCL |
| 政策约束场景缺失 | 从 τ-bench 抽 10-20 条政策场景 → ComplianceEvaluator 用例 | τ-bench φ^action |
| 规模不足 | 合成工作流：LLM 从 AIOpsLab 任务描述批量生成候选 → 人工筛选固化 | DeepEval Synthesizer |

## 4. 来源（主要）

Langfuse [docs](https://langfuse.com/docs)/[self-host pricing](https://langfuse.com/pricing-self-host)/[OTel](https://langfuse.com/resources/engineering/opentelemetry-languages)/[Score Analytics](https://langfuse.com/docs/evaluation/scores/score-analytics)/[CI-CD](https://langfuse.com/docs/evaluation/experiments/experiments-ci-cd)；
Phoenix [docs](https://arize.com/docs/phoenix)/[license](https://arize.com/docs/phoenix/self-hosting/license)/[repetitions](https://arize.com/docs/phoenix/datasets-and-experiments/how-to-experiments/repetitions)/[pytest](https://arize.com/docs/phoenix/evaluation/integrations/pytest)；
promptfoo [web UI](https://www.promptfoo.dev/docs/usage/web-ui/)/[CLI](https://www.promptfoo.dev/docs/usage/command-line/)/[deterministic](https://www.promptfoo.dev/docs/configuration/expected-outputs/deterministic/)/[GH Action](https://www.promptfoo.dev/docs/integrations/github-action/)；
DeepEval [metrics](https://deepeval.com/docs/metrics-introduction)/[CI-CD](https://deepeval.com/docs/evaluation-unit-testing-in-ci-cd)；
TruLens [dashboard](https://www.trulens.org/getting_started/dashboard/)/[RAG triad](https://www.trulens.org/getting_started/core_concepts/rag_triad/)；
Inspect AI [log viewer](https://inspect.aisi.org.uk/log-viewer.html)/[metrics](https://inspect.aisi.org.uk/metrics.html)/[datasets](https://inspect.aisi.org.uk/datasets.html)/[inspect_evals](https://github.com/UKGovernmentBEIS/inspect_evals)；
τ-bench / BFCL(Gorilla) / GAIA / AgentBench / SWE-bench / AIOpsLab（各仓库，见正文链接）。

*细节若与各项目最新版本不符，以官方文档为准。*
