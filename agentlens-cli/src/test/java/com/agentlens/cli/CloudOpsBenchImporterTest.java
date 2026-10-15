package com.agentlens.cli;

import com.agentlens.eval.Scenario;
import com.agentlens.eval.ScenarioLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1-C: Cloud-OpsBench snapshot cases map 1:1 into loadable scenarios. */
class CloudOpsBenchImporterTest {

    @TempDir
    Path root;

    @Test
    void mapsMetadataAndToolCacheIntoLoadableScenario() throws Exception {
        Path caseDir = fixture("boutique", "Admission_Fault", "1", """
                {"namespace": "boutique", "query": "Partial Service Unreachability.",
                 "difficulty": "medium",
                 "result": {"fault_taxonomy": "Admission_Fault", "fault_object": "app/adservice",
                            "root_cause": "missing_service_account"}}
                """, """
                {"collection_timestamp": "2025-11-28T15:33:20.568512",
                 "GetResources:{\\"resource_type\\":\\"pods\\"}": "pod output",
                 "GetErrorLogs:{\\"service\\":\\"adservice\\"}": "err log"}
                """);
        var importer = new CloudOpsBenchImporter();
        var imported = importer.importCase(caseDir);
        assertEquals("opsbench-boutique-admission-1", imported.scenario().id());
        assertEquals("Partial Service Unreachability.", imported.scenario().alert());
        assertEquals("Admission_Fault", imported.scenario().domain());
        assertEquals("app/adservice: missing_service_account", imported.scenario().rootCause());
        assertEquals("Partial Service Unreachability.", imported.scenario().instruction());
        assertEquals(12, imported.scenario().maxSteps());
        assertEquals(List.of("GetResources", "GetErrorLogs"), imported.scenario().allowedTools());
        assertTrue(imported.scenario().successAssertions().get(0).contains("missing_service_account"));

        // the whole point of P1-C: the file ScenarioLoader reads back must round-trip
        List<Scenario> out = new ArrayList<>();
        importer.importRoot(root, 0, out);
        Path file = root.resolve("opsbench-scenarios.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        List<Scenario> reloaded = new ScenarioLoader().load(file);
        assertEquals(1, reloaded.size());
        assertEquals(imported.scenario().id(), reloaded.get(0).id());
        assertEquals(imported.scenario().rootCause(), reloaded.get(0).rootCause());
    }

    @Test
    void difficultyDrivesMaxStepsAndBrokenCasesAreSkipped() throws Exception {
        trainticketCase("trainticket", "Runtime_Fault", "7", "hard", "oom_after_deploy");
        trainticketCase("trainticket", "Runtime_Fault", "8", "easy", "connection_pool_exhausted");
        // broken: no result block -> skipped with a reason, others still import
        Files.createDirectories(root.resolve("benchmark/trainticket/Runtime_Fault/9"));
        Files.writeString(root.resolve("benchmark/trainticket/Runtime_Fault/9/metadata.json"),
                "{\"namespace\": \"trainticket\", \"query\": \"x\"}");
        var importer = new CloudOpsBenchImporter();
        List<Scenario> out = new ArrayList<>();
        var summary = importer.importRoot(root, 0, out);
        assertEquals(2, summary.imported());
        assertEquals(1, summary.skipped());
        assertTrue(summary.skipReasons().get(0).contains("metadata.result missing"));
        assertEquals(15, out.get(0).maxSteps());
        assertEquals(8, out.get(1).maxSteps());
        assertEquals(2, summary.byNamespace().get("trainticket"));
        assertEquals(1, summary.byDifficulty().get("hard"));
        // limit caps the import without touching the corpus
        List<Scenario> capped = new ArrayList<>();
        assertEquals(1, importer.importRoot(root, 1, capped).imported());
    }

    @Test
    void missingBenchmarkDirIsRejected() {
        var importer = new CloudOpsBenchImporter();
        List<Scenario> out = new ArrayList<>();
        IOException e = assertThrows(IOException.class,
                () -> importer.importRoot(root.resolve("nowhere"), 0, out));
        assertTrue(e.getMessage().contains("benchmark/"), e.getMessage());
    }

    /** One-case fixture with explicit metadata + tool cache. */
    private Path fixture(String app, String taxonomy, String id, String metadata, String toolCache)
            throws IOException {
        return writeCase(root, app, taxonomy, id, metadata, toolCache);
    }

    private Path trainticketCase(String app, String taxonomy, String id, String difficulty,
                                 String cause) throws IOException {
        return writeCase(root, app, taxonomy, id, defaultMetadata(difficulty, cause),
                defaultToolCache());
    }

    private static String defaultMetadata(String difficulty, String cause) {
        return "{\"namespace\": \"trainticket\", \"query\": \"Service degraded.\", \"difficulty\": \""
                + difficulty + "\", \"result\": {\"fault_taxonomy\": \"Runtime_Fault\","
                + " \"fault_object\": \"app/user-service\", \"root_cause\": \"" + cause + "\"}}";
    }

    private static String defaultToolCache() {
        return "{\"collection_timestamp\": \"t\", \"GetResources:{\\\"a\\\":1}\": \"out\","
                + " \"GetAlerts:{}\": \"alerts\"}";
    }

    private static Path writeCase(Path base, String app, String taxonomy, String id,
                                  String metadata, String toolCache) throws IOException {
        Path dir = base.resolve("benchmark/" + app + "/" + taxonomy + "/" + id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("metadata.json"), metadata);
        Files.writeString(dir.resolve("tool_cache.json"), toolCache);
        return dir;
    }
}
