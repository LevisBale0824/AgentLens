package com.agentlens.core;

public enum EventType {
    MODEL_CALL,
    TOOL_CALL,
    TOOL_RESULT,
    RETRIEVAL,
    PLAN,
    MEMORY_READ,
    MEMORY_WRITE,
    FINAL_ANSWER,
    ERROR
}
