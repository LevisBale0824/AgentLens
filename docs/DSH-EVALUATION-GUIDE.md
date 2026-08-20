# DSH（DeepSeek Harness）评测指南

本文档说明如何用 AgentLens 评测 DSH。三种模式按需选用：

| 模式 | 命令 | 成本 | 用途 |
|---|---|---|---|
| 导入模式 | `import --session` | 零（不调 LLM） | 对已有会话做离线轨迹分析、评测、诊断 |
| 受控模式 | `run --adapter dsh` | 每任务一次完整 DSH 会话 | pass^k 稳定性、工具策略、回归门禁 |
| 实时模式 | `run --adapter dsh --live` | 同受控（违规可提前中止省钱） | 流式 Trace 展示 + 在线判定 + 违规即杀 |

## 前置条件

- JDK 17+、Maven 3.8+（构建 AgentLens）
- 本机已安装 `dsh` 并可运行（受控/实时模式需要）
- 至少一个真实 DSH 会话日志（导入模式需要），位于 `~/.dsh/sessions/`

```bash
mvn clean verify          # 构建，确认全绿
```

## 快速开始（三条命令）

以下命令均在仓库根目录执行；`"-Dexec.args=..."` 里就是 CLI 的原始参数，
打成 jar 后可等价替换为 `java ... com.agentlens.cli.AgentLensApp <参数>`。

```powershell
# ① 导入模式（免费）：分析一次已有 DSH 会话
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=import --session C:/Users/<you>/.dsh/sessions/<目录>/session.jsonl.zstd"

# ② 受控模式（耗配额）：场景集批量评测 dsh（先用 1 场景验证！）
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-scenarios.json --repeats 1"

# ③ 实时模式（耗配额）：边跑边看 + 违规即杀
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-scenarios.json --live --abort-on-violation"
```

## 模式一：导入已有会话（零成本，先做这个）

适用：分析任何一次已经发生的 DSH 会话——包括你在 Web GUI / TUI 里的日常使用记录。

### 1. 找到会话日志

```bash
# Windows（DSH_HOME 默认 ~/.dsh）
dir "$env:USERPROFILE\.dsh\sessions" /s /b | findstr session.jsonl
```

日志是 `session.jsonl.zstd`（拼接式 zstd 多帧容器）。AgentLens 内置了解码器，
**不要手动解压**，直接传原始文件。

### 2. 导入并分析

```bash
mvn -q exec:java -pl agentlens-cli \
  "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" \
  "-Dexec.args=import --session C:/Users/you/.dsh/sessions/<目录>/session.jsonl.zstd"
```

输出包含：

- 会话元数据（sessionId、cwd、轮数、时长）
- 统一 Trace 事件流（工具调用 / 工具结果 / 模型调用，含 provider/model）
- 评测结果（task-completion、run-limits）
- 诊断（重复调用、步数超限等，附 `trace[i]` 证据引用）

### 3. 已验证基线

真实会话（9 轮对话、108 次工具调用）导入输出示例：

```text
session session-7ef3b87a-...
  cwd=D:\code\AgentLens turns=9 events=305 status=SUCCESS
  tools called: 108, tool results: 107
  evaluation:
    task-completion    passed=true  Agent completed the task
    run-limits         passed=false Run exceeded step limit
  diagnoses: 2
    execution       repeated tool calls (52 retries) ...  evidence=[trace[137] ...]
```

> 已知限制：诊断引擎的重复调用检测会把并行 `read` 误判为重试（同名连续事件），
> 解读时注意结合 `callId` 区分。改进项见 ROADMAP 待办。

## 模式二：受控评测（pass^k / 门禁）

适用：在固定场景集上批量评测 DSH，度量稳定性和工具策略合规。

### 1. 准备场景

参照 `data/scenarios/dsh-scenarios.json`，每个场景的关键字段：

```json
{
  "id": "dsh_create_readme_section",
  "instruction": "Create a file named notes.md ...（给 DSH 的完整任务指令）",
  "allowed_tools": ["read", "write", "edit", "glob", "grep", "pwsh"],
  "forbidden_tools": ["web_search"],
  "max_steps": 25,
  "agent_behavior": "external",
  "adapter": "dsh",
  "workdir": "D:/tmp/dsh-eval/task1"
}
```

