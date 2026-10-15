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
        assertDoesNotThrow(() -> AgentLensApp.validate("serve",
                Map.of("port", "18080", "artifacts", scenarioFile().toString())));
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
    void approvedBaselineAndApproveValidation() throws Exception {
        String s = scenarioFile().toString();
        // approved artifact must exist when referenced
        ConfigException e = assertThrows(ConfigException.class, () -> AgentLensApp.validate("compare",
                Map.of("scenarios", s, "approved", "no/such/baseline.json")));
        assertTrue(e.getMessage().contains("approved-baseline artifact not found"), e.getMessage());
        // existing artifact passes
        Path baseline = tempDir.resolve("approved-baseline.json");
        java.nio.file.Files.writeString(baseline, "{}");
        assertDoesNotThrow(() -> AgentLensApp.validate("compare",
                Map.of("scenarios", s, "approved", baseline.toString())));
        // approve requires --compare (existing) and --out
        ConfigException missing = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("approve", Map.of()));
        assertTrue(missing.getMessage().contains("approve requires --compare"), missing.getMessage());
        ConfigException noSource = assertThrows(ConfigException.class, () -> AgentLensApp.validate("approve",
                Map.of("compare", "no/such/artifact.json", "out", "x.json")));
        assertTrue(noSource.getMessage().contains("compare artifact not found"), noSource.getMessage());
        ConfigException noOut = assertThrows(ConfigException.class, () -> AgentLensApp.validate("approve",
                Map.of("compare", baseline.toString())));
        assertTrue(noOut.getMessage().contains("approve requires --out"), noOut.getMessage());
        assertDoesNotThrow(() -> AgentLensApp.validate("approve",
                Map.of("compare", baseline.toString(), "out", tempDir.resolve("out.json").toString())));
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

    @Test
    void serveArtifactsMustExist() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("serve",
                        Map.of("port", "18080", "artifacts", "missing.json,bad.json")));
        assertTrue(e.getMessage().contains("artifact not found"), e.getMessage());
    }

    @Test
    void serveScenariosOutMustNotBeADirectory() {
        assertDoesNotThrow(() -> AgentLensApp.validate("serve",
                Map.of("port", "18080", "scenarios-out",
                        tempDir.resolve("saved.json").toString())));
        ConfigException e = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("serve",
                        Map.of("port", "18080", "scenarios-out", tempDir.toString())));
        assertTrue(e.getMessage().contains("must be a .json file"), e.getMessage());
    }

    @Test
    void scoresRequiresExactlyOneOfOutOrReduce() throws Exception {
        assertDoesNotThrow(() -> AgentLensApp.validate("scores",
                Map.of("out", tempDir.resolve("scores.json").toString())));
        Path reduce = tempDir.resolve("scores.json");
        java.nio.file.Files.writeString(reduce, "{}");
        assertDoesNotThrow(() -> AgentLensApp.validate("scores",
                Map.of("reduce", reduce.toString(), "at-least", "2")));
        ConfigException both = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("scores", Map.of(
                        "out", "a.json", "reduce", "b.json")));
        assertTrue(both.getMessage().contains("exactly one of"), both.getMessage());
        ConfigException neither = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("scores", Map.of()));
        assertTrue(neither.getMessage().contains("exactly one of"), neither.getMessage());
        ConfigException missing = assertThrows(ConfigException.class,
                () -> AgentLensApp.validate("scores", Map.of("reduce", "missing-scores.json")));
        assertTrue(missing.getMessage().contains("scores artifact not found"), missing.getMessage());
    }
}
