package com.agentlens.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ScenarioLoader {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public List<Scenario> load(Path path) throws IOException {
        return mapper.readValue(path.toFile(), new TypeReference<>() {});
    }
}