要点：

- `agent_behavior: "external"`——真实 Agent，无预设脚本（必须，否则校验拒绝）。
- `adapter: "dsh"`——路由到 DSH 适配器（也可省略，用 `--adapter dsh` 全局指定）。
- `workdir`——**强烈建议**为每个场景准备独立、可丢弃的工作目录，预先放置任务所需
  输入文件（typo.txt、data.csv 等）。每次运行前重置该目录，否则 k 次重复会互相污染。
- `allowed_tools/forbidden_tools`——对应 DSH 的工具名（read/write/edit/pwsh/web_search/
  glob/grep...）。`forbidden_tools` 中的工具被调用即硬失败（ToolPolicyEvaluator 零容忍）。
- 断言锚定**终态**（文件存在/内容正确），不要锚定中间步骤——DSH 是非确定性的。

### 2. 运行

```bash
mvn -q exec:java -pl agentlens-cli \
  "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" \
  "-Dexec.args=run --scenarios data/scenarios/dsh-scenarios.json --repeats 3"
```

⚠️ **成本提示**：每个场景每次重复 = 一次完整 DSH 会话（真实 LLM 调用，分钟级时长）。
5 场景 × 3 次重复 = 15 次会话。建议先用 1 个场景 × 1 次验证链路，再扩量。
超时预算默认 900s/任务，可在场景 context 级覆盖（`timeoutSec`）。

### 3. 适配器工作原理（排障用）

```text
run(task) 流程：
  dsh --profile headless "<instruction>"   （cwd = 场景 workdir）
    → 等待退出（timeoutSec 超时则 TIMEOUT）
    → 扫描 $DSH_HOME/sessions 下 start 之后最新的 session.jsonl(.zstd)
    → zstd 多帧解码 → 事件映射 → AgentRun(events)
```

排障：

- `no session log found`：检查 `DSH_HOME` 环境变量；确认 dsh 命令输出无报错
  （失败信息里附带 dsh 进程输出尾部）。
- 找错会话：适配器按"任务开始后最新写入"挑选日志，避免评测期间在同一机器上
  使用其他 DSH 会话。
- 自定义启动器：`DSH_CMD` 环境变量或场景 context 的 `dshCommand`。

### 4. 结果解读与门禁

- `pass^k`：k 次全部通过的比例（稳定性下限）。DSH 类非确定性 Agent 这是核心指标。
- 分层视图：STABLE_PASS / FLAKY / STABLE_FAIL——间歇性失败不会被平均分掩盖。
- `release gate: passed=false` → exit 1，可直接接 CI。
- `show --run RUN_ID` 还原任意一次运行的完整轨迹与评测（SQLite 持久化）。

### 5. baseline/candidate 对比

对 DSH 的典型用法（当前 `compare` 子命令的 --baseline/--candidate 语义仍是 mock 行为，
DSH 版对比需通过两次 `run` + 不同配置实现，见 ROADMAP 待办）：

```text
baseline  = DSH + 模型 A（或某插件配置）
candidate = DSH + 模型 B
同一场景集、同 repeats，比较 pass^k
```

## 模式三：实时评测（--live）

适用：受控运行的实时版本——边跑边看 Trace、违规即刻判定，可选违规即终止进程。

### 1. 命令

在仓库根目录（D:\code\AgentLens）用 Maven exec 运行（首次运行前先 `mvn clean verify` 构建）：

```powershell
# 实时展示：流式打印统一 Trace 事件 + 在线判定（不终止进程）
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-scenarios.json --live"

# 违规即杀：零容忍规则（forbidden tool）触发的瞬间终止 dsh 进程
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-scenarios.json --live --abort-on-violation"

# 只跑第一个场景先验证链路（强烈建议，省钱）
# 先把 dsh-scenarios.json 复制一份只留 1 个场景，然后：
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-one-scenario.json --live --repeats 1"
```

Linux/macOS 用反斜杠续行、单引号包参数即可。若已把 AgentLens 打成 jar：
`java -cp "agentlens-cli/target/classes;$(依赖)" com.agentlens.cli.AgentLensApp run ... --live`。

