package com.agentlens.eval;

import java.util.List;

public record Scenario(String id, String domain, String alert, List<String> symptoms, String rootCause,
                       List<String> allowedTools, List<String> forbiddenTools,
                       List<String> successAssertions, int maxSteps) {
    public Scenario {
        symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        successAssertions = successAssertions == null ? List.of() : List.copyOf(successAssertions);
    }
}
