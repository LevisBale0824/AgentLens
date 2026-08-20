package com.agentlens.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ScenarioLoader {
    private static final Set<String> KNOWN_BEHAVIORS =
            Set.of("correct", "wrong_tool", "forbidden_action", "loop", "flaky", "external");

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public List<Scenario> load(Path path) throws IOException {
        List<Scenario> scenarios = mapper.readValue(path.toFile(), new TypeReference<>() {});
        validate(scenarios);
        return scenarios;
    }

    private void validate(List<Scenario> scenarios) {
        Set<String> ids = new HashSet<>();
        for (Scenario s : scenarios) {
            if (s.id() == null || s.id().isBlank())
                throw new IllegalArgumentException("scenario with missing id");
            if (!ids.add(s.id()))
                throw new IllegalArgumentException("duplicate scenario id: " + s.id());
            if (s.allowedTools().isEmpty() && s.forbiddenTools().isEmpty())
                throw new IllegalArgumentException("scenario " + s.id() + " defines neither allowed nor forbidden tools");
            if (!KNOWN_BEHAVIORS.contains(s.agentBehavior()))
                throw new IllegalArgumentException("scenario " + s.id() + " has unknown agent_behavior: " + s.agentBehavior());
        }
    }
}
