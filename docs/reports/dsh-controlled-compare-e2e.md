# DSH 受控模式(非 live)独立实测 + DSH 版 baseline/candidate compare 首跑报告

- 日期:2026-09-05
- 前置:`9ec2429`(callId 感知重复调用检测)之后;`mvn -q clean install`(6 模块全绿)
- 配额消耗:**5 次真实 dsh headless 会话**(受控实测 1 次 + compare 4 次),全部 SUCCESS

## 一、受控模式(非 live 路径)独立实测 —— 成功

### 运行命令

```powershell
# 前置:清空工作目录(task1 的 notes.md 已删除,保证任务真实重跑)
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-one-scenario.json --repeats 1"
```

与 live 实测(见 dsh-live-e2e.md)的唯一区别:**不带 `--live`**,走 `run` 命令的批量分支
(`DshAgentAdapter.run(task, null)` → ExperimentRunner → SQLite/JSONL → PassK → 门禁)。

### 完整输出

```
loaded 1 scenarios from data\scenarios\dsh-one-scenario.json, repeats=1
adapter: dsh (available: [mock, cli, http, dsh])
single batch: runs=1, failures=0, p50=18520ms, p95=18520ms, max=18520ms

pass^k summary: scenarios=1, pass^k=1.000, avgPassRate=1.000
  STABLE_PASS  1 [dsh_create_readme_section]

release gate: passed=true, reason=all scenarios stable-pass
```

### 结果核验

1. **产物**:notes.md 26 字节、三行,与 instruction 完全一致。
2. **SQLite 落库与还原**:`show --run list` 中该 run(`session-9f662758-...`)按
   started_at 排序列第 1;`show --run` 完整还原 8 事件 trace:

```
MODEL_CALL  dsh.model  {provider=zai-coding-cn, model=GLM-5.3-Flash, step=1, turn=1}
TOOL_CALL   write      {callId=call_1865..., arguments={"content":"# Notes\n\n...","file_path":"...notes.md"}}
TOOL_RESULT write      {callId=call_1865..., status=ok}
MODEL_CALL  dsh.model  {step=2, turn=1}
TOOL_CALL   read       {callId=call_e8b4..., arguments={"file_path":"...notes.md"}}   ← agent 主动回读自检
TOOL_RESULT read       {callId=call_e8b4..., status=ok}
MODEL_CALL  dsh.model  {step=3, turn=1}
FINAL_ANSWER dsh.answer
```

3. **callId 感知检测的实战印证**:agent 在 write 后用 read 回读自检——旧"相邻同名即计数"
   实现会在特定事件序列下误报;新检测器按签名(write≠read)+ callId 配对正确不报
   (diagnoses=0)。真机 trace 直接验证了 9ec2429 的改造效果。

结论:受控模式(非 live)全链路(执行 → 统一 Trace → 评测 → 持久化 → pass^k → 门禁)
一次通过,无需任何修复。

## 二、DSH 版 baseline/candidate compare —— 成功(需先行开发)

### 开发内容

`compare` 原实现硬编码 `MockAgentAdapter(behavior)`(`--baseline/--candidate` 是 mock 行为名),
没有真实 Agent 通路。本次为 `compare` 新增 `--adapter dsh` 模式:

- **两侧均为真实 headless 会话**:baseline/candidate 各把同一固定场景集完整跑一遍,
  由同一 `RegressionGate` 按 pass^k 对比——对同一 agent 的两次独立发版测量做回归判定。
- **顺序执行**:并行会话在同一 workdir 会竞争任务产物,故每侧场景串行跑。
- **落库带版本标签**:runs/evaluations 以 `agentVersion=baseline|candidate` 区分,
  show/report 可分辨两侧。
- **配额护栏**:dsh 模式 `--repeats` 缺省降为 1(mock 仍为 5),并在输出头部打印
  `estimated dsh sessions: N`,防止误烧配额。

