package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ScenarioLoaderTest {
    @Test
    void loadsRepositoryScenarios() throws Exception {
        var scenarios = new ScenarioLoader().load(Path.of("..", "data", "scenarios", "ops-scenarios.json"));
        assertFalse(scenarios.isEmpty());
    }
}
