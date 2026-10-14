package com.agentlens.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a versioned judge-calibration corpus (board P0-A). Replaces the
 * hardcoded six-behavior default with a JSON file so calibration samples can
 * carry their own instruction, tool context, and ground truth, and so corpus
 * versions are recorded in results (kappa comparisons across judge models and
 * prompts only mean something against a pinned corpus).
 *
 * <p>Format:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "entries": [
 *     {"id": "correct-detailed-1", "behavior": "correct_detailed",
 *      "expectedPass": true, "instruction": "...",
 *      "context": {"allowedTools": ["query_prometheus", "read_logs"]}}
 *   ]
 * }
 * }</pre>
 *
 * Structural validation only (version, unique ids, non-blank fields); behavior
 * semantics are validated at the call site against the adapter's known set.
 */
public final class CorpusLoader {

    /** One calibration case: behavior drives the mock agent, expectedPass is ground truth. */
    public record Entry(String id, String behavior, boolean expectedPass,
                        String instruction, Map<String, Object> context) {
        public Entry {
            // Null context values would NPE in Map.copyOf and later in AgentTask's
            // Map.copyOf - drop them the same way Scenario.toTask learned to.
            var cleaned = new java.util.LinkedHashMap<String, Object>();
            if (context != null) {
                for (var kv : context.entrySet()) {
                    if (kv.getValue() != null) cleaned.put(kv.getKey(), kv.getValue());
                }
            }
            context = Map.copyOf(cleaned);
        }
    }

    /** A pinned corpus: version plus its entries. */
    public record Corpus(int version, List<Entry> entries) {
        public Corpus {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public Corpus load(Path path) throws IOException {
        Corpus corpus = mapper.readValue(path.toFile(), new TypeReference<>() {});
        validate(corpus);
        return corpus;
    }

    private static void validate(Corpus corpus) {
        if (corpus.version() < 1)
            throw new IllegalArgumentException("corpus version must be >= 1, got: " + corpus.version());
        if (corpus.entries() == null || corpus.entries().isEmpty())
            throw new IllegalArgumentException("corpus has no entries");
        Set<String> ids = new HashSet<>();
        for (Entry e : corpus.entries()) {
            if (e.id() == null || e.id().isBlank())
                throw new IllegalArgumentException("corpus entry with missing id");
            if (!ids.add(e.id()))
                throw new IllegalArgumentException("duplicate corpus entry id: " + e.id());
            if (e.behavior() == null || e.behavior().isBlank())
                throw new IllegalArgumentException("corpus entry " + e.id() + " has missing behavior");
            if (e.instruction() == null || e.instruction().isBlank())
                throw new IllegalArgumentException("corpus entry " + e.id() + " has missing instruction");
        }
    }
}