新增场景集 `data/scenarios/dsh-compare-scenarios.json`(2 个幂等任务,独立 workdir):
`dsh_create_readme_section`(覆写 notes.md)、`dsh_count_lines`(对预置 data.csv 计数写
count.txt,夹具 5 行)。幂等性保证 candidate 侧在 baseline 侧产物已存在时仍可真实重跑。

### 运行命令

```powershell
mvn -q exec:java -pl agentlens-cli "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=compare --adapter dsh --scenarios data/scenarios/dsh-compare-scenarios.json --repeats 1"
```

### 完整输出(4 次真实会话,全部 SUCCESS)

```
comparing releases on 2 scenarios from data\scenarios\dsh-compare-scenarios.json, repeats=1, adapter=dsh
estimated dsh sessions: 4 (sides=2 x scenarios=2 x repeats=1)

[baseline] repeat 1/1: dsh_create_readme_section
  => status=SUCCESS duration=16940ms events=8 diagnoses=0
[baseline] repeat 1/1: dsh_count_lines
  => status=SUCCESS duration=26845ms events=11 diagnoses=0
[candidate] repeat 1/1: dsh_create_readme_section
  => status=SUCCESS duration=20090ms events=8 diagnoses=0
[candidate] repeat 1/1: dsh_count_lines
  => status=SUCCESS duration=29420ms events=8 diagnoses=0

baseline   dsh-headless           pass^k=1.000  avgPassRate=1.000
candidate  dsh-headless           pass^k=1.000  avgPassRate=1.000
  candidate STABLE_PASS  2 [dsh_create_readme_section, dsh_count_lines]

release gate: passed=true
  reason=regression gate passed
```

### 结果核验

- count.txt 内容为 `5`(1 字节,正确行数);data.csv 未被改动(47 字节);notes.md 26 字节。
- 4 个新 run(`session-*`)全部落库,列表头部即 candidate 侧两跑。

### 语义说明(诚实记录)

两次测量的 agent 是**同一个 dsh 安装**,因此本演示证明的是:**真 Agent 完整走通
compare+门禁管线**且该 agent 在此数据集上测量稳定(pass^k 1.000 → 1.000 放行)。
"candidate 退化被拒绝"的负路径仍由 mock compare(`compare --baseline correct --candidate flaky`,
CI 已断言)承担;对真 Agent 构造退化候选(如不同模型/配置)需要 dsh 侧支持版本切换,
属于后续可选扩展。

## 配额与可复现性

| 测试 | 会话数 | 用时 |
|---|---|---|
| 受控 run(1 场景 × 1 repeat) | 1 | ~19s |
| compare(2 场景 × 2 侧 × 1 repeat) | 4 | ~94s |

复现命令即上文两条;compare 需先 `mvn -DskipTests install`(stale ~/.m2 坑,见
dsh-live-e2e.md 问题 3)。原始输出:`D:\tmp\dsh-eval\controlled-run1.txt`、
`D:\tmp\dsh-eval\compare-run1.txt`。

## 后续建议

1. ~~compare 交互式用法(如 candidate 侧换模型/换 dsh 配置)需适配器支持 per-side 配置,
   可作为 v0.3 候选。~~ ✅ 已落地(`--baseline-override/--candidate-override`,7fdd0ea)。
2. `--repeats 2` 以上的 pass^k 分层(FLAKY 检测)可在真 Agent 上补一次测量(约 8 会话)。
3. ~~`dirToken` 破折号折叠的极端路径碰撞~~ ✅ 已修复:对照真机会话目录实测
   (`D:\code\AgentDock` → `--D-code-AgentDock--`)精确复刻 DSH 命名,匹配从宽松
   `contains` 收紧为折叠后全等——cwd `D:\tmp` 不再误匹配 `--D-code-d-tmp--`;
   同折叠路径碰撞(`D:\tmp-x\a` vs `D:\tmp\x\a`)系 DSH 自身命名固有,已有测试记录。
