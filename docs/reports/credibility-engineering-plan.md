# 评测可信度工程（Evaluation Credibility Engineering）——详细计划

> 目标：把"评测结论可信"从口号变成 9 个可测量的验证 + 3 个 judge 机制升级。
> 每个子任务的数字产出直接回填 [interview-cases.md](interview-cases.md) 的 B/C 节。
> 节奏：按优先级切子任务，每个独立提交推送；质量门 `mvn -Pquality verify` 全绿。

## 0. 事实基础（计划前逐文件核实，设计以此为约束）

1. **分层语义**（`PassK.layerOf`）：单场景 k 次全过=STABLE_PASS、全败=STABLE_FAIL、
   否则 FLAKY → FLAKY 误判解析式 `p^k+(1-p)^k` 成立（p=0.6,k=5 ≈ 8.8%）。
2. **门禁 Level 3 是严格比较**（`RegressionGate.compare`）：`candidate.passPowK() <
   baseline - maxPassPowKDrop - 1e-9`（默认 tolerance=0）**且** `candidate.avgPassRate() <
   baseline - 1e-9` 任一命中即 fail。对 p∈(0,1) 的两次独立采样，avgPassRate 严格相等的
   概率≈0 → **预期虚警率 ≈ 50%**（P(cand<base)）。CI 里 correct/correct 能过仅仅因为
   mock 是 p=1 无方差。→ C2 实验的核心命题，见 S3。
3. **LlmJudge 接口**（eval）：`judge(task,run,criterion)→EvaluationResult` +
   `review(prompt)→String`（fail-open）。`compare --review` 的 prompt 在
   `AgentLensApp.reviewGate` 组装 → B1 位置随机化在调用方做，接口零改动。
4. **LlmJudgeEvaluator**：4 固定 criteria，逐个调 judge、异常计入 degraded、
   全部 degraded 时 fail-open `passed=true,score=0`。无 votes、无 abstain 语义、
   无 temperature（后端参数，CLI `buildJudge` 构建处）。
5. **DiagnosisEngine**：7 条规则（forbidden→safety、unknown→tool_selection、repeated、
   stepLimit、error、timeout、unsuccessful），Diagnosis 7 字段（layer,issue,confidence,
   severity,evidence,guidance,metadata）。**预期盲区**：wrong_conclusion /
   fabricated_evidence / partial_evidence 是答案层缺陷，trace 级规则大概率零诊断；
   cheat_tamper 仅因 `write` 不在 allowed 而间接触发 unknown-tool，"篡改语义"无规则。
6. **mock 行为清单**（12 种）：correct、correct_detailed、wrong_conclusion、
   fabricated_evidence、partial_evidence、wrong_tool、forbidden_action、loop、
   give_up(FAILED+error 事件)、cheat_tamper、cheat_hardcode、flaky(0.6)。
7. **judge-calibrate**：`JudgeCalibration`（accuracy/precision/recall/F1/κ/混淆矩阵）
   + corpus JSON（当前 10 条 = 2 正 8 负，无分层、无 train/val）。

## 1. 子任务定义（提交序列 S1–S9）

### S1 诊断标定 `diagnose-calibrate`（C1）

- **设计**：eval 新类 `DiagnosisCalibration`（混淆矩阵、per-rule P/R/F1、消融开关）；
  CLI 命令跑内置行为语料（12 行为 × 标签表，标签=行为→预期规则集+预期 layer，放在
  测试资源 JSON，规则演进时同 PR 更新）；输出 `output/diagnosis-calibration.json`。
  `--disable-rule X` 支持单规则消融。
- **验收**：correct/correct_detailed 假阳性=0；矩阵落盘；消融可用。
- **预期发现（记报告）**：答案层三行为零诊断（引擎盲区清单）；cheat_tamper 只有
  unknown-tool 间接信号。
- **测试**：+3（统计类、标签对账、消融）。

### S2 数学验证件套（C3+C4，合并一个提交）

- **C4 PassK**：`PassKPropertiesTest`——①序性质 pass^k ≤ pass@k ≤ avg（随机 1000 组，
  手写循环不引依赖）；②k≤3、n≤3 暴力枚举对拍；③恒定 p 闭式解 p^k 的蒙特卡洛
  （10k 采样/组，3σ 覆盖 ≥99.7%）。
- **C3 FLAKY**：误判率矩阵蒙特卡洛（p∈{0.1..0.9}×k∈{1,2,3,5,10,20}，每组 10k 次）
  与解析 `p^k+(1-p)^k` 对拍（|diff|<0.5pp）；指导表（"区分 p=0.5/p=0.9 到 95% 置信的
  最小 k"）写进 `--repeats` 帮助与 ROADMAP。
- **验收**：测试绿 + 表格落库。

### S3 门禁功效实验（C2）

- **设计**：`gate-power` 命令：参数 (p, k, tolerance, trials) → {虚警率（基线=候选
  同分布）, 检出率（候选=p−δ）}；标准网格输出 `output/gate-power.md`。
- **核心命题**：核实"默认 tolerance=0 → p∈(0,1) 时虚警率≈50%"；给出 tolerance 建议
  曲线。**处置决策：不改默认契约**（兼容已批准基线与既有 CI），若 CLI 未暴露
  `maxPassPowKDrop` 则新增 `compare --gate-tolerance` 显式 flag 透传既有构造参数。
- **验收**：功效表落盘；虚警率与理论值自洽；flag（如缺）+ 测试。

