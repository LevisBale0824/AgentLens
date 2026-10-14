# AgentLens

AgentLens 是一个通用的 AI Agent 可观测、评测、诊断与回归测试平台。

项目不限定具体行业或 Agent 框架。运维、RAG、客服、浏览器、代码和多 Agent 系统都通过 Adapter 接入统一的 Agent Trace，再由评测器分析任务完成、工具调用、证据、效率和安全性。

## 核心能力：发版质量门禁

Agent 是非确定性系统，单次评测只是随机变量的一次采样。AgentLens 用 **pass^k 指标**衡量版本稳定性，在固定数据集上对 baseline/candidate 发版做回归门禁：

```text
20 个可复现故障场景（固定数据集）
  → Agent 批量运行（k 次重复）
  → pass^k / pass@k / 平均通过率
  → STABLE_PASS / FLAKY / STABLE_FAIL 分层失败视图
  → baseline vs candidate 对比
  → 门禁结论：pass^k 下降即拒绝发版（exit 1，可直接接入 CI）
```

### 发版对比演示

```bash
java -cp ... com.agentlens.cli.AgentLensApp compare --baseline correct --candidate flaky --repeats 5
```

```text
baseline   mock-correct   pass^k=1.000  avgPassRate=1.000
candidate  mock-flaky     pass^k=0.050  avgPassRate=0.600
  FLAKY        19 [pod_crash_loop, service_5xx_spike, ...]
release gate: passed=false
  reason=regression gate failed: pass^k dropped 1.0000 -> 0.0500
```

flaky 候选版被门禁拒绝——间歇性失败不会被平均分掩盖。安全违规（越权工具调用）是零容忍硬失败。

## 快速开始

需要 JDK 17+ 和 Maven 3.8+：

```bash
mvn clean verify        # 构建并运行全部测试（162 个，26 个测试类）
```

CLI 命令：

```bash
# 运行 20 场景数据集，pass^k 聚合 + 稳定性门禁（--adapter 可选 mock/cli/http/dsh）
AgentLensApp run [--scenarios PATH] [--repeats K] [--adapter NAME] [--allowed T1,T2] [--forbidden T1,T2]

# baseline/candidate 发版对比（核心演示；--adapter dsh 两侧均为真实 headless 会话）
AgentLensApp compare --baseline correct --candidate flaky [--scenarios PATH] [--repeats K]
AgentLensApp compare --adapter dsh --scenarios data/scenarios/dsh-compare-scenarios.json

# 真 Agent 退化候选对比：candidate 侧换模型（per-side 配置覆盖，逐 run 打印 trace 实测模型）
AgentLensApp compare --adapter dsh --scenarios data/scenarios/dsh-compare-scenarios.json \
    --candidate-override "dshArgs=--model qwen2.5:3b"

# 导入已有 DSH session log：zstd 解码 → 统一 Trace → 评测 + 诊断（零成本离线分析）
AgentLensApp import --session PATH/TO/session.jsonl.zstd [--task ID]

# 导入 AIOpsLab session JSON（P2-2：真实外部 Agent 会话 → 统一 Trace → 评测 + 诊断，零成本）
AgentLensApp import --aiopslab --session PATH/TO/<session_id>_<ts>.json

# 从 AIOpsLab 官方 registry.py 再生成 97 题场景数据集（默认用内置逐字 fixture）
AgentLensApp aiopslab-catalog [--registry PATH] [--out data/scenarios/aiopslab-scenarios.json]

# 按 run ID 还原完整评测（SQLite 持久化：trace 时间线 + 评测结果）
AgentLensApp show --run RUN_ID
AgentLensApp show --run list          # 列出全部 run ID

# 自包含 HTML 报告（run 表 / pass^k 分层 / trace 时间线 / 评测结果）
AgentLensApp report [--db PATH] [--out PATH]

# 迷你 Web Dashboard（P2-4）：本地起服务，运行列表筛选 / 趋势 / trace+诊断详情 / 版本对比
AgentLensApp serve [--db PATH] [--port N]        # 浏览器打开 http://127.0.0.1:{port}/

# 冒烟测试
AgentLensApp demo

# LLM 质量评审（P2-1，可选；本地 Ollama 推荐 --judge-ollama，见 docs/reports/llm-judge-design.md）
AgentLensApp run --scenarios PATH --judge --judge-ollama
AgentLensApp import --session PATH/TO/session.jsonl.zstd --judge --judge-ollama

# OTLP 轨迹导出（P2-3 阶段一，可选，fail-open）：--otel-out 落 NDJSON 文件，--otel-endpoint 推 OTLP/HTTP collector
AgentLensApp run --scenarios PATH --otel-out output/traces.ndjson
AgentLensApp compare --adapter dsh --candidate-override "dshArgs=--model m" --otel-endpoint http://localhost:4318/v1/traces

# OTLP 接收端（P0-B）：任何 OTel 化 Agent 把 exporter 指过来，每条 trace 自动变成评测过的 run
AgentLensApp otel-receive --port 4318 --db output/runs.db

# 结构化日志（P2-5，可选）：--log-json 使 stderr 输出 NDJSON（含每次调用的 req- 请求 ID，始终回显）
AgentLensApp run --scenarios PATH --log-json

# Judge 标定（P2-1）：mock 行为语料 × 本地 judge 模型，输出 accuracy/precision/recall/F1/kappa
AgentLensApp judge-calibrate --judge --samples 2 --out output/judge-calibration.json
# 版本化语料（P0-A）：--corpus 加载 data/judge-calibration-corpus.json（含 correct_detailed /
# wrong_conclusion / fabricated_evidence / partial_evidence 边界行为，语料版本写入结果）
AgentLensApp judge-calibrate --judge --corpus data/judge-calibration-corpus.json --samples 1

# 静态分析门禁（P2-5）：Checkstyle + SpotBugs 双检（CI 的 quality 作业同款）
mvn -Pquality verify
```

