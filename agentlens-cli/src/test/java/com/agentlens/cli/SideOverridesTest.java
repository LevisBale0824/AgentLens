package com.agentlens.cli;

import com.agentlens.core.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the compare per-side override plumbing (ROADMAP 7.2 待办:
 * candidate 侧换模型/配置): spec parsing and task-context merging.
 */
class SideOverridesTest {

    @Test
    void parsesKeyValuePairs() {
        Map<String, String> overrides = AgentLensApp.parseOverrides(
                "dshArgs=--model qwen2.5:3b,timeoutSec=120");
        assertEquals("--model qwen2.5:3b", overrides.get("dshArgs"));
        assertEquals("120", overrides.get("timeoutSec"));
        assertEquals(2, overrides.size());
    }

    @Test
    void valueMayContainEqualsSign() {
        Map<String, String> overrides = AgentLensApp.parseOverrides("prompt=a=b=c");
        assertEquals("a=b=c", overrides.get("prompt"));
    }

    @Test
    void tokenWithoutEqualsBecomesBooleanFlag() {
        Map<String, String> overrides = AgentLensApp.parseOverrides("verbose,dshArgs=--fast");
        assertEquals("true", overrides.get("verbose"));
        assertEquals("--fast", overrides.get("dshArgs"));
    }

    @Test
    void blankSpecAndNullProduceEmptyMap() {
        assertTrue(AgentLensApp.parseOverrides(null).isEmpty());
        assertTrue(AgentLensApp.parseOverrides("  ").isEmpty());
        assertTrue(AgentLensApp.parseOverrides(" , ,").isEmpty());
    }

    @Test
    void emptyOverridesReturnSameTaskInstance() {
        AgentTask task = new AgentTask("t1", "do it", Map.of("workdir", "D:/tmp"), 5);
        assertSame(task, AgentLensApp.withOverrides(task, Map.of()));
    }

    @Test
    void overridesWinOverScenarioContextAndKeepOtherEntries() {
        AgentTask task = new AgentTask("t1", "do it",
                Map.of("workdir", "D:/tmp", "timeoutSec", 900), 5);
        AgentTask merged = AgentLensApp.withOverrides(task,
                Map.of("dshArgs", "--model qwen2.5:3b", "env.DSH_MODEL", "qwen2.5:3b"));
        assertEquals("--model qwen2.5:3b", merged.context().get("dshArgs"));
        assertEquals("qwen2.5:3b", merged.context().get("env.DSH_MODEL"));
        // Scenario-provided entries survive the merge.
        assertEquals("D:/tmp", merged.context().get("workdir"));
        // Task identity is preserved.
        assertEquals("t1", merged.id());
        assertEquals("do it", merged.instruction());
        assertEquals(5, merged.maxSteps());
    }

    @Test
    void overrideCanReplaceExistingScenarioEntry() {
        AgentTask task = new AgentTask("t1", "do it", Map.of("timeoutSec", 900), 5);
        AgentTask merged = AgentLensApp.withOverrides(task, Map.of("timeoutSec", "60"));
        assertEquals("60", String.valueOf(merged.context().get("timeoutSec")));
    }
}
