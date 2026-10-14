package com.agentlens.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * P0-D: the approved-baseline gate artifact (Langfuse double-publish model).
 * Publishing a comparison is step one; a human reviews the result and then
 * promotes it to the approved baseline (step two). Release gates then compare
 * against the frozen artifact instead of re-running a live baseline, and the
 * artifact pins everything that would otherwise silently drift:
 * dataset content (sha256), the evaluator set, repeats, and per-case pass
 * rates - a changed dataset, a changed evaluator set, or any missing case
 * fails the gate instead of comparing apples to oranges.
 */
public final class ApprovedBaseline {
    /** Artifact schema version; loaders reject anything they do not understand. */
    public static final int FORMAT_VERSION = 1;

    /** JSON codec for the artifact (pretty-printed, human-reviewable). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApprovedBaseline() { }

    /**
     * @param casePassRates taskId -> pass rate the approved run achieved (0..1)
     */
    public record Baseline(int formatVersion, String agentName, String datasetSha256,
                           List<String> evaluatorNames, int repeats,
                           double passPowK, double avgPassRate,
                           Map<String, Double> casePassRates,
                           String approvedBy, String createdAt) {
        public Baseline {
            evaluatorNames = evaluatorNames == null ? List.of() : List.copyOf(evaluatorNames);
            casePassRates = casePassRates == null ? Map.of() : Map.copyOf(casePassRates);
        }
    }

    /** Writes the artifact, creating parent directories. */
    public static void write(Baseline baseline, Path out) throws IOException {
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(baseline));
    }

    /** Loads and validates the artifact (structural checks + schema version). */
    public static Baseline load(Path in) throws IOException {
        Baseline baseline;
        try {
            baseline = JSON.readValue(Files.readString(in), Baseline.class);
        } catch (com.fasterxml.jackson.databind.exc.ValueInstantiationException e) {
            // record-constructor validation failures surface with their own message
            throw new IllegalArgumentException(String.valueOf(e.getCause()), e);
        }
        if (baseline.formatVersion() != FORMAT_VERSION)
            throw new IllegalArgumentException(
                    "unsupported approved-baseline formatVersion: " + baseline.formatVersion()
                            + " (expected " + FORMAT_VERSION + ")");
        if (baseline.datasetSha256() == null || baseline.datasetSha256().isBlank())
            throw new IllegalArgumentException("approved baseline is missing datasetSha256");
        if (baseline.evaluatorNames().isEmpty())
            throw new IllegalArgumentException("approved baseline is missing evaluatorNames");
        if (baseline.casePassRates().isEmpty())
            throw new IllegalArgumentException("approved baseline is missing casePassRates");
        return baseline;
    }

    /** SHA-256 of a file, hex encoded - the dataset pin. */
    public static String sha256Hex(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Builds the baseline from a candidate side of a reviewed comparison. */
    public static Baseline fromCandidate(String agentName, String datasetSha256,
                                         List<String> evaluatorNames, int repeats,
                                         PassK.Summary candidate, String approvedBy) {
        Map<String, Double> cases = new java.util.LinkedHashMap<>();
        candidate.perScenario().forEach(s -> cases.put(s.scenarioId(), s.passRate()));
        return new Baseline(FORMAT_VERSION, agentName, datasetSha256, evaluatorNames, repeats,
                candidate.passPowK(), candidate.avgPassRate(), cases, approvedBy,
                Instant.now().toString());
    }
}
