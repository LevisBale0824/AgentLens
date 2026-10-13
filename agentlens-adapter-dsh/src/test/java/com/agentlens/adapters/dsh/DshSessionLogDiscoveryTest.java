package com.agentlens.adapters.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void workdirMatchesRealDshEncodedNamesExactly() throws IOException {
        // Encoding observed on a live machine: D:\code\AgentLens -> --D-code-AgentLens--
        Path sessions = home.resolve("sessions");
        Path log = sessions.resolve("--D-code-AgentLens--").resolve("s1").resolve("session.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "{}\n");

        assertTrue(DshAgentAdapter.underCwdDirectory(log, sessions,
                DshAgentAdapter.dirToken(Path.of("D:\\code\\AgentLens"))));
        assertTrue(DshAgentAdapter.underCwdDirectory(log, sessions,
                DshAgentAdapter.dirToken(Path.of("d:/CODE/agentlens")))); // case-insensitive
    }

    @Test
    void substringCwdNamesNoLongerMatch() throws IOException {
        // The old contains() match mis-attributed these to workdir D:\tmp / D:\tmp\x\a.
        Path sessions = home.resolve("sessions");
        Path longer = sessions.resolve("--D-code-d-tmp--").resolve("s1").resolve("session.jsonl");
        Files.createDirectories(longer.getParent());
        Files.writeString(longer, "{}\n");
        Path suffix = sessions.resolve("--D-tmp-x-ab--").resolve("s2").resolve("session.jsonl");
        Files.createDirectories(suffix.getParent());
        Files.writeString(suffix, "{}\n");

        assertFalse(DshAgentAdapter.underCwdDirectory(longer, sessions,
                DshAgentAdapter.dirToken(Path.of("D:\\tmp"))));
        assertFalse(DshAgentAdapter.underCwdDirectory(suffix, sessions,
                DshAgentAdapter.dirToken(Path.of("D:\\tmp\\x\\a"))));
    }

    @Test
    void foldingCollisionIsInherentToDshNaming() throws IOException {
        // D:\tmp-x\a and D:\tmp\x\a fold to the same DSH name - identical to DSH's own
        // encoding, so both match here; documented, not fixable on our side.
        Path sessions = home.resolve("sessions");
        Path log = sessions.resolve("--D-Tmp-X-A--").resolve("s1").resolve("session.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "{}\n");

        assertTrue(DshAgentAdapter.underCwdDirectory(log, sessions,
                DshAgentAdapter.dirToken(Path.of("D:\\tmp\\x\\a"))));
        assertTrue(DshAgentAdapter.underCwdDirectory(log, sessions,
                DshAgentAdapter.dirToken(Path.of("D:\\tmp-x\\a"))));
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
