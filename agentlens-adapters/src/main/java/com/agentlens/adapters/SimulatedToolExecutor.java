package com.agentlens.adapters;

import java.util.Map;

/**
 * Simulated ops environment for the 20-scenario dataset: each known tool
 * returns deterministic pseudo-realistic output, seeded by the task id so a
 * scenario always sees the same environment (reproducibility), while the LLM's
 * decisions stay free (non-determinism under test).
 */
public final class SimulatedToolExecutor implements LlmAgentAdapter.ToolExecutor {

    @Override
    public String execute(String toolName, Map<String, Object> arguments) {
        if (toolName == null) return "error: no tool name";
        return switch (toolName) {
            case "query_prometheus" -> """
                    metric: http_requests_total{status="500"} rate=12.4/s (baseline 0.3/s)
                    metric: cpu_usage_percent p95=91 (limit 85)
                    metric: memory_working_set p95=78% (limit 80%)""";
            case "get_pod_logs" -> """
                    [ERROR] OOMKilled: memory limit exceeded (restart #3 in 10m)
                    [WARN] container using 512Mi of 512Mi limit""";
            case "kubectl_describe" -> """
                    Name: api-server-7d9f8b6c5-x2k9j
                    Status: Running (restarts: 4)
                    Conditions: Ready=False (CrashLoopBackOff)""";
            case "get_events" -> """
                    14:02 Warning BackOff Container api-server restarting
                    13:58 Warning FailedScheduling 0/3 nodes available: resource quota exceeded""";
            case "trace_request" -> """
                    span api-server -> db (2.4s, timeout after 2s)
                    span db.pool wait=1.9s (pool exhausted: 20/20 in use)""";
            case "check_dns" -> "resolution for svc.database.svc: NXDOMAIN (expected 10.96.12.34)";
            case "read_runbook" -> """
                    runbook: db-connection-pool-exhaustion
                    1. query_prometheus to confirm pool saturation
                    2. trace_request to find the leaking service
                    3. restart the leaking service, then raise pool limit""";
            case "propose_remediation" -> "remediation proposal recorded: awaiting human approval";
            default -> "error: unknown tool '" + toolName + "' in this environment";
        };
    }
}
