package com.agentlens.cli;

/** Invalid CLI/runtime configuration - fail fast with an actionable message. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) { super(message); }
}