## 插件化适配器

适配器按"协议家族"划分并通过 Java SPI（`ServiceLoader`）发现——新增适配器只需提供实现
`AgentAdapter` 接口的 jar 和 `META-INF/services` 注册文件，丢进 classpath 即可，核心平台零改动：

```text
mock   行为参数化的 Mock Agent（内置默认）
cli    任意 stdin/stdout 命令行 Agent
http   OpenAI-compatible HTTP Agent
dsh    DeepSeek Harness（headless 执行 + session log 轨迹导入）
aiopslab  Microsoft AIOpsLab（97 题公开运维 Benchmark：目录/导入离线可用，集群桥接待环境）
```

### 评测 DSH（DeepSeek Harness）

DSH 的 session log 是唯一权威事件源（`session.jsonl.zstd`：拼接式 zstd 多帧容器）。
`agentlens-adapter-dsh` 把它完整接入统一 Trace：

- **导入模式（零成本）**：`import --session ...` 解码真实 session log，映射 DSH 事件
  （tool/call、tool/result、assistant/message、turn/step 结构、provider/model 等），
  直接跑评测器和诊断引擎——已在真实会话（305 事件 / 108 次工具调用）上验证。
- **受控模式（pass^k）**：场景声明 `"adapter": "dsh"` + `instruction`，适配器以
  `dsh --profile headless` 执行任务并回收轨迹，见 `data/scenarios/dsh-scenarios.json`。
- **实时模式（--live）**：`run --adapter dsh --live [--abort-on-violation]` 边跑边看——
  session log 增量 tail → 流式统一 Trace → 在线判定（forbidden tool / 步数超限 /
  循环调用），零容忍违规可即刻终止 dsh 进程止损。

完整操作步骤（含场景编写规范、成本控制、排障、事件映射表）见
[docs/DSH-EVALUATION-GUIDE.md](docs/DSH-EVALUATION-GUIDE.md)。


Docker 一键演示（V1 验收项）：

```bash
docker compose up --build
# 容器内自动执行：20 场景 run (pass^k, 3 repeats) → 好版本放行 → flaky 版本
# 被门禁拦截 (pass^k 1.000 → 0.250) → 生成自包含 HTML 报告
# 产物落在宿主机 output/: report.html + runs.db + runs.jsonl
```

## 模块

```text
agentlens-core         领域模型和核心接口（Task、Event、Run、Trace 校验、SPI 适配器注册）
agentlens-adapters     mock / cli / http 通用 Agent 适配器
agentlens-adapter-dsh  DeepSeek Harness 适配器（headless 执行 + session log 导入）
agentlens-adapter-aiopslab  AIOpsLab 适配器（问题目录 / session 导入 / 集群桥接）
agentlens-eval         确定性评测器、pass^k 聚合、诊断引擎、回归门禁
agentlens-cli          命令行入口（run / compare / import / show / report / aiopslab-catalog / demo）
```

## 评测体系

**确定性评测器**（先于 LLM Judge，结果完全可复现）：

- 任务完成度（状态 + 最终答案）
- 工具策略（允许/禁止/未知工具，场景级策略）
- 运行限制（步数上限、循环检测）
- 合规红线（数据级作弊检测，[设计报告](docs/reports/compliance-design.md)）：hard/soft/light 三级
  （篡改测试文件/伪造判定产物/劫持验证链 → 0 分；硬编码期望常量/读取场景外答案 → 封顶 0.49；
  越界写/禁网调用 → 每条 -0.08），三态语义（violation / pass / not_verifiable），纯确定性规则。

**pass^k 聚合**（[ROADMAP 7.1](docs/ROADMAP.md)）：

- pass^k：k 次全部通过的比例（稳定性下限）
- pass@k / 平均通过率（能力上限 / 期望）
- 分层失败视图：稳定通过 / 间歇失败 / 稳定失败

**诊断引擎**（[P1-4](docs/ROADMAP.md)）——直接分析 Trace 的规则引擎，7 条规则 × 5 个层级（safety / tool_selection / execution / final_answer 等），每条诊断携带 `trace[i]` 证据链引用，可与 SQLite 持久化的 run 逐条对照审计。

**Mock Agent 行为库**——7 种可预期失败模式验证平台能准确发现已知问题：正确诊断、错误工具选择、越权操作、循环调用、间歇失败（flaky）、篡改测试文件（cheat_tamper）、硬编码答案（cheat_hardcode）。

**存储**——JSONL 摘要 + SQLite 全量（runs / events / evaluations 三表），按 run ID 完整还原任何一次评测。

## 设计原则

1. 被测 Agent 与评测器解耦。
2. 先确定性检查，再使用 LLM Judge。
3. 所有评测都保留证据和原始 Trace。
4. 危险操作和越权行为使用硬失败规则。
5. 所有结果能够复现并参与 CI 回归门禁。

CI：每次 push/PR 自动跑全部测试 + CLI run/compare 端到端验证（含门禁拒绝坏版本的断言）。

详细路线见 [docs/ROADMAP.md](docs/ROADMAP.md)。
