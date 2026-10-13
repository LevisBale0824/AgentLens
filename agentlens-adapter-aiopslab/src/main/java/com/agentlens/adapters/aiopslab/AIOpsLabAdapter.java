package com.agentlens.adapters.aiopslab;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * AIOpsLab adapter (ROADMAP P2-2). Bridges AgentLens to Microsoft
 * <a href="https://github.com/microsoft/AIOpsLab">AIOpsLab</a>'s own agent
 * runner: when an AIOpsLab checkout (plus its kind cluster, ChaosMesh and
 * Prometheus stack) is available, each task runs as
 *
 * <pre>
 * python clients/client.py --agent &lt;agent&gt; --problem-id &lt;task.id&gt; --max-steps &lt;maxSteps&gt;
 *   (cwd = AIOpsLab checkout; AGENTLENS_AIOPSLAB_HOME or task context "aiopslabHome")
 * </pre>
 *
 * and the newest session JSON under {@code results/} written after start is
 * imported onto the unified trace via {@link AIOpsLabSessionImporter}.
 *
 * <p>Without a configured AIOpsLab home the adapter fails the run with a clear
 * pointer - the offline paths (session {@code import} and the problem catalog)
 * do NOT require a cluster.
 */
public final class AIOpsLabAdapter implements AgentAdapter {
    private static final long DEFAULT_TIMEOUT_SEC = 1800;

    @Override public String name() { return "aiopslab"; }

    @Override
    public AgentRun run(AgentTask task) {
        Instant started = Instant.now();
        Map<String, Object> ctx = task.context();
        String home = str(ctx.get("aiopslabHome"), env("AGENTLENS_AIOPSLAB_HOME", null));
        if (home == null || home.isBlank()) {
            return failed(task, started,
                    "AIOpsLab adapter requires AGENTLENS_AIOPSLAB_HOME (an AIOpsLab checkout) "
                            + "and a provisioned kind cluster; session import "
                            + "(import --aiopslab <session.json>) and the problem catalog work "
                            + "without a cluster - see docs/reports/aiopslab-integration.md");
        }
        Path homeDir = Path.of(home);
        if (!Files.isDirectory(homeDir.resolve("clients"))) {
            return failed(task, started, "AGENTLENS_AIOPSLAB_HOME does not look like an "
                    + "AIOpsLab checkout (missing clients/): " + home);
        }

        String agent = str(ctx.get("aiopslabAgent"), env("AGENTLENS_AIOPSLAB_AGENT", "qwen"));
        long timeoutSec = ctx.get("timeoutSec") instanceof Number n ? n.longValue() : DEFAULT_TIMEOUT_SEC;

        List<String> command = new ArrayList<>(List.of(
                env("AGENTLENS_AIOPSLAB_PYTHON", "python"),
                "clients/client.py",
                "--agent", agent,
                "--problem-id", task.id(),
                "--max-steps", String.valueOf(Math.max(1, task.maxSteps()))));

        Instant beforeStart = Instant.now();
        try {
            Process process = new ProcessBuilder(command)
                    .directory(homeDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> {
                byte[] buffer = new byte[8192];
                try {
                    int n;
                    while ((n = process.getInputStream().read(buffer)) > 0) {
                        synchronized (output) {
                            if (output.length() < 16_000) {
                                output.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            });
            drain.setDaemon(true);
            drain.start();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return failed(task, started, "AIOpsLab client timed out after " + timeoutSec + "s: "
                        + tail(output));
            }
            if (process.exitValue() != 0) {
                return failed(task, started, "AIOpsLab client exited " + process.exitValue()
                        + ": " + tail(output));
            }
        } catch (IOException e) {
            return failed(task, started, "cannot launch AIOpsLab client: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(task, started, "interrupted while running AIOpsLab client");
        }

        Path session = newestSession(homeDir.resolve("results"), beforeStart.minusSeconds(5));
        if (session == null) {
            return failed(task, started, "no AIOpsLab session JSON found under "
                    + homeDir.resolve("results") + " written after start");
        }
        try {
            return new AIOpsLabSessionImporter().importSession(session).run();
        } catch (IOException e) {
            return failed(task, started, "cannot import AIOpsLab session " + session + ": " + e);
        }
    }

    /** Newest session JSON under the results dir written after the given instant. */
    static Path newestSession(Path resultsDir, Instant after) {
        if (!Files.isDirectory(resultsDir)) return null;
        try (Stream<Path> files = Files.list(resultsDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isAfter(after);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max(java.util.Comparator.comparing(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant();
                        } catch (IOException e) {
                            return Instant.EPOCH;
                        }
                    }))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static AgentRun failed(AgentTask task, Instant started, String error) {
        return new AgentRun(UUID.randomUUID().toString(), task.id(),
                com.agentlens.core.RunStatus.FAILED, null,
                List.of(new AgentEvent(com.agentlens.core.EventType.ERROR, "aiopslab.error",
                        Map.of("error", error), started,
                        Duration.between(started, Instant.now()).toMillis())),
                started, Duration.between(started, Instant.now()).toMillis(), 0, error);
    }

    private static String tail(StringBuilder sb) {
        String s = sb.toString();
        return s.length() <= 500 ? s : "..." + s.substring(s.length() - 500);
    }

    private static String str(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
