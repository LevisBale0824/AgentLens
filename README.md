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
mvn clean verify        # 构建并运行全部测试（42 个）
```

CLI 命令：

```bash
# 运行 20 场景数据集，pass^k 聚合 + 稳定性门禁
AgentLensApp run [--scenarios PATH] [--repeats K] [--allowed T1,T2] [--forbidden T1,T2]

# baseline/candidate 发版对比（核心演示）
AgentLensApp compare --baseline correct --candidate flaky [--scenarios PATH] [--repeats K]

# 按 run ID 还原完整评测（SQLite 持久化：trace 时间线 + 评测结果）
AgentLensApp show --run RUN_ID
AgentLensApp show --run list          # 列出全部 run ID

# 自包含 HTML 报告（run 表 / pass^k 分层 / trace 时间线 / 评测结果）
AgentLensApp report [--db PATH] [--out PATH]

# 冒烟测试
AgentLensApp demo
```

Docker 一键演示（V1 验收项）：

```bash
docker compose up --build
# 容器内自动执行：20 场景 run (pass^k, 3 repeats) → 好版本放行 → flaky 版本
# 被门禁拦截 (pass^k 1.000 → 0.250) → 生成自包含 HTML 报告
# 产物落在宿主机 output/: report.html + runs.db + runs.jsonl
```

## 模块

```text
agentlens-core       领域模型和核心接口（Task、Event、Run、Trace 校验）
agentlens-adapters   HTTP、CLI、Mock 等 Agent 适配器
agentlens-eval       确定性评测器、pass^k 聚合、诊断引擎、回归门禁
agentlens-cli        命令行入口（run / compare / demo）
```

## 评测体系

**确定性评测器**（先于 LLM Judge，结果完全可复现）：

- 任务完成度（状态 + 最终答案）
- 工具策略（允许/禁止/未知工具，场景级策略）
- 运行限制（步数上限、循环检测）

**pass^k 聚合**（[ROADMAP 7.1](docs/ROADMAP.md)）：

- pass^k：k 次全部通过的比例（稳定性下限）
- pass@k / 平均通过率（能力上限 / 期望）
- 分层失败视图：稳定通过 / 间歇失败 / 稳定失败

**诊断引擎**（[P1-4](docs/ROADMAP.md)）——直接分析 Trace 的规则引擎，7 条规则 × 5 个层级（safety / tool_selection / execution / final_answer 等），每条诊断携带 `trace[i]` 证据链引用，可与 SQLite 持久化的 run 逐条对照审计。

**Mock Agent 行为库**——5 种可预期失败模式验证平台能准确发现已知问题：正确诊断、错误工具选择、越权操作、循环调用、间歇失败（flaky）。

**存储**——JSONL 摘要 + SQLite 全量（runs / events / evaluations 三表），按 run ID 完整还原任何一次评测。

## 设计原则

1. 被测 Agent 与评测器解耦。
2. 先确定性检查，再使用 LLM Judge。
3. 所有评测都保留证据和原始 Trace。
4. 危险操作和越权行为使用硬失败规则。
5. 所有结果能够复现并参与 CI 回归门禁。

CI：每次 push/PR 自动跑全部测试 + CLI run/compare 端到端验证（含门禁拒绝坏版本的断言）。

详细路线见 [docs/ROADMAP.md](docs/ROADMAP.md)。
