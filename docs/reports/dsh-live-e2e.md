# DSH 受控模式首次真机端到端实测报告（任务一）

- 日期：2026-08-21 01:06（本机时间）
- 场景：`dsh_create_readme_section`（单场景抽取，`data/scenarios/dsh-one-scenario.json`，workdir=`D:/tmp/dsh-eval/task1`，max_steps=25）
- 结论：**成功**。dsh headless 真机会话完成文件创建任务，`status=SUCCESS`，`passed=1 failed=0 aborted=0`，全程仅消耗 **1 次**真实 dsh 会话（预算 2 次）。

## 运行命令

```powershell
mvn -q -DskipTests install   # 关键前置，见"问题 3"
mvn -q exec:java -pl agentlens-cli `
  "-Dexec.mainClass=com.agentlens.cli.AgentLensApp" `
  "-Dexec.args=run --scenarios data/scenarios/dsh-one-scenario.json --live --repeats 1"
```

## 完整输出（成功运行）

```
loaded 1 scenarios from data\scenarios\dsh-one-scenario.json, repeats=1
adapter: dsh (available: [mock, cli, http, dsh])

[01:06:11.684938500] repeat 1/1: dsh_create_readme_section
  [01:06:21] MODEL_CALL    dsh.model            {turn=1, step=1}
  [01:06:21] TOOL_CALL     write                {arguments={"file_path":"D:\\tmp\\dsh-eval\\task1\\notes.md","content":"# Notes\n\ncreated by agent\
  [01:06:21] TOOL_RESULT   dsh.tool             {status=ok, callId=call_324174c02c2b4390b2468f92}
  => status=SUCCESS duration=11830ms events=5 diagnoses=0

live run: scenarios=1 repeats=1 passed=1 failed=0 aborted=0
```

## 结果核验

### notes.md 实际内容（与场景 instruction 完全一致）

```
# Notes

created by agent
```

（26 字节，三行：`# Notes` / 空行 / `created by agent`，未动其他文件；task1 目录内仅有 notes.md。）

### 会话与 Trace 证据

- 新 dsh 会话日志出现在 `C:\Users\Min\.dsh\sessions\--D-tmp-dsh-eval-task1--\session-2127e9dc-...\session.jsonl.zstd`（目录名编码正确反映 cwd=task1，`findSessionLog` 的时间戳容差与 `~/.dsh` 解析均按预期工作）。
- CLI 输出包含 `adapter: dsh`、实时 Trace 事件流（MODEL_CALL / TOOL_CALL / TOOL_RESULT，含 write 工具的 file_path 与 content 参数）、以及 live 判定摘要。
- Windows 启动链路 `cmd /c dsh --profile headless "<instruction>"` 正常（`DshAgentAdapter.launcher` 的 cmd /c 分支 + instruction 末位参数）。

## 过程中发现的问题与修复

前两次 mvn 尝试均在启动 dsh 前以 NPE 崩溃（未消耗 dsh 会话额度；已核验 ~/.dsh/sessions 无新日志且 task1 为空）。共发现并修复 3 个问题：

### 问题 1（阻断）：`Scenario.toTask` 对 real-agent 场景必发 NPE

real-agent 场景（`adapter=dsh`）没有 `alert` / `root_cause` 字段，`toTask()` 把 null 值放进 context，而 `AgentTask` 构造器中 `Map.copyOf(context)` 拒绝 null 值直接抛 NPE。此前只有 ops 场景（字段齐全）被测试覆盖。

- 修复：`agentlens-eval/src/main/java/com/agentlens/eval/Scenario.java` —— null 字段不再放入 context。
- 回归测试：`agentlens-eval/src/test/java/com/agentlens/eval/ScenarioLoaderTest.java` 新增 `externalScenarioWithoutAlertConvertsToTask`。

### 问题 2（阻断）：CLI 不支持布尔 flag

`parseOptions` 假定每个 `--opt` 都带值，`--live --repeats 1` 会把字面量 `--repeats` 当成 live 的值、且丢弃 `1`；缺省 `run` 时首 flag 也会被当作命令词跳过。

- 修复：`agentlens-cli/src/main/java/com/agentlens/cli/AgentLensApp.java` —— 无值/下一 token 是 flag 时按布尔 `true` 处理；仅当 args[0] 是已知命令词时才跳过。

### 问题 3（阻断，文档级坑）：`mvn -pl agentlens-cli exec:java` 使用本地仓库里的旧版依赖 jar

`mvn clean verify` 不执行 install，`-pl` 单模块构建从 `~/.m2` 解析到修复前的 `agentlens-eval` 旧 jar，导致问题 1 的修复"不生效"（第二次失败的真实原因）。`-pl agentlens-cli -am` 则会把 exec 目标跑到根 pom 上同样失败。正确姿势：先 `mvn install`（或 `-DskipTests install`）再 exec。已在本报告与运行命令中固化；`docs/DSH-EVALUATION-GUIDE.md` 的快速开始命令建议后续补上该前置步骤。

### 修复文件清单

| 文件 | 变更 |
|---|---|
| `agentlens-eval/src/main/java/com/agentlens/eval/Scenario.java` | toTask 跳过 null 字段 |
| `agentlens-cli/src/main/java/com/agentlens/cli/AgentLensApp.java` | parseOptions 布尔 flag + 命令词识别 |
| `agentlens-eval/src/test/java/com/agentlens/eval/ScenarioLoaderTest.java` | 新增 external 场景回归测试 |

修复后 `mvn clean verify` 全绿（6 模块、65 测试，含 1 个新增回归测试）。

## 已知差距（不阻断，记录在案）

1. **live 模式不打印 pass^k 摘要**：`--live` 走 `dshLiveRun` 分支，只输出 `live run: scenarios=1 ... passed=1`；pass^k 摘要仅在非 live 批量路径打印。后续可让 live 分支复用 PassK 汇总。
2. **live 运行不落库**：live 分支不经过 SqliteRunStore，结果仅 stdout；`show --run`/`report` 查不到本次运行。
3. **run1/run2 排障输出**：保留在 `D:\tmp\dsh-eval\run1-output.txt` / `run2-output.txt`，成功输出在 `run4-output.txt`。

## 下一步建议

1. `docs/DSH-EVALUATION-GUIDE.md` 快速开始命令补上 `mvn install` 前置说明（问题 3）。
2. live 分支接入 PassK 摘要与 SqliteRunStore 持久化，使 live/批量两条路径产出一致。
3. 用剩余预算外的新会话扩测 `dsh_fix_typo`、`dsh_count_lines` 等场景与 `--abort-on-violation` 早停路径。
4. 为 `parseOptions` 增加 CLI 单元测试（目前 agentlens-cli 无测试目录）。
