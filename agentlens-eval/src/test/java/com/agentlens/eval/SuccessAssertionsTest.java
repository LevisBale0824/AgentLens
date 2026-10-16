package com.agentlens.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1 断言代数: DSL parsing and deterministic evaluation of success assertions. */
class SuccessAssertionsTest {

    @Test
    void plainSpecIsCaseInsensitiveMustContain() {
        var parsed = SuccessAssertions.parse(List.of("Root Cause Is OOMKilled"));
        assertEquals(1, parsed.size());
        var result = SuccessAssertions.evaluate(parsed, "root cause is oomkilled in adservice");
        assertTrue(result.passed());
        assertEquals(1.0, result.score(), 1e-9);
        assertFalse(SuccessAssertions.evaluate(parsed, "nothing here").outcomes().get(0).satisfied(),
                "absent text must not satisfy");
        assertFalse(SuccessAssertions.evaluate(parsed, "nothing here").passed());
    }

    @Test
    void negationWeightAndComposition() {
        var parsed = SuccessAssertions.parse(List.of("not-OutOfMemory", "w3:checkout", "w2:not-fabricated"));
        assertEquals(3, parsed.size());
        assertTrue(parsed.get(0).negated());
        assertEquals(3, parsed.get(1).weight());
        assertTrue(parsed.get(2).negated());

        // checkout present, neither forbidden string present -> 4/4 weight
        var pass = SuccessAssertions.evaluate(parsed, "checkout latency improved; clean answer");
        assertTrue(pass.passed());
        assertEquals(1.0, pass.score(), 1e-9);
        // "outofmemory" mentioned -> not- fails (weight 1); checkout + not-fabricated pass (3+2)
        var partial = SuccessAssertions.evaluate(parsed, "checkout ok after OutOfMemoryError");
        assertFalse(partial.passed());
        assertEquals(5.0 / 6, partial.score(), 1e-9);
    }

    @Test
    void kOfNSetCountsAlternatives() {
        var parsed = SuccessAssertions.parse(List.of("2-of: pods Ready | service up | no restarts"));
        assertEquals(List.of("pods Ready", "service up", "no restarts"), parsed.get(0).alternatives());
        assertEquals(2, parsed.get(0).minMatches());
        assertTrue(SuccessAssertions.evaluate(parsed, "pods Ready and service up observed").passed());
        assertFalse(SuccessAssertions.evaluate(parsed, "only service up mentioned").passed());
    }

    @Test
    void negatedSetRequiresFewerThanK() {
        var parsed = SuccessAssertions.parse(List.of("not-2-of: panic | crashloop | oom"));
        assertTrue(SuccessAssertions.evaluate(parsed, "clean run").passed());
        assertFalse(SuccessAssertions.evaluate(parsed, "panic and crashloop seen").passed());
    }

    @Test
    void invalidSpecsAreRejectedLoudly() {
        assertThrows(IllegalArgumentException.class, () -> SuccessAssertions.parse(List.of("w0:bad")));
        assertThrows(IllegalArgumentException.class, () -> SuccessAssertions.parse(List.of("not-")));
        assertThrows(IllegalArgumentException.class, () -> SuccessAssertions.parse(List.of("3-of:a|b")));
        assertThrows(IllegalArgumentException.class, () -> SuccessAssertions.parse(List.of("2-of:a||b")));
        assertThrows(IllegalArgumentException.class, () -> SuccessAssertions.parse(List.of("a|b")));
    }

    @Test
    void snakeCaseAssertionsMatchProseAnswers() {
        // corpus declarations are identifier-style; answers are natural text.
        // contains() stays order-sensitive, so the declaration must phrase the
        // words the way the answer does - normalization only erases the
        // underscore/hyphen/case artifacts, as ops-scenarios.json relies on.
        var parsed = SuccessAssertions.parse(List.of("identify_root_cause"));
        assertTrue(SuccessAssertions.evaluate(parsed, "we identify root cause from logs").passed());
        assertFalse(SuccessAssertions.evaluate(parsed, "root cause identified").passed());
        // hyphenated evidence also normalizes into the same token stream
        var kebab = SuccessAssertions.parse(List.of("oom_kill"));
        assertTrue(SuccessAssertions.evaluate(kebab, "pod OOM-killed twice").passed());
        // negation keeps working across the normalization
        var negated = SuccessAssertions.parse(List.of("not-delete_namespace"));
        assertTrue(SuccessAssertions.evaluate(negated, "root cause identified").passed());
        assertFalse(SuccessAssertions.evaluate(negated, "executed delete-namespace").passed());
    }

    @Test
    void blankSpecsSkippedAndNullAnswerSafe() {
        assertEquals(0, SuccessAssertions.parse(List.of("  ", "")).size());
        var result = SuccessAssertions.evaluate(SuccessAssertions.parse(List.of("x")), null);
        assertFalse(result.passed());
        assertEquals(0.0, result.score(), 1e-9);
        assertEquals(1.0, SuccessAssertions.evaluate(List.of(), "anything").score(), 1e-9);
    }
}
