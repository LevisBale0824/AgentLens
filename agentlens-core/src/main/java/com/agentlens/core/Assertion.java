package com.agentlens.core;

public record Assertion(String id, String description, boolean required) {
    public Assertion {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("assertion id is required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("assertion description is required");
    }
}
