package com.agentlens.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1 断言代数: turns scenario {@code success_assertions} strings into
 * executable, deterministic checks against the agent's final answer.
 *
 * <p>DSL (all specs are single strings; plain strings keep their historic
 * meaning of "the answer must contain this text"):
 * <pre>
 *   root cause is OOMKilled        - must contain (case-insensitive)
 *   not-OutOfMemoryError           - must NOT contain
 *   w3:checkout latency            - weight 3 (partial-credit scoring)
 *   2-of: pods Ready | service up  - at least 2 of the alternatives must appear
 *   w2:not-fabricated              - weight composes with negation
 * </pre>
 *
 * <p>Scoring: weighted pass ratio sum(weight*satisfied)/sum(weight) in [0,1];
 * passing requires EVERY assertion satisfied. Parsing is strict - an invalid
 * spec is surfaced as a failed evaluation by the evaluator, never silently
 * ignored (a broken corpus must fail loudly).
 */
public final class SuccessAssertions {

    private static final Pattern WEIGHT_PREFIX = Pattern.compile("^w(\\d+):(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern K_OF_N = Pattern.compile("^([1-9]\\d*)-of:(.+)$");

    /** One parsed assertion: either a literal check or a k-of-n set. */
    public record Assertion(String spec, boolean negated, int weight,
                            List<String> alternatives, int minMatches) {
    }

    /** Per-assertion outcome kept for evidence chains. */
    public record Outcome(String spec, boolean negated, int weight, boolean satisfied) {
    }

    /** Aggregate result: weighted score plus strict all-satisfied verdict. */
    public record Result(double score, boolean passed, List<Outcome> outcomes) {
    }

    private SuccessAssertions() {
    }

    /** Parses the scenario DSL; blank specs are skipped, invalid ones throw. */
    public static List<Assertion> parse(List<String> specs) {
        if (specs == null) return List.of();
        List<Assertion> parsed = new ArrayList<>();
        for (String raw : specs) {
            String spec = raw == null ? "" : raw.trim();
            if (!spec.isEmpty()) parsed.add(parseOne(spec));
        }
        return List.copyOf(parsed);
    }

    /**
     * Evaluates parsed assertions against the final answer (null-safe). Both
     * sides are identifier-normalized before matching: lower-case, underscores
     * and hyphens read as spaces, whitespace collapsed - so a corpus may declare
     * snake_case assertions ({@code identify_root_cause}) against prose answers
     * ("root cause identified ...") without literal-underscore artifacts.
     */
    public static Result evaluate(List<Assertion> assertions, String answer) {
        String haystack = normalize(answer == null ? "" : answer);
        double weightSum = 0;
        double weightPassed = 0;
        boolean all = true;
        List<Outcome> outcomes = new ArrayList<>();
        for (Assertion assertion : assertions) {
            boolean satisfied;
            if (assertion.minMatches() > 1 || assertion.alternatives().size() > 1) {
                long hits = assertion.alternatives().stream()
                        .filter(a -> haystack.contains(normalize(a)))
                        .count();
                satisfied = hits >= assertion.minMatches();
            } else {
                satisfied = haystack.contains(normalize(assertion.alternatives().get(0)));
            }
            if (assertion.negated()) satisfied = !satisfied;
            weightSum += assertion.weight();
            if (satisfied) weightPassed += assertion.weight();
            all &= satisfied;
            outcomes.add(new Outcome(assertion.spec(), assertion.negated(),
                    assertion.weight(), satisfied));
        }
        return new Result(weightSum == 0 ? 1.0 : weightPassed / weightSum, all,
                List.copyOf(outcomes));
    }

    /** Identifier-tolerant text form: lower-case, {@code _}/{@code -} as spaces, collapsed. */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Assertion parseOne(String spec) {
        int weight = 1;
        String rest = spec;
        Matcher weightMatcher = WEIGHT_PREFIX.matcher(rest);
        if (weightMatcher.matches()) {
            weight = Integer.parseInt(weightMatcher.group(1));
            if (weight < 1 || weight > 9) {
                throw new IllegalArgumentException("invalid assertion '" + spec
                        + "': weight must be 1..9");
            }
            rest = weightMatcher.group(2).trim();
        }
        boolean negated = rest.startsWith("not-");
        if (negated) rest = rest.substring(4).trim();

        Matcher set = K_OF_N.matcher(rest);
        if (set.matches()) {
            int k = Integer.parseInt(set.group(1));
            List<String> alternatives = splitAlternatives(set.group(2));
            if (k > alternatives.size()) {
                throw new IllegalArgumentException("invalid assertion '" + spec
                        + "': k-of expects k <= " + alternatives.size() + " alternatives");
            }
            return new Assertion(spec, negated, weight, alternatives, k);
        }
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("invalid assertion '" + spec + "': empty text");
        }
        if (rest.contains("|")) {
            throw new IllegalArgumentException("invalid assertion '" + spec
                    + "': '|' alternatives need a '<k>-of:' prefix");
        }
        return new Assertion(spec, negated, weight, List.of(rest), 1);
    }

    private static List<String> splitAlternatives(String joined) {
        List<String> alternatives = new ArrayList<>();
        for (String part : joined.split("\\|")) {
            String alternative = part.trim();
            if (alternative.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid assertion: empty '|' alternative in '" + joined + "'");
            }
            alternatives.add(alternative);
        }
        return List.copyOf(alternatives);
    }
}
