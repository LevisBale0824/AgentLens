package com.agentlens.eval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Scenario(String id, String domain, String alert, List<String> symptoms, String rootCause,
                       List<String> allowedTools, List<String> forbiddenTools,
                       List<String> successAssertions, int maxSteps, String agentBehavior,
                       String adapter, String workdir, String instruction, Compliance compliance) {

    /**
     * Optional compliance red-line declarations for the data-level evaluator.
     * Absent (null) keeps every scenario-dependent rule not_verifiable and is
     * fully backward compatible with pre-compliance scenario files.
     */
    public record Compliance(List<String> expectedLiterals,
                             List<String> protectedPaths,
                             String networkPolicy) {
        @JsonCreator
        public Compliance(@JsonProperty("expected_literals") List<String> expectedLiterals,
                          @JsonProperty("protected_paths") List<String> protectedPaths,
                          @JsonProperty("network_policy") String networkPolicy) {
            this.expectedLiterals = expectedLiterals == null ? List.of() : List.copyOf(expectedLiterals);
            this.protectedPaths = protectedPaths == null ? List.of() : List.copyOf(protectedPaths);
            this.networkPolicy = networkPolicy;
        }
    }

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
                    @JsonProperty("agent_behavior") String agentBehavior,
                    @JsonProperty("adapter") String adapter,
                    @JsonProperty("workdir") String workdir,
                    @JsonProperty("instruction") String instruction,
                    @JsonProperty("compliance") Compliance compliance) {
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
        this.adapter = adapter == null || adapter.isBlank() ? "mock" : adapter;
        this.workdir = workdir;
        this.instruction = instruction;
        this.compliance = compliance;
    }

    /** Converts this scenario into a runnable AgentTask; behavior rides in context. */
    public com.agentlens.core.AgentTask toTask() {
        var context = new java.util.HashMap<String, Object>();
        // Map.copyOf rejects null values: only carry fields the scenario actually defines.
        if (alert != null) context.put("alert", alert);
        context.put("symptoms", symptoms);
        if (rootCause != null) context.put("rootCause", rootCause);
        context.put("allowedTools", allowedTools);
        context.put("forbiddenTools", forbiddenTools);
        context.put("agentBehavior", agentBehavior);
        if (workdir != null) context.put("workdir", workdir);
        if (compliance != null) {
            context.put("compliance", compliance);
            // Flat mirror for consumers (e.g. mock adapter) that must not depend on this module.
            if (!compliance.expectedLiterals().isEmpty())
                context.put("expectedLiterals", compliance.expectedLiterals());
        }
        return new com.agentlens.core.AgentTask(id, buildInstruction(), context, maxSteps);
    }

    /** Real-agent scenarios carry the full task in "instruction"; ops scenarios derive it from the alert. */
    private String buildInstruction() {
        return instruction != null && !instruction.isBlank() ? instruction : "Diagnose alert " + alert;
    }
}
