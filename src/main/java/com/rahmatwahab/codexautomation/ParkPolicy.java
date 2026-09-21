package com.rahmatwahab.codexautomation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Pure park-and-advance policy (Requirement 5), config-gated.
 *
 * <p>When {@code workflow.terminal_policy} is {@code "park_and_advance"}, a
 * story that reaches a terminal unrecoverable state on its current head is
 * recorded as parked (head, reason, timestamp) rather than held forever. A
 * parked story is excluded from selection and is automatically un-parked when
 * its head advances (a new commit lands). The default policy is {@code "hold"},
 * under which none of this activates and the parked set stays empty — so the
 * current behavior is preserved exactly until the policy is enabled.</p>
 *
 * <p>All methods are pure functions over the durable state map (no I/O), so the
 * policy is exhaustively unit-testable.</p>
 */
public final class ParkPolicy {

    private ParkPolicy() {
    }

    /** State key holding the parked-story records (story number -> record). */
    public static final String STATE_KEY = "parked_stories";

    /** Whether the park-and-advance policy is enabled by config. */
    public static boolean enabled(Map<String, Object> workflow) {
        return "park_and_advance".equalsIgnoreCase(
                Engine.asString(workflow.getOrDefault("terminal_policy", "hold")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> records(Map<String, Object> state) {
        Object existing = state.get(STATE_KEY);
        if (existing instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new LinkedHashMap<>();
    }

    /**
     * Record a story as parked on the given head. Returns true if newly parked
     * (so the caller can post a single operator notification). Idempotent: a
     * story already parked on the same head is not re-recorded.
     */
    public static boolean park(Map<String, Object> state, int story, String head,
                               String reason, String timestamp) {
        Map<String, Object> records = new LinkedHashMap<>(records(state));
        String key = String.valueOf(story);
        Object existing = records.get(key);
        if (existing instanceof Map<?, ?> rec && head.equals(Engine.asString(rec.get("head")))) {
            return false; // already parked on this head
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("head", head);
        record.put("reason", reason);
        record.put("parked_at", timestamp);
        records.put(key, record);
        state.put(STATE_KEY, records);
        return true;
    }

    /** The set of story numbers currently parked. */
    public static Set<Integer> parkedStoryNumbers(Map<String, Object> state) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String key : records(state).keySet()) {
            try {
                result.add(Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
                // skip malformed keys
            }
        }
        return result;
    }

    /** The head a story is parked on, or empty if not parked. */
    public static String parkedHead(Map<String, Object> state, int story) {
        Object rec = records(state).get(String.valueOf(story));
        return rec instanceof Map<?, ?> m ? Engine.asString(m.get("head")) : "";
    }

    /**
     * Un-park a story whose head has advanced (new commit != parked head).
     * Returns true if it was un-parked. A story parked on the same head stays
     * parked; an unknown story is a no-op.
     */
    public static boolean unparkIfHeadAdvanced(Map<String, Object> state, int story, String currentHead) {
        Map<String, Object> current = records(state);
        String key = String.valueOf(story);
        Object rec = current.get(key);
        if (!(rec instanceof Map<?, ?> m)) {
            return false;
        }
        String parkedHead = Engine.asString(m.get("head"));
        if (!currentHead.isEmpty() && !currentHead.equals(parkedHead)) {
            Map<String, Object> updated = new LinkedHashMap<>(current);
            updated.remove(key);
            state.put(STATE_KEY, updated);
            return true;
        }
        return false;
    }

    /**
     * Filter a candidate story list, removing any currently-parked story. Used
     * by selection so a parked story is not re-selected. When the policy is off
     * the parked set is empty and this returns the input unchanged.
     */
    public static List<Map<String, Object>> excludeParked(
            List<Map<String, Object>> stories, Map<String, Object> state) {
        Set<Integer> parked = parkedStoryNumbers(state);
        if (parked.isEmpty()) {
            return stories;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> s : stories) {
            if (!parked.contains(Engine.asInt(s.get("number")))) {
                result.add(s);
            }
        }
        return result;
    }
}
