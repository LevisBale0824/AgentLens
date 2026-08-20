package com.agentlens.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers {@link AgentAdapter} implementations via Java SPI
 * ({@code META-INF/services/com.agentlens.core.AgentAdapter}), so new adapters
 * are added by dropping a jar on the classpath instead of changing the CLI.
 *
 * <p>Adapters are keyed by {@link AgentAdapter#name()}; this module defines the
 * interface only — concrete adapters (mock, cli, http, dsh, ...) live in
 * adapter modules that register themselves through their own SPI files.
 */
public final class AdapterRegistry {

    private final Map<String, AgentAdapter> byName;
    private final String defaultName;

    private AdapterRegistry(Map<String, AgentAdapter> byName, String defaultName) {
        this.byName = byName;
        this.defaultName = defaultName;
    }

    /** Loads all SPI-registered adapters from the current classpath. */
    public static AdapterRegistry load() {
        return load("mock");
    }

    /** Loads all SPI-registered adapters; {@code defaultName} is used when lookup is empty. */
    public static AdapterRegistry load(String defaultName) {
        Map<String, AgentAdapter> byName = new LinkedHashMap<>();
        for (AgentAdapter adapter : ServiceLoader.load(AgentAdapter.class)) {
            if (adapter.name() != null) byName.putIfAbsent(adapter.name(), adapter);
        }
        if (defaultName != null && !byName.containsKey(defaultName) && !byName.isEmpty()) {
            defaultName = byName.keySet().iterator().next();
        }
        return new AdapterRegistry(byName, defaultName);
    }

    /** All discovered adapter names, in discovery order. */
    public Set<String> names() {
        return byName.keySet();
    }

    /** The adapter used when no explicit adapter is requested. */
    public AgentAdapter defaultAdapter() {
        AgentAdapter adapter = byName.get(defaultName);
        if (adapter == null)
            throw new IllegalStateException("no AgentAdapter found on classpath; available: " + names());
        return adapter;
    }

    /** Returns the adapter registered under {@code name}, or the default. */
    public AgentAdapter get(String name) {
        if (name == null || name.isBlank()) return defaultAdapter();
        AgentAdapter adapter = byName.get(name);
        if (adapter == null)
            throw new IllegalArgumentException("unknown adapter '" + name + "'; available: " + names());
        return adapter;
    }
}
