package com.agentlens.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CallId-aware detector for repeated tool calls (ROADMAP 7.2). Classifies every
 * TOOL_CALL against the previous attempt with the same call signature, so that
 * three different situations no longer collapse into one "loop" bucket:
 *
 * <ul>
 *   <li>{@link Kind#RETRY}        - same tool + equivalent arguments, previous
 *       attempt completed with an error result: a true retry loop (strongest
 *       signal);</li>
 *   <li>{@link Kind#NO_PROGRESS}  - same tool + equivalent arguments, previous
 *       attempt completed ok: possible no-progress loop (weaker signal);</li>
 *   <li>{@link Kind#PARALLEL}     - previous same-signature attempt is still
 *       unanswered: a parallel/duplicate call in flight, <b>not</b> a loop;</li>
 *   <li>{@link Kind#FIRST}        - no earlier attempt with this signature.</li>
 * </ul>
 *
 * <p>A call's <em>signature</em> is its tool name plus its attributes with
 * volatile keys ({@code callId}, {@code attempt}, {@code step}, {@code phase},
 * {@code turn}) removed — re-calling with different arguments is a distinct
 * call, and bookkeeping fields do not change the signature.
 *
 * <p>TOOL_RESULT events are attributed back to their call by {@code callId}
 * first (reliable under parallel interleaving), falling back to the most
 * recent unanswered call with the same tool name for adapters that do not
 * emit callIds.
 *
 * <p>The detector is incremental: the same instance serves streaming
 * evaluation ({@link LiveEvaluator}); batch code replays a finished run's
 * events through a fresh instance (see {@link AgentRun#repeatedToolCallAttempts()}).
 */
public final class ToolRepeatDetector {

    /** Classification of one TOOL_CALL relative to earlier identical calls. */
    public enum Kind { FIRST, RETRY, NO_PROGRESS, PARALLEL }

    /** One classified tool call with its trace position for evidence chains. */
    public record Attempt(int eventIndex, String tool, String signature, String callId,
                          Kind kind, String previousOutcome) {}

    /** Attribute keys that identify bookkeeping, not the call's payload. */
    private static final Set<String> VOLATILE_KEYS =
            Set.of("callId", "attempt", "step", "phase", "turn");

    private static final class OpenCall {
        final String tool;
        final String signature;
        final String callId;
        boolean closed;

        OpenCall(String tool, String signature, String callId) {
            this.tool = tool;
            this.signature = signature;
            this.callId = callId;
        }
    }

    private final List<Attempt> attempts = new ArrayList<>();
    private final List<OpenCall> openCalls = new ArrayList<>();
    private final Map<String, String> lastOutcomeBySignature = new java.util.HashMap<>();

    /** Feeds one trace event; only TOOL_CALL and TOOL_RESULT are meaningful. */
    public void onEvent(int eventIndex, AgentEvent event) {
        if (event.type() == EventType.TOOL_CALL && event.name() != null) onCall(eventIndex, event);
        else if (event.type() == EventType.TOOL_RESULT) onResult(event);
    }

    private void onCall(int eventIndex, AgentEvent event) {
        String signature = signatureOf(event);
        String callId = text(event.attributes().get("callId"));

        Kind kind;
        String previousOutcome = null;
        if (findUnanswered(signature) != null) {
            kind = Kind.PARALLEL;
        } else {
            previousOutcome = lastOutcomeBySignature.get(signature);
            kind = previousOutcome == null ? Kind.FIRST
                    : "error".equals(previousOutcome) ? Kind.RETRY : Kind.NO_PROGRESS;
        }
        attempts.add(new Attempt(eventIndex, event.name(), signature, callId, kind, previousOutcome));
        openCalls.add(new OpenCall(event.name(), signature, callId));
    }

    private void onResult(AgentEvent event) {
        String callId = text(event.attributes().get("callId"));
        OpenCall match = null;
        if (callId != null) {
            for (OpenCall c : openCalls) {
                if (!c.closed && callId.equals(c.callId)) { match = c; break; }
            }
        }
        if (match == null && event.name() != null) {
            for (int i = openCalls.size() - 1; i >= 0; i--) {
                OpenCall c = openCalls.get(i);
                if (!c.closed && event.name().equals(c.tool)) { match = c; break; }
            }
        }
        if (match == null) return; // result for an unknown call: ignore
        match.closed = true;
        String status = text(event.attributes().get("status"));
        lastOutcomeBySignature.put(match.signature, "error".equalsIgnoreCase(status) ? "error" : "ok");
    }

    /** All classified TOOL_CALLs, in trace order. */
    public List<Attempt> attempts() { return List.copyOf(attempts); }

    /** RETRY + NO_PROGRESS calls: the loop-proxy count (PARALLEL excluded). */
    public long repeatedCallCount() {
        return attempts.stream().filter(a -> a.kind() == Kind.RETRY || a.kind() == Kind.NO_PROGRESS).count();
    }

    /** True retry-after-error calls only. */
    public long retryCount() {
        return attempts.stream().filter(a -> a.kind() == Kind.RETRY).count();
    }

    /** Parallel duplicates excluded from loop evidence. */
    public long parallelCount() {
        return attempts.stream().filter(a -> a.kind() == Kind.PARALLEL).count();
    }

    private OpenCall findUnanswered(String signature) {
        for (OpenCall c : openCalls) {
            if (!c.closed && c.signature.equals(signature)) return c;
        }
        return null;
    }

    private static String signatureOf(AgentEvent event) {
        TreeMap<String, Object> payload = new TreeMap<>();
        event.attributes().forEach((key, value) -> {
            if (!VOLATILE_KEYS.contains(key) && value != null) payload.put(key, value);
        });
        return event.name() + "|" + payload;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
