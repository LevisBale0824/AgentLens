package com.agentlens.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusLoaderTest {

    @TempDir
    Path temp;

    private Path write(String json) throws IOException {
        Path file = temp.resolve("corpus.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void loadsVersionedEntriesWithContext() throws IOException {
        Path file = write("""
                {"version": 3, "entries": [
                  {"id": "e1", "behavior": "correct_detailed", "expectedPass": true,
                   "instruction": "diagnose the crash loop",
                   "context": {"allowedTools": ["query_prometheus", "read_logs"]}}
                ]}
                """);
        CorpusLoader.Corpus corpus = new CorpusLoader().load(file);
        assertEquals(3, corpus.version());
        assertEquals(1, corpus.entries().size());
        CorpusLoader.Entry e = corpus.entries().get(0);
        assertEquals("e1", e.id());
        assertEquals("correct_detailed", e.behavior());
        assertTrue(e.expectedPass());
        assertEquals("diagnose the crash loop", e.instruction());
        assertEquals(Map.of("allowedTools", List.of("query_prometheus", "read_logs")), e.context());
    }

    @Test
    void rejectsVersionBelowOne() throws IOException {
        Path file = write("""
                {"version": 0, "entries": [
                  {"id": "e1", "behavior": "correct", "expectedPass": true, "instruction": "x"}
                ]}
                """);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CorpusLoader().load(file));
        assertTrue(ex.getMessage().contains("version"));
    }

    @Test
    void rejectsEmptyEntries() throws IOException {
        Path file = write("{\"version\": 1, \"entries\": []}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CorpusLoader().load(file));
        assertTrue(ex.getMessage().contains("no entries"));
    }

    @Test
    void rejectsDuplicateIds() throws IOException {
        Path file = write("""
                {"version": 1, "entries": [
                  {"id": "e1", "behavior": "correct", "expectedPass": true, "instruction": "a"},
                  {"id": "e1", "behavior": "loop", "expectedPass": false, "instruction": "b"}
                ]}
                """);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new CorpusLoader().load(file));
        assertTrue(ex.getMessage().contains("duplicate corpus entry id: e1"));
    }

    @Test
    void rejectsMissingBehaviorOrInstruction() throws IOException {
        Path noBehavior = write("""
                {"version": 1, "entries": [
                  {"id": "e1", "expectedPass": true, "instruction": "x"}
                ]}
                """);
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                () -> new CorpusLoader().load(noBehavior));
        assertTrue(ex1.getMessage().contains("missing behavior"));

        Path noInstruction = write("""
                {"version": 1, "entries": [
                  {"id": "e2", "behavior": "correct", "expectedPass": true}
                ]}
                """);
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                () -> new CorpusLoader().load(noInstruction));
        assertTrue(ex2.getMessage().contains("missing instruction"));
    }

    @Test
    void shippedCorpusIsValidAndBehaviorsKnown() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path corpusFile = null;
        while (dir != null) {
            Path candidate = dir.resolve("data").resolve("judge-calibration-corpus.json");
            if (Files.isRegularFile(candidate)) {
                corpusFile = candidate;
                break;
            }
            dir = dir.getParent();
        }
        if (corpusFile == null) return; // corpus not checked out (e.g. artifact-only build)
        var corpus = new CorpusLoader().load(corpusFile);
        assertTrue(corpus.entries().size() >= 10, "corpus should cover all behavior classes");
        long positives = corpus.entries().stream().filter(CorpusLoader.Entry::expectedPass).count();
        assertTrue(positives >= 1, "corpus needs at least one expected-pass entry");
        for (var e : corpus.entries()) {
            assertTrue(com.agentlens.adapters.MockAgentAdapter.KNOWN_BEHAVIORS.contains(e.behavior()),
                    "unknown behavior in shipped corpus: " + e.id() + " -> " + e.behavior());
            assertTrue(e.context() != null && e.context().containsKey("allowedTools"),
                    "entry needs tool context: " + e.id());
        }
    }
}
