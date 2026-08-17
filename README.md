# AgentLens

AgentLens 是一个通用的 AI Agent 可观测、评测、诊断与回归测试平台。

项目不限定具体行业或 Agent 框架。运维、RAG、客服、浏览器、代码和多 Agent 系统都通过 Adapter 接入统一的 Agent Trace，再由评测器分析任务完成、工具调用、证据、效率和安全性。

## 当前状态

当前仓库是新项目的工程化基础骨架，已包含：

- Maven 多模块结构。
- 核心领域模型：Task、Event、Run、EvaluationResult。
- 可插拔 `AgentAdapter` 接口。
- Mock Agent Adapter。
- 第一个确定性评测器。
- CLI 演示入口。

详细路线见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 模块

```text
agentlens-core       领域模型和核心接口
agentlens-adapters   HTTP、CLI、Mock 等 Agent 适配器
agentlens-eval       确定性指标和 LLM Judge
agentlens-cli        命令行入口
```

## 运行

需要 JDK 17+ 和 Maven 3.8+：

```bash
mvn clean verify
mvn -pl agentlens-cli -am exec:java
```

## 设计原则

1. 被测 Agent 与评测器解耦。
2. 先确定性检查，再使用 LLM Judge。
3. 所有评测都保留证据和原始 Trace。
4. 危险操作和越权行为使用硬失败规则。
5. 所有结果能够复现并参与 CI 回归门禁。
