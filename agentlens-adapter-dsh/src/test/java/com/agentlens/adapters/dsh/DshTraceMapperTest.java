package com.agentlens.adapters.dsh;

import com.agentlens.core.EventType;
import com.agentlens.core.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DshTraceMapperTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsSessionMetadataToolsAndFinalAnswer() throws Exception {
        List<String> lines = List.of(
                "{\"type\": \"session\", \"version\": 0, \"id\": \"session-xyz\", \"createdAt\": 1000, \"cwd\": \"D:/w\", \"agentPreset\": \"standard\"}",
                "{\"type\": \"request/context\", \"seq\": 12, \"time\": 1100, \"data\": {\"provider\": \"zai\", \"model\": \"glm-5.1\", \"contextWindow\": 200000}}",
                "{\"type\": \"user/message\", \"seq\": 7, \"time\": 1088, \"data\": {\"content\": [{\"type\": \"text\", \"text\": \"fix the bug\"}]}}",
                "{\"type\": \"tool/call\", \"seq\": 20, \"time\": 2000, \"data\": {\"callId\": \"c1\", \"name\": \"read\", \"arguments\": \"{\\\"path\\\":\\\"a.txt\\\"}\"}}",
                "{\"type\": \"tool/result\", \"seq\": 21, \"time\": 2100, \"data\": {\"message\":"
                        + " {\"source\": {\"callId\": \"c1\"}, \"content\": [{\"type\": \"tool-result\","
                        + " \"content\": [{\"type\": \"text\", \"text\": \"contents\"}]}]}}}",
                "{\"type\": \"tool/call\", \"seq\": 22, \"time\": 2200, \"data\": {\"callId\": \"c2\", \"name\": \"edit\", \"arguments\": \"{}\"}}",
                "{\"type\": \"tool/result\", \"seq\": 23, \"time\": 2300, \"data\": {\"message\": {\"source\": {\"callId\": \"c2\"}, \"content\": [{\"type\": \"error\", \"text\": \"denied\"}]}}}",
                "{\"type\": \"assistant/message\", \"seq\": 30, \"time\": 3000, \"data\": {\"turn\": 1, \"step\": 2,"
                        + " \"message\": {\"content\": [{\"type\": \"text\", \"text\": \"done: fixed\"}],"
                        + " \"source\": {\"kind\": \"model\", \"provider\": \"zai\", \"model\": \"glm-5.1\"}}}}");
        var mapped = DshTraceMapper.toRun(lines.stream().map(l -> unchecked(l)).toList(), "task-1");

        assertEquals("session-xyz", mapped.sessionId());
        assertEquals("fix the bug", mapped.instruction());
        assertEquals(RunStatus.SUCCESS, mapped.run().status());
        assertEquals("done: fixed", mapped.run().finalAnswer());
        assertEquals(2000, mapped.run().durationMs());

        var events = mapped.run().events();
        assertEquals(2, events.stream().filter(e -> e.type() == EventType.TOOL_CALL).count());
        assertEquals(2, events.stream().filter(e -> e.type() == EventType.TOOL_RESULT).count());

        var editResult = events.stream()
                .filter(e -> e.type() == EventType.TOOL_RESULT && "edit".equals(e.name()))
                .findFirst().orElseThrow();
        assertEquals("error", editResult.attributes().get("status"));

        var modelCall = events.stream().filter(e -> e.type() == EventType.MODEL_CALL)
                .findFirst().orElseThrow();
        assertEquals("glm-5.1", modelCall.attributes().get("model"));
    }

    @Test
    void sessionWithoutAnswerIsFailed() throws Exception {
        List<String> lines = List.of(
                "{\"type\": \"session\", \"id\": \"s\", \"createdAt\": 0, \"cwd\": \"x\"}",
                "{\"type\": \"turn/start\", \"time\": 10}");
        var mapped = DshTraceMapper.toRun(lines.stream().map(l -> unchecked(l)).toList(), "task-2");
        assertEquals(RunStatus.FAILED, mapped.run().status());
        assertNotNull(mapped.run().error());
    }

    private static com.fasterxml.jackson.databind.JsonNode unchecked(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
