package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLoaderTest {
    @Test
    void loadsRepositoryScenarios() throws Exception {
        var scenarios = new ScenarioLoader().load(Path.of("..", "data", "scenarios", "ops-scenarios.json"));
        assertEquals(20, scenarios.size());
    }

    @Test
    void scenarioConvertsToAgentTaskWithBehaviorContext() throws Exception {
        var scenarios = new ScenarioLoader().load(Path.of("..", "data", "scenarios", "ops-scenarios.json"));
        var task = scenarios.get(0).toTask();
        assertEquals(scenarios.get(0).id(), task.id());
        assertEquals("correct", task.context().get("agentBehavior"));
        assertTrue(task.context().containsKey("forbiddenTools"));
    }

    @Test
    void duplicateIdsRejected() {
        var loader = new ScenarioLoader();
        var json = "[" + scenarioJson("a") + "," + scenarioJson("a") + "]";
        assertThrows(Exception.class, () -> loader.load(writeTemp(json)));
    }

    @Test
    void unknownBehaviorRejected() {
        var loader = new ScenarioLoader();
        var json = "[" + scenarioJson("a").replace("\"correct\"", "\"hacked\"") + "]";
        assertThrows(Exception.class, () -> loader.load(writeTemp(json)));
    }

    @Test
    void defaultsToCorrectBehaviorWhenAbsent() throws Exception {
        var loader = new ScenarioLoader();
        var json = "[" + scenarioJson("a").replace(",\"agent_behavior\":\"correct\"", "") + "]";
        var scenarios = loader.load(writeTemp(json));
        assertEquals("correct", scenarios.get(0).agentBehavior());
    }

    @Test
    void externalScenarioWithoutAlertConvertsToTask() throws Exception {
        // Regression: real-agent scenarios (dsh) have no alert/root_cause; toTask used to
        // put null values into the context, and AgentTask's Map.copyOf then threw NPE.
        var loader = new ScenarioLoader();
        String json = "[{\"id\":\"ext\",\"domain\":\"terminal\",\"instruction\":\"do it\","
                + "\"allowed_tools\":[\"read\"],\"forbidden_tools\":[],\"success_assertions\":[\"x\"],"
                + "\"max_steps\":5,\"agent_behavior\":\"external\",\"adapter\":\"dsh\","
                + "\"workdir\":\"D:/tmp/x\"}]";
        var task = loader.load(writeTemp(json)).get(0).toTask();
        assertEquals("do it", task.instruction());
        assertEquals("D:/tmp/x", task.context().get("workdir"));
        assertFalse(task.context().containsKey("alert"));
    }

    private static String scenarioJson(String id) {
        return "{\"id\":\"" + id + "\",\"domain\":\"test\",\"alert\":\"A\",\"symptoms\":[\"s\"],"
                + "\"root_cause\":\"r\",\"allowed_tools\":[\"t\"],\"forbidden_tools\":[\"f\"],"
                + "\"success_assertions\":[\"x\"],\"max_steps\":5,\"agent_behavior\":\"correct\"}";
    }

    private static java.nio.file.Path writeTemp(String json) throws Exception {
        var file = java.nio.file.Files.createTempFile("scenarios", ".json");
        java.nio.file.Files.writeString(file, json);
        return file;
    }
}
