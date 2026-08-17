package com.agentlens.adapters;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MockAgentAdapter implements AgentAdapter {
    @Override
    public String name() { return "mock"; }

    @Override
    public AgentRun run(AgentTask task) {
        Instant started = Instant.now();
        AgentEvent event = new AgentEvent(EventType.FINAL_ANSWER, "mock.answer", null, started, 1);
        return new AgentRun(UUID.randomUUID().toString(), task.id(), RunStatus.SUCCESS,
                "Mock Agent completed: " + task.instruction(), List.of(event), started, 1, 0, null);
    }
}
