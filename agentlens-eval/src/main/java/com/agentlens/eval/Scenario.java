package com.agentlens.eval;

import java.util.List;

public record Scenario(String id, String domain, String alert, List<String> symptoms, String rootCause,
                       List<String> allowedTools, List<String> forbiddenTools,
                       List<String> successAssertions, int maxSteps, String agentBehavior) {
    public Scenario {
        symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        successAssertions = successAssertions == null ? List.of() : List.copyOf(successAssertions);
        agentBehavior = agentBehavior == null ? "correct" : agentBehavior;
    }

    /** Converts this scenario into a runnable AgentTask; behavior rides in context. */
    public com.agentlens.core.AgentTask toTask() {
        var context = new java.util.HashMap<String, Object>();
        context.put("alert", alert);
        context.put("symptoms", symptoms);
        context.put("rootCause", rootCause);
        context.put("allowedTools", allowedTools);
        context.put("forbiddenTools", forbiddenTools);
        context.put("agentBehavior", agentBehavior);
        return new com.agentlens.core.AgentTask(id, "Diagnose alert " + alert, context, maxSteps);
    }
}
