package com.agentlens.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROADMAP P2-5 runtime configuration validation: invalid flags fail fast with
 * actionable messages instead of silently falling back to defaults.
 */
class ConfigValidationTest {

    @TempDir
    Path tempDir;

    private Path scenarioFile;

    private Path scenarioFile() throws Exception {
        if (scenarioFile == null) {
            scenarioFile = tempDir.resolve("scenarios.json");
            java.nio.file.Files.writeString(scenarioFile, "[]");
        }
        return scenarioFile;
    }

    @Test
    void validRunConfigPasses() throws Exception {
        String s = scenarioFile().toString();
        assertDoesNotThrow(() -> AgentLensApp.validate("run", Map.of("scenarios", s)));
        assertDoesNotThrow(() -> AgentLensApp.validate("run",
                Map.of("scenarios", s, "repeats", "3", "adapter", "mock")));
        assertDoesNotThrow(() -> AgentLensApp.validate("serve", Map.of("port", "18080")));
        assertDoesNotThrow(() -> AgentLensApp.validate("demo", Map.of()));
    }

    @Test
    void missingScenariosFileFails() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("run", Map.of("scenarios", "no/such/file.json")));
        assertTrue(e.getMessage().contains("scenarios file not found"));
        assertTrue(e.getMessage().contains("no" + java.io.File.separator + "such" + java.io.File.separator + "file.json"));
    }

    @Test
    void garbageIntegersAreRejectedInsteadOfSilentlyDefaulted() {
        assertEquals(1, AgentLensApp.requireInt(Map.of(), "repeats", 1));
        assertEquals(5, AgentLensApp.requireInt(Map.of("repeats", "5"), "repeats", 1));
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.requireInt(Map.of("repeats", "abc"), "repeats", 1));
        assertTrue(e.getMessage().contains("repeats must be an integer"));
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    void repeatsMustBePositive() throws Exception {
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("run", Map.of("scenarios", scenarioFile().toString(), "repeats", "0")));
        assertTrue(e.getMessage().contains("repeats must be >= 1"));
    }

    @Test
    void portMustBeInValidRange() {
        ConfigException low = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("serve", Map.of("port", "0")));
        assertTrue(low.getMessage().contains("1-65535"));
        ConfigException high = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("serve", Map.of("port", "70000")));
        assertTrue(high.getMessage().contains("1-65535"));
    }

    @Test
    void otelEndpointMustBeHttpUrl() throws Exception {
        String s = scenarioFile().toString();
        assertDoesNotThrow(() -> AgentLensApp.validate("run",
                Map.of("scenarios", s, "otel-endpoint", "http://127.0.0.1:4318/v1/traces")));
        ConfigException bad = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("run",
                        Map.of("scenarios", s, "otel-endpoint", "ftp://x/y")));
        assertTrue(bad.getMessage().contains("otel-endpoint must be an http(s) URL"));
        ConfigException garbage = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("run",
                        Map.of("scenarios", s, "otel-endpoint", "::not a uri::")));
        assertTrue(garbage.getMessage().contains("otel-endpoint must be an http(s) URL"));
    }

    @Test
    void unknownAdapterNamesListAvailableAdapters() throws Exception {
        ConfigException e = assertThrows(ConfigException.class, () -> AgentLensApp.validate("run",
                Map.of("scenarios", scenarioFile().toString(), "adapter", "no-such-adapter")));
        assertTrue(e.getMessage().contains("unknown adapter 'no-such-adapter'"));
        assertTrue(e.getMessage().contains("available"));
    }

    @Test
    void judgeUrlMustBeHttpUrl() throws Exception {
        ConfigException e = assertThrows(ConfigException.class, () -> AgentLensApp.validate("run",
                Map.of("scenarios", scenarioFile().toString(),
                        "judge", "ollama", "judge-url", "localhost:11434")));
        assertTrue(e.getMessage().contains("judge-url must be an http(s) URL"));
    }

    @Test
    void showAndReportRequireExistingDatabase() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("show", Map.of("db", tempDir.resolve("missing.db").toString())));
        assertTrue(e.getMessage().contains("database not found"));
        assertDoesNotThrow(() -> AgentLensApp.validate("show", Map.of()));
    }

    @Test
    void judgeCalibrateSamplesMustBePositive() throws Exception {
        assertDoesNotThrow(() -> AgentLensApp.validate("judge-calibrate", Map.of("samples", "2")));
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("judge-calibrate", Map.of("samples", "x")));
        assertTrue(e.getMessage().contains("samples must be an integer"));
    }
}
