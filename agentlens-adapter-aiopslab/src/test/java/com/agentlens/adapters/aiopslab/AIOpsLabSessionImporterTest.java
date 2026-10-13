package com.agentlens.adapters.aiopslab;

import com.agentlens.core.AgentEvent;
import com.agentlens.core.AgentRun;
import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ROADMAP P2-2: AIOpsLab session JSON → unified trace mapping. */
class AIOpsLabSessionImporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DETECTION_SESSION = """
            {
              "agent": "qwen-agent",
              "session_id": "8d2b1a0e-1111-2222-3333-444455556666",
              "problem_id": "k8s_target_port-misconfig-detection-1",
              "start_time": 1788600000.0,
              "end_time": 1788600030.5,
              "trace": [
                {"role": "assistant", "content": "I should inspect the service logs first.\\n```\\nget_logs(\\"test-social-network\\", \\"user-service\\")\\n```"},
                {"role": "env", "content": "connectivity error ... 2026-01-01 user-service logs"},
                {"role": "assistant", "content": "Let me think about what the metrics show without running any action yet."},
                {"role": "assistant", "content": "Checking pod state:\\n```\\nexec_shell(\\"kubectl get pods -n test-social-network\\")\\n```"},
                {"role": "env", "content": "Error: Your service/namespace does not exist. Use kubectl to check."},
                {"role": "assistant", "content": "Based on the anomalies observed:\\n```\\nsubmit(\\"Yes\\")\\n```"},
                {"role": "env", "content": "Submission received."}
              ],
              "results": {"TTD": 12.34, "steps": 4, "in_tokens": 1200, "out_tokens": 340}
            }
            """;

    private static AIOpsLabSessionImporter.Imported importJson(String json) throws IOException {
        return new AIOpsLabSessionImporter().importSession(JSON.readTree(json));
    }

    @Test
    void mapsAciTurnsOntoUnifiedTrace() throws Exception {
        AIOpsLabSessionImporter.Imported imported = importJson(DETECTION_SESSION);
        AgentRun run = imported.run();
        assertEquals("8d2b1a0e-1111-2222-3333-444455556666", run.runId());
        assertEquals("k8s_target_port-misconfig-detection-1", run.taskId());
        assertEquals(RunStatus.SUCCESS, run.status());
        assertEquals("Yes", run.finalAnswer());
        assertEquals(30500, run.durationMs());
        assertEquals(1540, run.totalTokens());
        assertEquals("qwen-agent", imported.agentName());

        List<AgentEvent> events = run.events();
        assertEvent(events.get(0), EventType.TOOL_CALL, "get_logs", "aci-0");
        assertEvent(events.get(1), EventType.TOOL_RESULT, "get_logs", "aci-0");
        assertEquals("MODEL_CALL", events.get(2).type().toString());
        // env error marks the paired tool result
        assertEvent(events.get(4), EventType.TOOL_RESULT, "exec_shell", "aci-3");
        assertEquals("error", events.get(4).attributes().get("status"));
        assertEvent(events.get(5), EventType.TOOL_CALL, "submit", "aci-5");
        assertEvent(events.get(events.size() - 1), EventType.FINAL_ANSWER, "aiopslab.submit", null);
    }

    @Test
    void unevaluatedSessionFailsEvenWithSubmission() throws Exception {
        String json = """
                {"agent": "a", "session_id": "s1", "problem_id": "p1",
                 "start_time": 100.0, "end_time": 110.0,
                 "trace": [{"role": "assistant", "content": "```\\nsubmit(\\"No\\")\\n```"}],
                 "results": {}}
                """;
        AgentRun run = importJson(json).run();
        assertEquals(RunStatus.FAILED, run.status(),
                "no evaluator results (TTD/TTL/TTA/TTM) -> cannot count as success");
    }

    @Test
    void actionParsingUtilities() {
        assertEquals("exec_shell(\"ls -l\")",
                AIOpsLabSessionImporter.extractAction("Check k8s info\n```\nexec_shell(\"ls -l\")\n```"));
        assertNull(AIOpsLabSessionImporter.extractAction("no fenced action here"));
        assertEquals("submit", AIOpsLabSessionImporter.apiName("submit(\"Yes\")"));
        assertNull(AIOpsLabSessionImporter.apiName("not an api"));
        assertEquals("Yes", AIOpsLabSessionImporter.actionArguments("submit(\"Yes\")"));
        assertEquals("kubectl get pods", AIOpsLabSessionImporter.actionArguments("exec_shell('kubectl get pods')"));
    }

    @Test
    void evaluatedChecksTheAiopsLabResultKeys() {
        var importer = new AIOpsLabSessionImporter();
        assertTrue(AIOpsLabSessionImporter.evaluated(JSON.createObjectNode().put("TTD", 1.0)));
        assertTrue(AIOpsLabSessionImporter.evaluated(JSON.createObjectNode().put("success", true)));
        assertFalse(AIOpsLabSessionImporter.evaluated(JSON.createObjectNode().put("other", 1)));
        assertFalse(AIOpsLabSessionImporter.evaluated(null));
    }

    @Test
    void importsARealSessionFile() throws Exception {
        Path file = Path.of("target", "test-session.json");
        Files.writeString(file, DETECTION_SESSION);
        AIOpsLabSessionImporter.Imported imported =
                new AIOpsLabSessionImporter().importSession(file);
        assertEquals("k8s_target_port-misconfig-detection-1", imported.run().taskId());
        assertTrue(AIOpsLabSessionImporter.evaluated(imported.results()));
    }

    private static void assertEvent(AgentEvent event, EventType type, String name, String callId) {
        assertEquals(type, event.type());
        assertEquals(name, event.name());
        if (callId != null) assertEquals(callId, event.attributes().get("callId"));
    }
}
