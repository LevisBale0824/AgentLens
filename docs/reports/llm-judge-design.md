# LLM Judge 设计与实现(P2-1,任务:评测体系补全)

- 日期:2026-09-05
- 状态:**已落地**。`OpenAiJudge` + `LlmJudgeEvaluator`,CLI `--judge` 接入 run / import / live / compare(--adapter dsh),本地 Ollama qwen2.5:7b 真机验证(真实 DSH 轨迹 + mock run)。

## 1. 定位与原则

ROADMAP 设计原则 #2:"先确定性检查,再使用 LLM Judge"。此前确定性侧(任务完成度、
工具策略、运行限制、合规红线、诊断引擎)全部落地,而 `LlmJudge` 只有一行接口。
本实现补全后半句:

- **Judge 只处理难以规则化的维度**(ROADMAP 5.3):解释质量、证据充分性、建议质量、
  计划合理性——四个预设 criteria,各带 rubric。
- **Judge 是质量信号,不是安全门**:安全违规、禁止工具仍由确定性评测器零容忍硬失败。
- **fail-open**:judge 基础设施故障(端点不可达、持续畸形输出)不阻塞发版门禁,
  按 criterion 记录 degraded(全部失败时 `judge.status=unavailable`,passed=true score=0);
  judge **正常给出的否定判定**则如实参与评测结果(选择 `--judge` 即选择让质量信号
  参与 pass^k 与门禁)。

## 2. 架构

```text
EvaluatorRegistry
  └─ LlmJudgeEvaluator (evaluator 名 "llm-judge")
       ├─ criteria 预设 rubric(LinkedHashMap,顺序确定)
       ├─ 逐 criterion 调 LlmJudge.judge(task, run, rubric)
       └─ 合并:passed=AND, score=平均, 理由逐条列出, metadata 按 criterion 短名存分
            └─ OpenAiJudge implements LlmJudge
                 ├─ 传输:OpenAI 兼容 /v1/chat/completions | Ollama 原生 /api/chat
                 ├─ 输入:system(评审规则) + user(rubric + instruction + trace digest)
                 ├─ 输出:严格 JSON {score, passed, reason, evidence[]}
                 ├─ 校验:score∈[0,1]、reason 非空;缺 passed 则 score≥0.7 推导
                 ├─ 重试:最多 3 次,温度递增(0 → 0.3 → 0.6)逃逸确定性坏输出
                 ├─ 缓存:SHA-256(model|promptVersion|criterion|digest),同轨迹零重付
                 └─ 版本记录:metadata 恒带 judge.model / judge.promptVersion / judge.criterion
```

### trace digest(关键设计)

模型看到的是**消毒后的确定性摘要**:instruction、终态、每事件一行(属性按 key 排序,
截断、封顶 40 事件/6000 字符)。**所有嵌入值经过 sanitize**:`\`→`/`、`"`→`'`、
`{}`→`()`、控制符→空格——digest 是 prompt 文本而非数据。

> 这一消毒来自真机故障复盘:digest 中原始工具参数(`arguments={"content":"# Notes\n\n..."}`)
> 被小模型逐字引用进 evidence 数组时无法正确转义引号,产生坏 JSON;连 Ollama 的
> grammar 级 `format:"json"` 都救不了"引用含引号文本"的场景(约束生成合法,但模型
> 复述的内容本身带未转义引号)。消毒后 4/4 criteria 全部解析通过。

### 两种 JSON 可靠性模式(实测结论)

| 模式 | 机制 | 适用 |
|---|---|---|
| `--judge-json-mode` | OpenAI 兼容 `response_format:{"type":"json_object"}` | OpenAI / vLLM 等真正支持该字段的端点。**本机 Ollama 兼容层接受但忽略该字段**(强探针验证:要求说颜色仍返回散文),等效无效 |
| `--judge-ollama` | Ollama 原生 `/api/chat` + `format:"json"` + `options.temperature` | 本机验证有效;配合 digest 消毒,小模型也能稳定输出可解析判定 |

## 3. 真机验证记录(本地 Ollama,qwen2.5:7b,零云配额)

### 3.1 真实 DSH 轨迹(import 模式,8 事件)

```
llm-judge  passed=false
  explanation_quality=0.80(FAIL) The final answer does not fully explain what
    happened, missing the exact content of the file.
  evidence_sufficiency=1.00(pass)  ...write 后 read 回读确认...
  recommendation_quality=1.00(pass) ...clearly states the exact content...
  plan_sensibility=1.00(pass)      ...efficient, sensible plan...
```

判定有区分度:不是无脑全 pass——它准确抓住"回答没复述文件具体内容"这一真实瑕疵。

### 3.2 mock run --judge(门禁语义实证)

`run --scenarios ops-one-scenario.json --judge --judge-ollama` → **pass^k=0.000,门禁拒绝**
(STABLE_FAIL)。这是正确行为:mock-correct 的固定回答"Root cause identified with
evidence from 3 tools"确实空洞,judge 分别给 0.00/0.80/0.00/1.00 并附理由,落库后
`show --run` 完整还原。启示:**接入 judge 后,连平台自己的正确性夹具也会被质量审查挑刺**;
对质量敏感的发版应把 `--judge` 作为显式选择。

### 3.3 过程中发现并修复的问题

| # | 问题 | 修复 |
|---|---|---|
| 1 | temperature=0 时重试确定性复现同样坏输出 | 重试温度递增(0/0.3/0.6,封顶 1.0),真机救回 1 个 criterion |
| 2 | Ollama 兼容层忽略 response_format(jsonMode 无效) | 新增 Ollama 原生传输(`--judge-ollama`) |
| 3 | digest 原始 JSON 元字符被模型引用导致 verdict 转义崩溃 | digest 全量 sanitize(引号/反斜杠/花括号/控制符) |
| 4 | 降级原因只显示 Jackson 根因,丢失原始响应内容 | rootMessage 保留顶层+根因(含 500 字符响应摘录) |

## 4. 成本与可复现

- 判定缓存按内容哈希:k 次重复跑同一确定性轨迹,只有第一次真正调用模型。
- promptVersion 常量进缓存键:prompt 升级自动失效旧缓存,历史判定仍带版本号可审计。
- judge 不改变确定性评测器的任何结果;不带 `--judge` 时全链路与原来完全一致。

## 5. 后续建议

1. DeepEval/Ragas 指标经 `LlmJudge` 接入(ROADMAP 10.3 的 DeepEvalAdapter 思路)。
2. compare 门禁可加 faithfulness 阈值规则(ROADMAP P1-5 的"下降不超 5%"),当前 judge
   分数已随评测落库,规则是纯增量。
3. judge 定期的标定(与人工标注对照)仍是开放问题(ROADMAP V2"人工标注和 Judge 校准")。
