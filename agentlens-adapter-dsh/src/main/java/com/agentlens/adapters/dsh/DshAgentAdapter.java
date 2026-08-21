package com.agentlens.adapters.dsh;

import com.agentlens.core.AgentAdapter;
import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * DeepSeek Harness adapter. Runs each task as a real headless DSH session and
 * maps the resulting session log onto the unified trace:
 *
 * <pre>
 * dsh --profile headless "&lt;instruction&gt;"   (cwd = task workdir)
 *   -> newest session.jsonl(.zstd) under $DSH_HOME/sessions written after start
 *   -> DshSessionLogReader (zstd multi-frame decode) -> DshTraceMapper -> AgentRun
 * </pre>
 *
 * Configuration (task context, with environment fallbacks):
 * <ul>
 *   <li>workdir     - session working directory (default: process cwd)</li>
 *   <li>dshCommand  - launcher (default: env DSH_CMD, else "dsh")</li>
 *   <li>timeoutSec  - wall clock budget per task (default 900)</li>
 *   <li>dshHome     - session root (default: env DSH_HOME, else ~/.dsh)</li>
 * </ul>
 */
public final class DshAgentAdapter implements AgentAdapter {
    private static final long DEFAULT_TIMEOUT_SEC = 900;

    @Override public String name() { return "dsh"; }

    @Override
    public AgentRun run(AgentTask task) {
        return run(task, null);
    }

    /** Live listener: receives every streaming trace event plus live-evaluator verdicts. */
    public interface LiveListener {
        default void onTraceEvent(com.agentlens.core.AgentEvent event, int eventIndex) {}
        default void onVerdict(com.agentlens.core.LiveEvaluator.Verdict verdict) {}
        /** @return true to abort the dsh process immediately (early kill on hard violations). */
        default boolean shouldAbort(com.agentlens.core.LiveEvaluator.Verdict verdict) { return false; }
    }

