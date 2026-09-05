package com.agentlens.adapters.aiopslab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AIOpsLab's official {@code aiopslab/orchestrator/problems/registry.py}
 * into a structured problem catalog (ROADMAP P2-2). Problem IDs are structured:
 * {@code <fault>-<task_type>-<variant>} where task type is one of
 * detection / localization / analysis / mitigation - e.g.
 * {@code k8s_target_port-misconfig-localization-1}.
 *
 * <p>Each problem maps onto an AgentLens scenario: the fault segment determines
 * the ground-truth root cause (static map for the well-known AIOpsLab faults,
 * prettified fallback otherwise), and the task type determines the ACI action
 * set (all task types share the base observation actions plus {@code submit}).
 *
 * <p>This is an offline parse of the registry source: instantiating the actual
 * Python problem objects requires a provisioned cluster, which the catalog
 * deliberately does not need.
 */
public final class AIOpsLabProblemCatalog {

    /** The four AIOpsLab task types. */
    public static final Set<String> TASK_TYPES = Set.of(
            "detection", "localization", "analysis", "mitigation");

    /**
     * ACI actions available in every task (aiopslab/orchestrator/actions/base.py
     * TaskActions) plus the per-task submit - i.e. the agent's allowed tool set.
     */
    public static final List<String> ACI_ACTIONS = List.of(
            "get_logs", "get_metrics", "get_traces", "read_metrics", "read_traces",
            "exec_shell", "submit");

    private static final Pattern PROBLEM_ID = Pattern.compile(
            "\"([\\w-]+?)-(detection|localization|analysis|mitigation)-(\\d+)\"");

    /**
     * The 31 fault segments of the official registry (main branch) → ground-truth
     * root cause. Exact keys; {@link #rootCauseOf} also falls back to prefix
     * matching so future registry variants of the same family still resolve.
     */
    private static final Map<String, String> FAULT_ROOT_CAUSES = Map.ofEntries(
            Map.entry("assign_to_non_existent_node_social_net", "Pod assigned to a non-existent node"),
            Map.entry("astronomy_shop_ad_service_failure", "Ad service failure"),
            Map.entry("astronomy_shop_ad_service_high_cpu", "Ad service high CPU"),
            Map.entry("astronomy_shop_ad_service_manual_gc", "Ad service manual GC thrashing"),
            Map.entry("astronomy_shop_cart_service_failure", "Cart service failure"),
            Map.entry("astronomy_shop_image_slow_load", "Slow container image pull"),
            Map.entry("astronomy_shop_kafka_queue_problems", "Kafka queue backlog (consumer lag)"),
            Map.entry("astronomy_shop_loadgenerator_flood_homepage", "Homepage flooded by load generator"),
            Map.entry("astronomy_shop_payment_service_failure", "Payment service failure"),
            Map.entry("astronomy_shop_payment_service_unreachable", "Payment service unreachable"),
            Map.entry("astronomy_shop_product_catalog_service_failure", "Product catalog service failure"),
            Map.entry("astronomy_shop_recommendation_service_cache_failure", "Recommendation service cache failure"),
            Map.entry("auth_miss_mongodb", "MongoDB deployed with authentication disabled"),
            Map.entry("disk_woreout", "Disk wearout fault"),
            Map.entry("k8s_target_port-misconfig", "K8S service targetPort misconfiguration"),
            Map.entry("kernel_fault_hotel_reservation", "Kernel-level fault injection"),
            Map.entry("misconfig_app_hotel_res", "Application configuration error (mongodb URL)"),
            Map.entry("network_delay_hotel_res", "Chaos Mesh network delay"),
            Map.entry("network_loss_hotel_res", "Chaos Mesh network loss"),
            Map.entry("operator_invalid_affinity_toleration", "Operator misoperation: invalid affinity/toleration"),
            Map.entry("operator_non_existent_storage", "Operator misoperation: non-existent storage"),
            Map.entry("operator_overload_replicas", "Operator misoperation: overloaded replicas"),
            Map.entry("operator_security_context_fault", "Operator misoperation: security context fault"),
            Map.entry("operator_wrong_update_strategy", "Operator misoperation: wrong update strategy"),
            Map.entry("pod_failure_hotel_res", "Chaos Mesh pod failure"),
            Map.entry("pod_kill_hotel_res", "Chaos Mesh pod kill"),
            Map.entry("redeploy_without_PV", "Redeployment without persistent volume"),
            Map.entry("revoke_auth_mongodb", "MongoDB user authorization revoked"),
            Map.entry("scale_pod_zero_social_net", "Deployment scaled to zero replicas"),
            Map.entry("user_unregistered_mongodb", "Storage user service unregistered"),
            Map.entry("wrong_bin_usage", "Wrong binary usage"));

    /** One catalog entry parsed from the registry. */
    public record Problem(String id, String fault, String taskType, int variant,
                          String rootCause) {}

    /** Parses all problem IDs from a registry.py source file. */
    public List<Problem> parse(Path registryPy) throws IOException {
        return parse(Files.readString(registryPy));
    }

    /** Parses problem IDs from registry source text; order preserved, IDs deduplicated. */
    public List<Problem> parse(String registrySource) {
        Map<String, Problem> byId = new LinkedHashMap<>();
        Matcher matcher = PROBLEM_ID.matcher(registrySource);
        while (matcher.find()) {
            String id = matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
            if (byId.containsKey(id)) continue;
            byId.put(id, new Problem(id, matcher.group(1), matcher.group(2),
                    Integer.parseInt(matcher.group(3)), rootCauseOf(matcher.group(1))));
        }
        return List.copyOf(byId.values());
    }

    /** Ground-truth root cause: static map for known faults, prettified fallback otherwise. */
    public static String rootCauseOf(String fault) {
        String exact = FAULT_ROOT_CAUSES.get(fault);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : FAULT_ROOT_CAUSES.entrySet()) {
            if (fault.startsWith(entry.getKey())) return entry.getValue();
        }
        return titleize(fault);
    }

    /** Scenario-level view used for dataset generation: id → attribute map. */
    public static Map<String, Object> toScenario(Problem problem) {
        Map<String, Object> scenario = new TreeMap<>();
        scenario.put("id", problem.id());
        scenario.put("domain", "aiopslab-" + problem.taskType());
        scenario.put("alert", problem.fault());
        scenario.put("symptoms", List.of("aiopslab:" + problem.taskType()));
        scenario.put("root_cause", problem.rootCause());
        scenario.put("allowed_tools", ACI_ACTIONS);
        scenario.put("forbidden_tools", List.of());
        scenario.put("success_assertions",
                List.of("aiopslab evaluator results present (TTD/TTL/TTA/TTM)"));
        scenario.put("max_steps", 15);
        scenario.put("agent_behavior", "external");
        scenario.put("adapter", "aiopslab");
        return scenario;
    }

    private static String titleize(String segment) {
        String[] words = segment.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }
}
