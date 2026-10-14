# 原始调研记录 ③：公共 Agent 评测数据集全景

> 本文为 2026-10-13 同类项目调研的**原始记录**（AI 辅助在线调研），
> 是 [peer-analysis.md](../reports/peer-analysis.md) 的来源材料之一。未经本地部署验证；
> 规模数字为公开资料约数，许可以各仓库当前状态为准。

## 1. Agent/工具调用类基准

### τ-bench (Sierra)
- **任务风格**：客服 Agent 在数据库/工具环境（航空、零售、电信域）中处理用户请求：多轮对话 + 工具调用 + 遵守政策约束。
- **格式**：JSON Lines。任务含 `id`、`user_scenario`（含 instructions 与隐藏的数据库初始状态）、工具 API schema 集、`evaluation_criteria`（action 检查 + response 检查：φ^action 与 φ^response）。
- **规模**：retail 域 ~115 任务、airline 域 ~50 任务（τ-bench 与 τ²-bench 合计约 300+，telecom 域为 τ²-bench 新增）。
- **许可**：Apache-2.0（github.com/sierra-research/tau-bench）。
- **评测方式**：**pass^k**（同一任务 k 次全过的比例）+ pass@k，动作序列与最终回复双维度校验——AgentLens 的 pass^k 门禁与其同源。
- **导入难度**：低-中。任务主体（instruction + 工具集 + 判定标准）可直转 AgentLens Scenario；难点是用户模拟器（LLM 扮演用户多轮）——单轮化后即可导入，或保留多轮交由 DSH 真会话承载。

### GAIA (Meta/HuggingFace)
- **任务风格**：通用 AI 助手多步推理（网页、文件、多模态），分 Level 1/2/3 难度。
- **格式**：HuggingFace 数据集（JSON），字段：`task_id, Question, Level, Final answer, Annotator metadata`。
- **规模**：466 题（validation 165 + test 300，test 答案不公开防污染）。
- **许可**：Apache-2.0（huggingface.co/datasets/gaia-benchmark/GAIA）。
- **导入难度**：中。单轮、字段清晰，易转 Scenario；但无工具环境定义（靠 Agent 自带浏览/文件工具），与"工具调用 trace 评测"契合度一般。

### BFCL (Berkeley Function-Calling Leaderboard)
- **任务风格**：函数调用正确性（单次/多次/并行调用、可执行类）。
- **格式**：JSON，问题 + 可用函数 schema + 期望调用序列（AST 匹配或真实执行校验）。
- **规模**：数千条（v3，含多轮/可执行类别）。
- **许可**：数据 CC-BY-4.0，评测代码 Apache-2.0（github.com/ShishirPatil/gorilla，berkeley-function-call-leaderboard 子目录）。
- **导入难度**：低。期望调用序列可直接映射到工具策略/工具正确性断言——**最贴合确定性评测器**的公开集。

### AgentBench
- **任务风格**：8 个环境（操作系统、DB、知识图谱、网页购物等）综合 Agent 能力评测。
- **格式**：各环境独立 JSON + 环境容器（docker-compose 提供）。
- **规模**：数百任务跨 8 环境。
- **许可**：Apache-2.0（github.com/THUDM/AgentBench）。
- **导入难度**：高（环境绑定深）。参考价值在多环境组织方式与成功率统计口径。

### SWE-bench (Verified)
- **任务风格**：真实 GitHub issue → 生成补丁 → 跑测试验证。
- **格式**：JSONL（repo/issue/test patch/测试用例）。
- **规模**：2294（Verified 子集 500 由人工筛选）。
- **许可**：MIT（github.com/princeton-nlp/SWE-bench）。
- **导入难度**：高（需代码执行沙箱）；与运维诊断场景不直接契合。

## 2. Ops/SRE 运维诊断类（与本项目场景最契合）

- **AIOps Challenge / 清华 AIOps 数据集**（assets.awesome-aiops.dev 有汇总）：日志/指标/trace 三类数据 + 告警根因标注，偏"检测/定位"而非"Agent 修复"。
- **SRE Agent 基准**：生态尚碎片化、无统一标准（unverified）。
- **K8s 故障注入即评测**：业界（含 AIOpsLab）通行做法是 ChaosMesh/Litmus 注入故障 + 人工或 LLM 判卷，**无标准化公开评测集**——AIOpsLab 97 题（31 故障 × 4 任务类型，MIT，github.com/microsoft/AIOpsLab）是目前唯一成体系的 K8s 运维 Agent 评测集，**本项目已接入**（aiopslab adapter）。
- **结论**：AIOpsLab 唯一成体系（已接入✓）；τ-bench 的"政策约束 + φ^action 硬校验"模式是第二值得借鉴的。

## 3. Golden set / LLM-as-judge 标定实践

### DeepEval golden dataset
- `Golden` 对象：`input / expected_output / context[] / additional_metadata`；本地 JSON 或云端；**Synthesizer 可从文档自动生成用例再人工筛选**（合成→curate 两步工作流）。
- 官方建议：每指标 20-50 条边界样本起步，覆盖 pass 与 fail 两类。

### Ragas
- `TestsetGenerator`：从文档自动合成（evolution 式：simple→multi-hop→reasoning）问题+ground truth，人工修正后固化。
- 强调分布覆盖：简单/多跳/对抗各占一定比例。

### promptfoo
- tests YAML 内联或外部引用；社区实践是"少量手工精心设计的边界用例 + 大量自动合成用例"组合。

### 通用共识（多来源交叉）
- 标定集须含**边界与对抗样本**（判定应 FAIL 的"看起来对"的样本），否则 judge 只学会说 PASS；
- 每类 20-50 条、总体 100-300 条是常见起步规模；需版本化（语料版本进结果记录）。

## 4. 对 AgentLens mock 语料的具体补强映射

| 语料缺口 | 来源方案 | 落地形式 |
|---|---|---|
| correct 答案太薄 | AIOpsLab 任务模板 + 工具调用结果回填答案模板（确定性合成） | `correct_detailed` 行为：答案嵌入真实工具名/数据点/根因/建议 |
| 缺边界用例 | 自建三类：结论错（证据真）/证据假（结论对）/证据不全 | 3 个新 mock 行为（确定性，无需 LLM） |
| 缺真实任务多样性 | **BFCL 期望调用序列** → 直接转 Scenario（工具集+期望调用） | `bfcl-import` 转换器（低难度，最贴确定性评测器） |
| 缺政策约束场景 | **τ-bench 政策文本 + φ^action 硬校验**思想 → ComplianceEvaluator 用例 | 从 τ-bench 抽 10-20 条政策场景转 Scenario（中难度） |
| 语料规模与版本化 | DeepEval/Ragas 合成→人工筛选工作流 | `judge-calibrate --corpus data/corpus.json`（版本化 JSON，替代硬编码 6 行为） |

## 来源
- tau-bench: github.com/sierra-research/tau-bench (Apache-2.0)
- GAIA: huggingface.co/datasets/gaia-benchmark/GAIA (Apache-2.0, test 答案不公开)
- BFCL: github.com/ShishirPatil/gorilla (berkeley-function-call-leaderboard; 数据 CC-BY-4.0)
- AgentBench: github.com/THUDM/AgentBench (Apache-2.0)
- SWE-bench: github.com/princeton-nlp/SWE-bench (MIT)
- AIOpsLab: github.com/microsoft/AIOpsLab (MIT, 已接入)
- DeepEval/Ragas/promptfoo 合成工作流：各自官方 docs