    /**
     * Runs the task; when {@code live} is non-null, tails the session log while dsh
     * works, maps events onto the unified trace in real time, feeds the online
     * evaluator, and (via {@link LiveListener#shouldAbort}) can kill the process the
     * moment a zero-tolerance rule fires ?instead of letting it burn the full budget.
     */
    public AgentRun run(AgentTask task, LiveListener live) {
        Instant started = Instant.now();
        long startMillis = System.currentTimeMillis() - 2_000; // tolerate fs timestamp skew
        Map<String, Object> ctx = task.context();
        Path workdir = Path.of(str(ctx.get("workdir"), System.getProperty("user.dir")));
        List<String> fullCommand = new ArrayList<>(launcher(
                str(ctx.get("dshCommand"), env("DSH_CMD", "dsh"))));
        fullCommand.add(task.instruction());
        long timeoutSec = lng(ctx.get("timeoutSec"), DEFAULT_TIMEOUT_SEC);

        try {
            Process process = new ProcessBuilder(fullCommand)
                    .directory(workdir.toFile())
                    .redirectErrorStream(true)
                    .start();
            // Drain output concurrently so a full pipe cannot deadlock the child.
            StringBuilder outputTail = new StringBuilder();
            Thread drain = new Thread(() -> {
                byte[] buffer = new byte[8192];
                try {
                    int n;
                    while ((n = process.getInputStream().read(buffer)) > 0) {
                        if (outputTail.length() < 20_000)
                            outputTail.append(new String(buffer, 0, n));
                    }
                } catch (IOException ignored) {}
            });
            drain.setDaemon(true);
            drain.start();

            LiveSession liveSession = live != null ? startLiveSession(task, live, startMillis, process) : null;

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return finalize(task, started, startMillis, outputTail, liveSession, RunStatus.TIMEOUT,
                        "dsh headless timed out after " + timeoutSec + "s");
            }
            if (wasAborted(liveSession)) {
                return finalize(task, started, startMillis, outputTail, liveSession, RunStatus.CANCELLED,
                        "aborted by live evaluator: " + liveSession.evaluator().verdicts().stream()
                                .filter(v -> v.hardViolation()).findFirst()
                                .map(v -> v.message()).orElse("hard violation"));
            }
            return finalize(task, started, startMillis, outputTail, liveSession, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(task, started, RunStatus.CANCELLED, e.getMessage());
        } catch (Exception e) {
            return failed(task, started, RunStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Holds the watcher/evaluator side of a live run. */
    private record LiveSession(DshSessionLogWatcher watcher, com.agentlens.core.LiveEvaluator evaluator,
                               java.util.concurrent.atomic.AtomicBoolean aborted,
                               java.util.List<com.agentlens.core.AgentEvent> streamEvents) {}

    /**
     * Waits (bounded) for the session log to appear, then starts tailing it: every
     * durable event is mapped and fed to the online evaluator; a hard verdict with
     * {@code shouldAbort == true} sets the abort flag ?the CLI kills the process.
     */
    private LiveSession startLiveSession(AgentTask task, LiveListener live, long startMillis, Process process)
            throws InterruptedException {
        var evaluator = new com.agentlens.core.LiveEvaluator(
                forbiddenFrom(task), task.maxSteps(), 8, live::onVerdict);
        var events = java.util.Collections.synchronizedList(
                new java.util.ArrayList<com.agentlens.core.AgentEvent>());
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        var aborted = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Bounded wait for the log file to be created (dsh writes it at session start).
        Path log = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var found = findSessionLog(dshHome(task.context()), startMillis, workdirOf(task));
                if (found.isPresent()) { log = found.get(); break; }
            } catch (IOException ignored) {}
            Thread.sleep(500);
        }
        if (log == null) return new LiveSession(null, evaluator, aborted, events);

        var watcher = DshSessionLogWatcher.watch(log, 500L, node -> {
            DshTraceMapper.Mapped partial = DshTraceMapper.toRun(List.of(node), task.id());
            if (partial.run().events().isEmpty()) return;
            com.agentlens.core.AgentEvent event = partial.run().events().get(0);
            events.add(event);
            int index = counter.incrementAndGet() - 1;
            live.onTraceEvent(event, index);
            int verdictsBefore = evaluator.verdicts().size();
            evaluator.onEvent(index, event);
            // Early kill: hard verdict + listener says abort -> terminate dsh now.
            for (var v : evaluator.verdicts().subList(verdictsBefore, evaluator.verdicts().size())) {
                if (v.hardViolation() && live.shouldAbort(v) && aborted.compareAndSet(false, true)) {
                    process.destroyForcibly();
                }
            }
        });
        watcher.start();
        return new LiveSession(watcher, evaluator, aborted, events);
    }

    /** True if the live run was aborted early (hard violation + shouldAbort). */
    boolean wasAborted(LiveSession session) {
        return session != null && session.aborted().get();
    }

    private AgentRun finalize(AgentTask task, Instant started, long startMillis, StringBuilder outputTail,
                              LiveSession liveSession, RunStatus forcedStatus, String forcedError)
            throws IOException {
        try (var ignored = liveSession == null ? null : liveSession.watcher()) {
            Path sessionLog = findSessionLog(dshHome(task.context()), startMillis, workdirOf(task)).orElse(null);
            if (sessionLog != null) {
                DshTraceMapper.Mapped mapped = DshTraceMapper.toRun(
                        DshSessionLogReader.read(sessionLog), task.id());
                AgentRun run = mapped.run();
                if (forcedStatus != null) {
                    return new AgentRun(run.runId(), run.taskId(), forcedStatus, run.finalAnswer(),
                            run.events(), run.startedAt(), run.durationMs(), run.totalTokens(), forcedError);
                }
                return run;
            }
        }
        return failed(task, started, forcedStatus == null ? RunStatus.FAILED : forcedStatus,
                forcedError != null ? forcedError
                        : "no session log found; output: " + tail(outputTail));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> forbiddenFrom(AgentTask task) {
        Object v = task.context().get("forbiddenTools");
        return v instanceof List<?> list
                ? Set.copyOf(list.stream().map(String::valueOf).toList())
                : Set.of();
    }

    static Path dshHome(Map<String, Object> ctx) {
        String configured = str(ctx.get("dshHome"), env("DSH_HOME", null));
        return configured != null ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".dsh");
    }

    /** Scenario workdir (session cwd), defaulting to the process cwd like {@link #run}. */
    static Path workdirOf(AgentTask task) {
        return Path.of(str(task.context().get("workdir"), System.getProperty("user.dir")));
    }

    /**
     * Finds the newest session log written after {@code notBefore}. When {@code workdir}
     * is known, only session directories whose DSH-encoded cwd matches the workdir are
     * considered: DSH names each session directory after the session cwd (path with
     * non-alphanumerics replaced by dashes, e.g. {@code D:\tmp\x} -> {@code --D-tmp-x--}),
     * so filtering on that token prevents latching onto an unrelated concurrently-active
     * session (e.g. the interactive GUI session running the evaluation itself).
     */
    static java.util.Optional<Path> findSessionLog(Path dshHome, long notBefore, Path workdir) throws IOException {
        Path sessions = dshHome.resolve("sessions");
        if (!Files.isDirectory(sessions)) return java.util.Optional.empty();
        String token = dirToken(workdir);
        try (Stream<Path> files = Files.walk(sessions, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals("session.jsonl") || n.equals("session.jsonl.zstd");
                    })
                    .filter(p -> lastModifiedMs(p) >= notBefore)
                    .filter(p -> token == null || underCwdDirectory(p, sessions, token))
                    .max(Comparator.comparingLong(DshAgentAdapter::lastModifiedMs));
        }
    }

    /** True if some directory between the log file and {@code sessionsRoot} encodes the cwd token. */
    static boolean underCwdDirectory(Path log, Path sessionsRoot, String token) {
        for (Path dir = log.getParent(); dir != null && dir.startsWith(sessionsRoot); dir = dir.getParent()) {
            if (dir.getFileName() != null && normalizeDashes(dir.getFileName().toString()).contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * DSH session-directory token for a cwd: non-alphanumerics become dashes and runs of
     * dashes collapse to one (DSH encodes {@code D:\tmp\x} as {@code --D-tmp-x--}, so both
     * sides must be collapsed before comparing). Leading/trailing dashes are trimmed.
     */
    static String dirToken(Path workdir) {
        if (workdir == null) return null;
        return normalizeDashes(workdir.toString());
    }

    private static String normalizeDashes(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static long lastModifiedMs(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<String> launcher(String dsh) {
        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows && (dsh.endsWith(".cmd") || !dsh.contains("."))) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(dsh);
        command.add("--profile");
        command.add("headless");
        return command;
    }

    private static AgentRun failed(AgentTask task, Instant started, RunStatus status, String error) {
        return new AgentRun(UUID.randomUUID().toString(), task.id(), status, null,
                List.of(new AgentEvent(EventType.ERROR, "dsh.error",
                        Map.of("error", String.valueOf(error)), started,
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

    private static long lng(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
