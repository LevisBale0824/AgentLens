package com.agentlens.adapters;

import com.agentlens.core.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class CliAgentAdapter implements AgentAdapter {
    private final List<String> command;
    private final Duration timeout;

    /** SPI no-arg constructor: command comes from AGENTLENS_CLI_COMMAND (default "echo"). */
    public CliAgentAdapter() {
        this(List.of(System.getenv().getOrDefault("AGENTLENS_CLI_COMMAND", "echo")), null);
    }

    public CliAgentAdapter(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("command is required");
        this.command = List.copyOf(command);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    }

    @Override public String name() { return "cli"; }

    @Override
    public AgentRun run(AgentTask task) {
        Instant started = Instant.now();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().write((task.instruction() + System.lineSeparator()).getBytes());
            process.getOutputStream().close();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) { process.destroyForcibly(); return runFailed(task, started, RunStatus.TIMEOUT, "CLI timeout"); }
            String output = new String(process.getInputStream().readAllBytes());
            boolean success = process.exitValue() == 0;
            return new AgentRun(UUID.randomUUID().toString(), task.id(), success ? RunStatus.SUCCESS : RunStatus.FAILED,
                    success ? output : null,
                    List.of(new AgentEvent(success ? EventType.FINAL_ANSWER : EventType.ERROR, "cli.process",
                            Map.of("exitCode", process.exitValue()), started, Duration.between(started, Instant.now()).toMillis())),
                    started, Duration.between(started, Instant.now()).toMillis(), 0,
                    success ? null : "CLI exit code " + process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return runFailed(task, started, RunStatus.CANCELLED, e.getMessage());
        } catch (IOException e) {
            return runFailed(task, started, RunStatus.FAILED, e.getMessage());
        }
    }

    private AgentRun runFailed(AgentTask task, Instant started, RunStatus status, String error) {
        return new AgentRun(UUID.randomUUID().toString(), task.id(), status, null,
                List.of(new AgentEvent(EventType.ERROR, "cli.error", Map.of("error", String.valueOf(error)), started, 0)),
                started, Duration.between(started, Instant.now()).toMillis(), 0, error);
    }
}