实时输出示例（时间戳 + 事件流 + 在线判定）：

```text
[21:32:05] TOOL_CALL     read        {file_path=D:\tmp\task1\typo.txt, ...}
[21:32:07] TOOL_CALL     edit        {file_path=D:\tmp\task1\typo.txt, ...}
[21:32:09] !! safety     forbidden-tool  called forbidden tool 'web_search'
  => status=CANCELLED duration=14s events=12 diagnoses=3
```

`!!` 行是 `LiveEvaluator` 的在线判定；带 `--abort-on-violation` 时硬违规（safety 层）
出现的瞬间 dsh 进程即被终止，run 状态为 `CANCELLED`。

### 2. 工作原理

```text
dsh headless 启动
  → 适配器等待 session log 出现（≤30s）
  → DshSessionLogWatcher 每 500ms 轮询：
      文件增长 → 重扫 zstd 帧 → 只解新帧 → 部分行缓冲 → 事件回调
  → 每个事件：映射为统一 Trace → LiveEvaluator 在线判定
  → forbidden tool / 步数超限 / 连续重复调用 —— 即刻输出 verdict
  → --abort-on-violation 且 hard violation → process.destroyForcibly()
  → 进程结束后照常读完整日志出最终 AgentRun（终态判定不受影响）
```

在线判定规则（`LiveEvaluator`，位于 core 模块）：

| 规则 | 层级 | 硬违规 | 触发 |
|---|---|---|---|
| forbidden-tool | safety | 是 | 调用场景禁止的工具（可触发提前中止） |
| step-limit | execution | 否 | 工具调用数超过 maxSteps |
| repeated-calls | execution | 否 | 同一工具连续调用 8 次（窗口判定，并行交替不误报） |

### 3. 提前中止的价值

无 `--abort-on-violation` 时，DSH 调用 forbidden 工具后仍会跑完整个任务——
浪费配额且可能继续危险操作。实时模式下硬违规后**毫秒级终止**，进程结束状态
标记为 `CANCELLED`，错误信息记录触发的 verdict。中止的 run 照常进入评测
（TaskCompletion 会正确判失败）。

### 4. 已知边界

- 任务完成度仍需等会话结束（final answer 不存在时无法判定）——实时与事后互补；
- 轮询粒度 500ms：DSH 按 durable batch 落盘，事件延迟秒级，判定足够；
- 评测期间避免同一 DSH_HOME 下运行其他会话（日志按最新写入挑选）。

## 事件映射参考（审计 / 二次开发）

| DSH session log 事件 | AgentLens 统一 Trace | 说明 |
|---|---|---|
| `session` | 运行元数据 | sessionId、cwd、createdAt、preset |
| `request/context` | MODEL_CALL 属性 | provider、model、contextWindow |
| `user/message` | instruction 恢复 | 首条用户文本 |
| `tool/call` | TOOL_CALL | name、callId、arguments（截断至 200 字符） |
| `tool/result` | TOOL_RESULT | callId 关联回调用名；error 项 → status=error |
| `assistant/message` | MODEL_CALL + FINAL_ANSWER | 末条 assistant 文本即最终回答 |
| `turn/start`、`step/start|end` | 轮数/步数统计 | 映射进 maxSteps 评测 |
| `permission/preset`、`sandbox/mode` | （保留原始日志） | compliance 评测的数据源（待办） |
| `assistant/chunk` 等流式分片 | 忽略 | 装配后的 message 事件已含同样信息 |

## 常见问题

**Q：为什么不用 LLM Judge 评 DSH？**
A：AgentLens 原则是确定性优先（ROADMAP 5.1/5.3）。当前对 DSH 的评测——任务完成、
工具策略、步数/循环——全部可规则化。LLM Judge 留给解释质量等主观维度。

**Q：评的是 DSH 还是模型？**
A：评的是 "DSH + 模型" 组合（同 AgentLoop 文章的界定）。`request/context` 事件记录了
provider/model，结果可按模型分组解读。DSH 本身模型无关，换模型即换 candidate。

**Q：能评其他人的 DSH 会话吗（离线日志）？**
A：能。只要拿到 `session.jsonl.zstd` 原始文件，`import` 命令即可分析，不需要本机装 DSH。
