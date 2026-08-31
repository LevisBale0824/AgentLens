package com.agentlens.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for AgentLensApp.parseOptions (regression for the boolean-flag
 * parsing bug found during the first live DSH e2e run, see
 * docs/reports/dsh-live-e2e.md problem 2).
 */
class ParseOptionsTest {

    @Test
    void parsesValueFlagsAfterCommandWord() {
        Map<String, String> opts = AgentLensApp.parseOptions(
                new String[]{"run", "--scenarios", "data/x.json", "--repeats", "3"}, "run");
        assertEquals("data/x.json", opts.get("scenarios"));
        assertEquals("3", opts.get("repeats"));
    }

    @Test
    void parsesBooleanFlagAtEnd() {
        Map<String, String> opts = AgentLensApp.parseOptions(new String[]{"run", "--live"}, "run");
        assertEquals("true", opts.get("live"));
        assertEquals(1, opts.size());
    }

    @Test
    void booleanFlagFollowedByAnotherFlagDoesNotSwallowIt() {
        // Regression: --live used to consume "--repeats" as its value and drop "1".
        Map<String, String> opts = AgentLensApp.parseOptions(
                new String[]{"run", "--live", "--repeats", "1"}, "run");
        assertEquals("true", opts.get("live"));
        assertEquals("1", opts.get("repeats"));
    }

    @Test
    void firstTokenFlagMeansThereIsNoCommandWord() {
        Map<String, String> opts = AgentLensApp.parseOptions(
                new String[]{"--scenarios", "data/x.json", "--live"}, "run");
        assertEquals("data/x.json", opts.get("scenarios"));
        assertEquals("true", opts.get("live"));
    }

    @Test
    void unknownFirstWordIsIgnoredAsPositional() {
        Map<String, String> opts = AgentLensApp.parseOptions(
                new String[]{"weird", "--db", "out.db"}, "run");
        assertEquals("out.db", opts.get("db"));
    }

    @Test
    void singleDashNegativeNumberIsAValueNotAFlag() {
        Map<String, String> opts = AgentLensApp.parseOptions(
                new String[]{"--temperature", "-0.4"}, "run");
        assertEquals("-0.4", opts.get("temperature"));
    }

    @Test
    void duplicateFlagLastWins() {
        Map<String, String> opts = AgentLensApp.parseOptions(
                new String[]{"run", "--repeats", "2", "--repeats", "5"}, "run");
        assertEquals("5", opts.get("repeats"));
    }

    @Test
    void emptyArgsProduceEmptyOptions() {
        Map<String, String> opts = AgentLensApp.parseOptions(new String[]{}, "run");
        assertTrue(opts.isEmpty());
    }
}
