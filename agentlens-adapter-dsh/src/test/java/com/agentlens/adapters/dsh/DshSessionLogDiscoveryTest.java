package com.agentlens.adapters.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for session-log discovery: when several dsh sessions are
 * concurrently active (e.g. the interactive GUI session running the evaluation),
 * findSessionLog must latch onto the one whose cwd matches the scenario workdir,
 * not merely the newest log under ~/.dsh/sessions.
 */
class DshSessionLogDiscoveryTest {

    @TempDir
    Path home;

    @Test
    void prefersWorkdirSessionOverNewerUnrelatedSession() throws IOException {
        Path task1 = log(home, "--D-tmp-dsh-eval-task1--", "session-a", 1_000);
        Path gui = log(home, "--D-code-AgentLens--", "session-b", 2_000); // newer, wrong cwd

        var found = DshAgentAdapter.findSessionLog(home, 0, Path.of("D:/tmp/dsh-eval/task1"));
        assertTrue(found.isPresent());
        assertEquals(task1, found.get());
        assertNotEquals(gui, found.get());
    }

    @Test
    void fallsBackToNewestWhenWorkdirDirectoryAbsent() throws IOException {
        Path gui = log(home, "--D-code-AgentLens--", "session-b", 2_000);

        var found = DshAgentAdapter.findSessionLog(home, 0, Path.of("D:/tmp/dsh-eval/task1"));
        // No directory matching the workdir: strict filter yields nothing rather
        // than mis-attributing a foreign session's trace.
        assertTrue(found.isEmpty());
        assertEquals(gui, DshAgentAdapter.findSessionLog(home, 0, null).orElseThrow());
    }

    @Test
    void dirTokenCollapsesNonAlphanumerics() {
        assertEquals("d-tmp-dsh-eval-task1",
                DshAgentAdapter.dirToken(Path.of("D:\\tmp\\dsh-eval\\task1")));
        assertEquals("d-tmp-dsh-eval-task1",
                DshAgentAdapter.dirToken(Path.of("D:/tmp/dsh-eval/task1")));
        assertNull(DshAgentAdapter.dirToken(null));
    }

    private static Path log(Path dshHome, String cwdDir, String sessionDir, long mtime) throws IOException {
        Path dir = dshHome.resolve("sessions").resolve(cwdDir).resolve(sessionDir);
        Files.createDirectories(dir);
        Path file = dir.resolve("session.jsonl");
        Files.writeString(file, "{\"type\":\"session\"}\n");
        Files.getLastModifiedTime(file).toMillis();
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + mtime));
        return file;
    }
}
