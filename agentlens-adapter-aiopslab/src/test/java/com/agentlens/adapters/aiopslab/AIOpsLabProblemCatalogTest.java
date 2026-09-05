package com.agentlens.adapters.aiopslab;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parses the real microsoft/AIOpsLab problem registry (fixture, MIT). */
class AIOpsLabProblemCatalogTest {

    private static Path fixture() {
        return Path.of("src", "test", "resources", "aiopslab-registry.py");
    }

    @Test
    void parsesTheFullOfficialCatalog() throws Exception {
        List<AIOpsLabProblemCatalog.Problem> problems =
                new AIOpsLabProblemCatalog().parse(fixture());
        assertEquals(97, problems.size(), "official registry has 97 problem instances");
        // all four task types are represented
        for (String type : AIOpsLabProblemCatalog.TASK_TYPES) {
            assertTrue(problems.stream().anyMatch(p -> p.taskType().equals(type)),
                    "task type present: " + type);
        }
        // ids are unique and structured
        assertEquals(problems.size(), problems.stream().map(AIOpsLabProblemCatalog.Problem::id).distinct().count());
    }

    @Test
    void splitsStructuredIdsIntoFaultTaskTypeVariant() throws Exception {
        List<AIOpsLabProblemCatalog.Problem> problems =
                new AIOpsLabProblemCatalog().parse(fixture());
        AIOpsLabProblemCatalog.Problem targetPort = problems.stream()
                .filter(p -> p.id().equals("k8s_target_port-misconfig-localization-1"))
                .findFirst().orElseThrow();
        assertEquals("k8s_target_port-misconfig", targetPort.fault());
        assertEquals("localization", targetPort.taskType());
        assertEquals(1, targetPort.variant());
        assertEquals("K8S service targetPort misconfiguration", targetPort.rootCause());

        AIOpsLabProblemCatalog.Problem scaledToZero = problems.stream()
                .filter(p -> p.fault().equals("scale_pod_zero_social_net")).findFirst().orElseThrow();
        assertEquals("Deployment scaled to zero replicas", scaledToZero.rootCause());
    }

    @Test
    void coversTheOfficialFaultFamilies() throws Exception {
        List<AIOpsLabProblemCatalog.Problem> problems =
                new AIOpsLabProblemCatalog().parse(fixture());
        long distinctFaults = problems.stream().map(AIOpsLabProblemCatalog.Problem::fault).distinct().count();
        assertEquals(31, distinctFaults, "main-branch registry has 31 fault segments");
        assertTrue(problems.stream().anyMatch(p -> p.fault().startsWith("astronomy_shop_")),
                "astronomy shop fault family present");
        assertTrue(problems.stream().anyMatch(p -> p.fault().startsWith("operator_")),
                "operator misoperation family present");
    }

    @Test
    void unknownFaultsFallBackToTitleizedRootCause() {
        assertEquals("Something New",
                AIOpsLabProblemCatalog.rootCauseOf("something_new"));
        assertEquals("K8S service targetPort misconfiguration",
                AIOpsLabProblemCatalog.rootCauseOf("k8s_target_port-misconfig-v2"),
                "prefix fallback resolves future registry variants");
    }

    @Test
    void scenariosCarryAciActionsAndGroundTruth() throws Exception {
        List<AIOpsLabProblemCatalog.Problem> problems =
                new AIOpsLabProblemCatalog().parse(fixture());
        Map<String, Object> scenario =
                AIOpsLabProblemCatalog.toScenario(problems.get(0));
        assertTrue(String.valueOf(scenario.get("domain")).startsWith("aiopslab-"));
        assertTrue(((List<?>) scenario.get("allowed_tools")).containsAll(
                List.of("get_logs", "get_metrics", "exec_shell", "submit")));
        assertEquals("external", scenario.get("agent_behavior"));
        assertEquals("aiopslab", scenario.get("adapter"));
        assertNotNull(scenario.get("root_cause"));
    }
}