### S4 `compare --review` 位置随机化（B1）

- **设计**：reviewGate 组装时 shuffle(A,B)（seed 落日志可复现）；`--review-both`
  双向各评一次：一致才采信，不一致标注 `divergent`。免费版仅随机化+审计记录。
- **验证**：固定 10 组已知优劣对（correct_detailed vs wrong_conclusion 等），三模式
  （固定/随机/双向）对比一致率与正确率；数字回填案例库 B1。
- **触点**：`AgentLensApp.reviewGate`，接口零改动。真机验证：本地 Ollama。

### S5 Judge prompt 硬化（B2）

- **设计**：后端 prompt（定位 `buildJudge` 实现）加四件套：锚定声明、证据摘录前置
  （"先引用 trace 原文片段再判定"）、边界 few-shot、**`insufficient_evidence` 第三判定**；
  temperature 显式 0.1。
- **兼容决策**：`insufficient_evidence` 通过 metadata verdict 标记、`LlmJudgeEvaluator`
  聚合时移出 pass/fail（与 degraded 并列的第三态）；**默认不启用**（prompt 版本区分，
  `--judge-abstain` 开关），旧行为不变。
- **验证**：构造截断 trace bad case 集 → 硬化前后误判率；同输入 5 次一致率。
- **风险**：R3 语义变更——用开关规避；Ollama 离线时 stub 判定可跑、真机数字标注环境。

### S6 `--judge-votes N`（B3）

- **设计**：`LlmJudgeEvaluator(judge, criteria, votes)`；每 criterion 跑 votes 次、
  多数投票定 passed；全一致→`confidence=high` 否则 `low`；`metadata.votes=[scores]`。
  degraded 语义不变（基础设施故障≠判定分歧）。
- **验证**：标定语料 3 票 vs 1 票准确率、一致子集占比 vs 一致子集准确率（对照外部
  "一致时 100% vs 全体 55%"）；成本 = N× 调用写文档。
- **验收**：votes=1 时行为与现状逐字节一致（回归保护）。

### S7 标定框架升级（B4）

- **设计**：corpus schema 加 `tier`（core/boundary/adversarial/safety）+ 语料 10→~30 条
  （人工编写，分层比例按风险）；`JudgeCalibration` 输出固定 seed 的 train/val 分割与
  泛化 gap；Wilson 区间反推样本量提示；calibration 工件与 approved-baseline 记
  judge 指纹（模型+prompt hash），漂移即警告。
- **验证**：注入一条过拟合 rubric（只在特定样例成立），展示 gap 暴露（预期 >20pp）；
  正常 rubric gap <5pp。
- **风险**：R4 分层定义主观——定义写进 corpus schema 文档。

### S8 失败归因标签器（B5 前半；聚类视图后续另立项）

- **设计**：eval 新类 `Attribution`：输入 run+results+场景声明 → 四层标签
  `data | rule | evaluator | agent`（启发式：规则层=断言失败+lint 信号；评测器层=
  空白名单 unknown 等已知误报模式；数据层=trace 卫生信号；其余=agent）。
  `show --run` 追加 attribution 行；`/api/runs/{id}` 加字段。
- **验证**：A2/A3 时代的历史 run 应被标 evaluator/rule 层；mock 失败行为全标 agent 层；
  754 案例库抽检归因一致率。
- **预期数字**：历史库"评测体系自身误报"占比（显著非零即证明价值）。

### S9 explanation 收敛检测（B6）

- **设计**：`explain-stats --pattern RE --from --to --db` + `GET /api/explanations`：
  按 `explanation` 字段正则命中数的时间窗对比。
- **验证**：以 A2 为样板——修复前后窗口 `unknown=\[` 命中数对比，与逐 run 复核一致。

## 2. 顺序与理由

```
S1 诊断标定 ──► S2 数学件套 ──► S3 门禁功效      （纯本地、零 LLM、最快出数字）
      │
      ▼
S4 位置随机化 ──► S5 prompt 硬化 ──► S6 votes     （judge 链，需本地 Ollama）
                                          │
      S7 标定升级 ◄── 依赖 S6 的 votes 语义 ◄────┘
      S8 归因标签（依赖 S1 的标签表与 A2/A3 历史语料）
      S9 explanation 检测（依赖 N2 的 explanation，独立，随时可插）
```

## 3. 风险与预案

| # | 风险 | 预案 |
|---|---|---|
| R1 | S4–S6 依赖本地 Ollama 在线 | 逻辑测试走 stub judge（既有模式）；真机数字单独标注环境 |
| R2 | S3 发现虚警≈50% 后是否改默认契约 | **不改默认**（兼容已批准基线/CI）；文档+显式 flag，理由写入报告 |
| R3 | S5 insufficient 语义影响既有聚合 | 默认关闭（`--judge-abstain`），prompt 版本区分，旧行为逐字节不变 |
| R4 | S7 分层语料主观性 | tier 定义写进 corpus schema；每条带 rationale 字段 |
| R5 | S1 标签表与规则实现耦合 | 标签表放测试资源 JSON，规则演进同 PR 更新（约定入文档） |

## 4. 每子任务统一验收门

`mvn -pl agentlens-cli -am -Pquality verify` 全绿 → 冒烟（命令/API 真跑）→ 数字回填
interview-cases.md → ROADMAP 新节（7.24 起）+ NEXT_TASKS → 独立提交推送。
