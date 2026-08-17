package com.agentlens.core;

public interface AgentAdapter {
    String name();

    AgentRun run(AgentTask task) throws Exception;
}
