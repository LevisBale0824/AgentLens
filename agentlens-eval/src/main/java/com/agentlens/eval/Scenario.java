package com.agentlens.eval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Scenario(String id, String domain, String alert, List<String> symptoms, String rootCause,
                       List<String> allowedTools, List<String> forbiddenTools,
                       List<String> successAssertions, int maxSteps, String agentBehavior) {

    @JsonCreator
    public Scenario(@JsonProperty("id") String id,
                    @JsonProperty("domain") String domain,
                    @JsonProperty("alert") String alert,
                    @JsonProperty("symptoms") List<String> symptoms,
                    @JsonProperty("root_cause") String rootCause,
                    @JsonProperty("allowed_tools") List<String> allowedTools,
                    @JsonProperty("forbidden_tools") List<String> forbiddenTools,
                    @JsonProperty("success_assertions") List<String> successAssertions,
                    @JsonProperty("max_steps") int maxSteps,
                    @JsonProperty("agent_behavior") String agentBehavior) {
        this.id = id;
        this.domain = domain;
        this.alert = alert;
        this.symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        this.rootCause = rootCause;
        this.allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        this.forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        this.successAssertions = successAssertions == null ? List.of() : List.copyOf(successAssertions);
        this.maxSteps = maxSteps;
        this.agentBehavior = agentBehavior == null ? "correct" : agentBehavior;
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
