package com.agentlens.eval;

import com.agentlens.core.AgentRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JsonlRunStore {
    private final Path file;

    public JsonlRunStore(Path file) throws IOException {
        this.file = file;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    public synchronized void append(AgentRun run) throws IOException {
        String line = "{\"runId\":\"" + escape(run.runId()) + "\",\"taskId\":\"" + escape(run.taskId())
                + "\",\"status\":\"" + run.status() + "\",\"events\":" + run.events().size()
                + ",\"durationMs\":" + run.durationMs() + "}\n";
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String escape(String value) { return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""); }
}
