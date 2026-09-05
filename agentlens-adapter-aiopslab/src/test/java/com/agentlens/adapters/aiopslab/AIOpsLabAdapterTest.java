package com.agentlens.adapters.aiopslab;

import com.agentlens.core.AgentRun;
import com.agentlens.core.RunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AIOpsLabAdapterTest {

    @Test
    void failsWithClearGuidanceWithoutAiopslabHome() {
        // context override (empty string) defeats any ambient env var: deterministic
        var task = new com.agentlens.core.AgentTask("p1", "instruction", Map.of("aiopslabHome", ""), 10);
        AgentRun run = new AIOpsLabAdapter().run(task);
        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.error().contains("AGENTLENS_AIOPSLAB_HOME"));
        assertTrue(run.error().contains("import --aiopslab"));
    }

    @Test
    void rejectsHomesThatAreNotAiopslabCheckouts(@TempDir Path dir) {
        var task = new com.agentlens.core.AgentTask("p1", "instruction",
                Map.of("aiopslabHome", dir.toString()), 10);
        AgentRun run = new AIOpsLabAdapter().run(task);
        assertEquals(RunStatus.FAILED, run.status());
        assertTrue(run.error().contains("missing clients/"));
    }

    @Test
    void newestSessionPicksLatestFileAfterInstant(@TempDir Path dir) throws IOException {
        Path older = dir.resolve("100_1.json");
        Path newer = dir.resolve("200_2.json");
        Files.writeString(older, "{}");
        Files.writeString(newer, "{}");
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));

        Path found = AIOpsLabAdapter.newestSession(dir, Instant.parse("2026-01-01T12:00:00Z"));
        assertEquals(newer, found);

        assertNull(AIOpsLabAdapter.newestSession(dir, Instant.parse("2026-01-03T00:00:00Z")),
                "nothing written after the cutoff");
        assertNull(AIOpsLabAdapter.newestSession(dir.resolve("nope"), Instant.EPOCH));
        Files.writeString(dir.resolve("300_notes.txt"), "not a session");
        assertEquals(newer, AIOpsLabAdapter.newestSession(dir, Instant.parse("2026-01-01T12:00:00Z")),
                "non-json files are ignored");
    }
}
