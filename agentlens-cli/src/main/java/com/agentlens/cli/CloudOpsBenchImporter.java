package com.agentlens.cli;

import com.agentlens.eval.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * P1-C: imports LLM4Ops/Cloud-OpsBench (MIT, FSE 2026 "Freezing the Crime
 * Scene") into AgentLens scenarios - 754 real K8s RCA fault cases that replay
 * from snapshots, no live cluster needed.
 *
 * <pre>
 * benchmark/&lt;app&gt;/&lt;fault_taxonomy&gt;/&lt;case_id&gt;/metadata.json  - query/difficulty/root cause
 * .../tool_cache.json    - deterministic diagnostic tool-call replay ("Tool:{args}": output)
 * .../raw_data/*.json    - alert / k8s_states / logs snapshots
 * </pre>
 *
 * Mapping (almost 1:1 with the Scenario dialect):
 * metadata.query -> instruction, result.root_cause (+ fault_object) ->
 * root_cause golden answer, tool_cache tool names -> allowed_tools, snapshot
 * facts -> context. Output is a plain Scenario array readable by
 * {@link ScenarioLoader}, so `run --scenarios` consumes it directly.
 */
public final class CloudOpsBenchImporter {

    /** Not a tool: bookkeeping key present in every tool_cache.json. */
    private static final String NON_TOOL_KEY = "collection_timestamp";
    private static final int MAX_TOOLS_PER_CASE = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One imported case plus machine-readable provenance. */
    public record ImportedCase(String id, String namespace, String difficulty,
                               String faultTaxonomy, Scenario scenario) {
    }

    /** Summary of one import run (counts + machine-readable rollups). */
    public record ImportSummary(int imported, int skipped, List<String> skipReasons,
                                Map<String, Integer> byNamespace, Map<String, Integer> byDifficulty) {
        public ImportSummary {
            skipReasons = skipReasons == null ? List.of() : List.copyOf(skipReasons);
            byNamespace = byNamespace == null ? Map.of() : Map.copyOf(byNamespace);
            byDifficulty = byDifficulty == null ? Map.of() : Map.copyOf(byDifficulty);
        }
    }

    /**
     * Walks {@code root/benchmark/<app>/<taxonomy>/<case>/metadata.json} and
     * maps every readable case to a Scenario. Broken or blank cases are
     * skipped with a reason instead of failing the whole import.
     */
    public ImportSummary importRoot(Path root, int limit, List<Scenario> out) throws IOException {
        Path benchmark = root.resolve("benchmark");
        if (!Files.isDirectory(benchmark)) {
            throw new IOException("not a Cloud-OpsBench checkout (missing benchmark/): "
                    + root.toAbsolutePath());
        }
        List<Path> metadataFiles;
        try (Stream<Path> walk = Files.walk(benchmark, 4)) {
            metadataFiles = walk.filter(p -> "metadata.json".equals(String.valueOf(p.getFileName())))
                    .sorted()
                    .toList();
        }
        List<ImportedCase> imported = new ArrayList<>();
        List<String> skipReasons = new ArrayList<>();
        for (Path metadata : metadataFiles) {
            if (limit > 0 && imported.size() >= limit) break;
            Path caseDir = metadata.getParent();
            if (caseDir == null) {
                skipReasons.add(metadata + ": case directory unresolved");
                continue;
            }
            try {
                ImportedCase c = importCase(caseDir);
                imported.add(c);
                out.add(c.scenario());
            } catch (Exception e) {
                skipReasons.add(relativizeCase(root, caseDir) + ": " + e.getMessage());
            }
        }
        Map<String, Integer> byNamespace = new TreeMap<>();
        Map<String, Integer> byDifficulty = new TreeMap<>();
        for (ImportedCase c : imported) {
            byNamespace.merge(c.namespace(), 1, Integer::sum);
            byDifficulty.merge(c.difficulty(), 1, Integer::sum);
        }
        return new ImportSummary(imported.size(), skipReasons.size(),
                List.copyOf(skipReasons), byNamespace, byDifficulty);
    }

    /** Maps one case directory to a scenario (throws with a reason when unusable). */
    ImportedCase importCase(Path caseDir) throws IOException {
        Path metadataPath = caseDir.resolve("metadata.json");
        if (!Files.isRegularFile(metadataPath)) {
            throw new IOException("metadata.json missing");
        }
        JsonNode meta = JSON.readTree(metadataPath.toFile());
        String query = text(meta, "query");
        if (query == null) throw new IOException("metadata.query blank");
        JsonNode result = meta.get("result");
        if (result == null || !result.isObject()) throw new IOException("metadata.result missing");
        String rootCause = text(result, "root_cause");
        String faultObject = text(result, "fault_object");
        if (rootCause == null) throw new IOException("metadata.result.root_cause blank");

        String namespace = text(meta, "namespace") == null ? "unknown" : text(meta, "namespace");
        String difficulty = text(meta, "difficulty") == null ? "unknown" : text(meta, "difficulty");
        String taxonomy = text(result, "fault_taxonomy") == null ? "unknown" : text(result, "fault_taxonomy");

        String caseId = childName(caseDir);
        Path appDir = caseDir.getParent();
        String app = appDir == null ? namespace : childName(appDir.getParent());
        String slug = taxonomy.toLowerCase().replace("_fault", "").replace('_', '-');
        String id = "opsbench-" + app + "-" + slug + "-" + caseId;

        List<String> tools = toolNames(caseDir);
        String golden = faultObject == null ? rootCause : faultObject + ": " + rootCause;

        Scenario scenario = new Scenario(id, taxonomy, query, List.of(), golden,
                tools, List.of(),
                List.of("final answer states the root cause '" + rootCause + "'"),
                maxSteps(difficulty), "correct", "mock", null, query, null);
        return new ImportedCase(id, namespace, difficulty, taxonomy, scenario);
    }

    /** Distinct diagnostic tool names from tool_cache.json ("Tool:{args}": output). */
    private List<String> toolNames(Path caseDir) throws IOException {
        Path cache = caseDir.resolve("tool_cache.json");
        if (!Files.isRegularFile(cache)) {
            throw new IOException("tool_cache.json missing (no deterministic replay)");
        }
        JsonNode node = JSON.readTree(cache.toFile());
        if (!node.isObject()) throw new IOException("tool_cache.json is not an object");
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(key -> {
            if (key.isBlank() || key.equals(NON_TOOL_KEY)) return;
            String name = key.split(":", 2)[0].trim();
            if (!name.isEmpty()) names.add(name);
        });
        if (names.isEmpty()) throw new IOException("tool_cache.json has no tool entries");
        return names.stream().limit(MAX_TOOLS_PER_CASE).toList();
    }

    /** Bounded step budget by dataset difficulty (hard cases get more room). */
    private static int maxSteps(String difficulty) {
        return switch (difficulty == null ? "" : difficulty) {
            case "easy" -> 8;
            case "hard" -> 15;
            default -> 12;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : String.valueOf(v.asText()).isBlank()
                ? null : v.asText();
    }

    /** Null-safe file name (getFileName() is nullable for root paths). */
    private static String childName(Path path) {
        if (path == null) return "unknown";
        Path name = path.getFileName();
        return name == null ? "unknown" : name.toString();
    }

    private static String relativizeCase(Path root, Path caseDir) {
        try {
            return root.toAbsolutePath().relativize(caseDir.toAbsolutePath()).toString();
        } catch (Exception e) {
            return String.valueOf(caseDir);
        }
    }
}
