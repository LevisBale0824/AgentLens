# P2-2: AIOpsLab 接入设计与真机验证报告

ROADMAP P2-2 / Day 13:接入 [microsoft/AIOpsLab](https://github.com/microsoft/AIOpsLab)
作为公开运维 Benchmark。本文记录接口调研结论、适配层设计、映射契约与真机验证结果。

## 1. 背景与路线选择

AIOpsLab 完整运行需要:kind 集群 + OpenEBS 存储 + Prometheus 观测栈 + 目标微服务应用
(social-network / hotel-reservation / astronomy-shop 等)+ ChaosMesh 故障注入。
ROADMAP Day 13 预留了降级路线:**「若环境部署耗时,则先实现其任务/Trace Adapter,
再补完整 Kubernetes 集成」**。本里程碑按此路线交付适配层(离线可用、上游契约逐字对齐),
集群桥接代码就位但实测留待环境就绪(V1.1 待办)。

## 2. AIOpsLab 接口契约(源码调研结论)

| 接口 | 事实 | 来源文件 |
|---|---|---|
| 问题注册表 | ID 结构化:`<fault>-<task_type>-<variant>`,task_type ∈ {detection, localization, analysis, mitigation} | `aiopslab/orchestrator/problems/registry.py` |
| 目录规模 | **97 个问题实例 × 31 种故障**(detection 35 / localization 35 / analysis 13 / mitigation 14) | 同上(main 分支实测解析) |
| ACI 动作集 | 所有任务共享基础动作 `get_logs / get_metrics / get_traces / read_metrics / read_traces / exec_shell` + 各任务 `submit` | `orchestrator/actions/base.py` + 各任务 actions |
| 动作语法 | agent 每回合回复恰好一个 markdown 代码块,内为 `api_name(args)`;`ResponseParser.validate` 强制恰好一对 ``` ``` | `orchestrator/parser.py` |
| 会话持久化 | `results/<session_id>_<start_ts>.json`,字段:`agent / session_id / problem_id / start_time / end_time / trace[{role, content}] / results` | `session.py` |
| 评测结果 | `results` 字典:任务型指标 TTD/TTL/TTA/TTM(检测/定位/分析/缓解耗时)+ steps + in_tokens/out_tokens + 任务准确率键 | `orchestrator/tasks/*.py` |
| 错误信号 | env 回复错误时以 `Error: ...` / `InvalidActionError` / `Format validation failure` 等前缀开头 | `actions/base.py` + `parser.py` |
| 官方客户端 | `python clients/client.py --agent qwen --problem-id <id> --max-steps <n>`(cwd=AIOpsLab 检出,产物落 `results/`) | `clients/client.py` |

要点:问题实例化(`ProblemRegistry.get_problem_instance`)在构造时就依赖 `KubeCtl()` 与
应用部署,目录解析刻意只做**registry.py 源码解析**(ID 是纯结构化字符串,无需集群),
这是离线目录的可行性根据。

## 3. 交付物

新模块 `agentlens-adapter-aiopslab`(SPI 注册,CLI 依赖接入):

### 3.1 问题目录 Adapter — `AIOpsLabProblemCatalog`

- 正则解析 registry.py 中全部 `"id": lambda:` 键 → `Problem(id, fault, taskType, variant, rootCause)`。
  正则细节:fault 段允许含连字符(`k8s_target_port-misconfig`、`redeploy_without_PV`),
  非贪婪回溯在**最后一个** task_type 边界切分。
- 故障段 → 根因真值:31 项精确映射(按 main 分支真实命名,如
  `scale_pod_zero_social_net` → "Deployment scaled to zero replicas")+ 前缀回退
  (上游同族变体自动解析)+ titleize 兜底。
- 任务类型 → ACI 动作集(全任务共享 7 动作)。
- `toScenario(problem)` → AgentLens 场景属性(id / domain=`aiopslab-<task>` / alert=fault /
  root_cause 真值 / allowed_tools=ACI 动作 / agent_behavior=external / adapter=aiopslab / max_steps=15)。

**数据集再生成**(AIOpsLab 上游加题后零改动刷新):

```bash
mvn -q exec:java -pl agentlens-cli -Dexec.args="aiopslab-catalog"
# 默认:registry=模块内置逐字 fixture,out=data/scenarios/aiopslab-scenarios.json
# 实测:97 problems → detection 35 / localization 35 / analysis 13 / mitigation 14
```

### 3.2 Session Trace Adapter — `AIOpsLabSessionImporter`

真实 session JSON → 统一 Trace 的映射契约:

| AIOpsLab 元素 | AgentLens 事件 | 说明 |
|---|---|---|
| assistant 回复含可解析 ```api_name(args)``` | `TOOL_CALL(name=api, callId=aci-<i>, arguments=原始动作行)` | 按 ResponseParser 约定取第一对代码块 |
| assistant 回复不可解析 | `MODEL_CALL` | Agent 产出了推理但无合法动作 |
| env 回复 | `TOOL_RESULT(配对最近调用, status=ok\|error)` | ACI 错误前缀 → error |
| `submit(...)` | `TOOL_CALL(submit)` + run 末尾 `FINAL_ANSWER` | solution = 去引号参数 |
| `start/end_time` (epoch s) | `durationMs` | |
| `results.in_tokens + out_tokens` | `totalTokens` | |
| run 状态 | **SUCCESS ⇔ 已 submit 且 results 含 TTD/TTL/TTA/TTM/success** | AIOpsLab 评测器实际跑过才算成功 |

CLI(离线,零配额):

```bash
mvn -q exec:java -pl agentlens-cli \
  -Dexec.args="import --aiopslab --session PATH/TO/<session_id>_<ts>.json"
```

评测器:task-completion + run-limits + tool-policy(**白名单 = ACI 七动作**,任何越出
Agent-Cloud Interface 的工具名直接判违规)+ 可选 `--judge`。结果 + 诊断 + SQLite 持久化
(adapter=aiopslab, agentVersion=imported, metadata.problemId),`show --run` 可完整复现。

### 3.3 集群桥接 Adapter — `AIOpsLabAdapter`(SPI)

- 配置 `AGENTLENS_AIOPSLAB_HOME`(AIOpsLab 检出)+ 就绪集群后,每场景执行官方客户端:
  `python clients/client.py --agent <AGENTLENS_AIOPSLAB_AGENT,默认 qwen> --problem-id <task.id>
  --max-steps <maxSteps>`(cwd=AIOpsLab 检出,超时默认 1800s),结束后自动导入
  `results/` 中启动时间之后最新的 session JSON。
- 未配置/检出无效时 run 失败(FAILED + ERROR 事件),错误信息明确指引离线路径。
- SPI 注册实测:`available: [mock, cli, http, dsh, aiopslab]`。

## 4. 真机验证

| 验证项 | 结果 |
|---|---|
| 单测(真实 registry.py 逐字 fixture + 真实格式 session) | 模块 13 个测试全绿,全仓 `mvn -q clean install` exit=0 |
| `aiopslab-catalog` 再生成 | 97 问题 → `data/scenarios/aiopslab-scenarios.json` |
| 数据集加载 + 冒烟 | ScenarioLoader 加载 97 场景,mock pass^k=1.000,门禁放行,p50=71ms |
| `import --aiopslab`(合成真实格式 session,含错误后原样重试) | 13 事件映射正确(callId 配对、env error 标记、submit→FINAL_ANSWER);三项评测全过;**诊断命中 `repeated tool calls: 2 retry-after-error`,证据 trace[4]/trace[8]**;SQLite 持久化;`show --run` 完整复现 |

## 5. 已知边界与后续

- **目录/导入是 main 分支快照**:fixture 逐字副本 + MIT 归属注释;上游演进用
  `aiopslab-catalog --registry <新 registry.py>` 刷新数据集(测试断言 97/31 需同步更新)。
- **session `problem_id` 不携带任务描述**:任务描述由 AIOpsLab 在 `init_problem` 时动态拼装
  (含 app summary),session JSON 不落盘;导入评测因此以 problem_id 为 instruction 锚点。
- **集群桥接未实测**(待 V1.1):kind + OpenEBS + Prometheus + 应用部署就绪后,跑通
  `run --scenarios data/scenarios/aiopslab-scenarios.json --adapter aiopslab`,
  得到真实外部 Agent 在 97 题上的 pass^k 分层。
- **K8sGPT**(ROADMAP P2-2 第二目标):诊断型 Agent,与 AIOpsLab 自主修复 Agent 互补;
  接入路径为 cli/http 适配器或 session 导入对比,留待集群环境一并验证。
