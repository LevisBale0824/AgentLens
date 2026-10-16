package com.agentlens.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** P1 scorer contract: {value(score), answer, explanation, reason} with back-compat ctor. */
class EvaluationResultTest {

    @Test
    void compatConstructorLeavesAnswerAndExplanationNull() {
        var legacy = new EvaluationResult("task-completion", true, 1.0, "ok", Map.of());
        assertNull(legacy.answer());
        assertNull(legacy.explanation());
        assertEquals("task-completion", legacy.evaluator());
        assertEquals(1.0, legacy.score(), 1e-9);
    }

    @Test
    void canonicalConstructorNormalizesBlankDimensions() {
        var result = new EvaluationResult("e", false, 0.0, "no", Map.of(),
                "  ", "   explanation with content   ");
        assertNull(result.answer());
        assertEquals("explanation with content", result.explanation());
    }

    @Test
    void excerptFlattensWhitespaceAndTruncatesWithEllipsis() {
        assertEquals(null, EvaluationResult.excerpt(null, 10));
        assertEquals("a b c", EvaluationResult.excerpt("a\n  b\tc", 10));
        String cut = EvaluationResult.excerpt("0123456789abcdef", 10);
        assertEquals(10, cut.length());
        assertEquals("012345678…", cut);
    }
}
