package com.agentlens.adapters.dsh;

import com.agentlens.core.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the per-side launch configuration of {@link DshAgentAdapter}
 * (ROADMAP 7.2 待办: candidate 侧换模型/换配置): the "dshArgs" context key must
 * inject extra CLI args between "--profile headless" and the instruction, and
 * "env.*" context keys must become child-process environment variables.
 */
class DshLaunchCommandTest {

    /** Position of "--profile" is OS-dependent (Windows prepends cmd /c). */
    private static int profileIndex(List<String> command) {
        return command.lastIndexOf("--profile");
    }

    @Test
    void defaultCommandEndsWithInstructionAfterHeadless() {
        var task = new AgentTask("t1", "do the thing", Map.of(), 5);
        List<String> command = DshAgentAdapter.commandFor(task);
        int i = profileIndex(command);
        assertEquals("headless", command.get(i + 1));
        assertEquals("do the thing", command.get(command.size() - 1));
        // Nothing between headless and the instruction.
        assertEquals(i + 2, command.size() - 1);
    }

    @Test
    void dshArgsStringInsertsExtraArgsBeforeInstruction() {
        var task = new AgentTask("t1", "do the thing",
                Map.of("dshArgs", "--model qwen2.5:3b"), 5);
        List<String> command = DshAgentAdapter.commandFor(task);
        int i = profileIndex(command);
        assertEquals("headless", command.get(i + 1));
        assertEquals(List.of("--model", "qwen2.5:3b"),
                command.subList(i + 2, command.size() - 1));
        assertEquals("do the thing", command.get(command.size() - 1));
    }

    @Test
    void dshArgsListIsUsedTokenByToken() {
        var task = new AgentTask("t1", "do the thing",
                Map.of("dshArgs", List.of("--config", "small-model.json")), 5);
        List<String> command = DshAgentAdapter.commandFor(task);
        int i = profileIndex(command);
        assertEquals(List.of("--config", "small-model.json"),
                command.subList(i + 2, command.size() - 1));
    }

    @Test
    void blankDshArgsMeansNoExtraArgs() {
        var task = new AgentTask("t1", "do the thing", Map.of("dshArgs", "   "), 5);
        List<String> command = DshAgentAdapter.commandFor(task);
        int i = profileIndex(command);
        assertEquals(i + 2, command.size() - 1);
    }

    @Test
    void dshCommandContextOverridesLauncher() {
        var task = new AgentTask("t1", "do the thing",
                Map.of("dshCommand", "my-dsh.cmd"), 5);
        List<String> command = DshAgentAdapter.commandFor(task);
        int i = profileIndex(command);
        // The launcher token sits directly before --profile (after optional cmd /c).
        assertEquals("my-dsh.cmd", command.get(i - 1));
    }

    @Test
    void envPrefixedContextKeysBecomeProcessEnvironment() {
        Map<String, String> env = DshAgentAdapter.extraEnv(Map.of(
                "env.DSH_MODEL", "qwen2.5:3b",
                "env.DSH_API_KEY", "sk-test",
                "workdir", "D:/tmp/x",
                "timeoutSec", 42));
        assertEquals(Map.of("DSH_MODEL", "qwen2.5:3b", "DSH_API_KEY", "sk-test"), env);
    }

    @Test
    void blankEnvValuesAreDroppedAndNullContextYieldsEmpty() {
        var ctx = new java.util.LinkedHashMap<String, Object>();
        ctx.put("env.EMPTY", "");
        ctx.put("env.BLANK", "   ");
        assertTrue(DshAgentAdapter.extraEnv(ctx).isEmpty());
        assertTrue(DshAgentAdapter.extraEnv(null).isEmpty());
    }
}
