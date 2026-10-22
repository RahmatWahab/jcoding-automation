package com.jcoding.workflow;

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

    /** Explicit serial delivery policy: an unfinished PR must not be abandoned. */
    public static boolean finishOpenPrFirst(Map<String, Object> workflow) {
        return Engine.asBool(workflow.get("finish_open_pr_before_next_story"), false);
    }

    public static boolean mayAdvance(Map<String, Object> workflow, Map<String, Object> state) {
        return enabled(workflow) && !(finishOpenPrFirst(workflow) && state.get("current_pr") != null);
    }

    /** Resume an explicitly prioritized open PR even if an old run parked this head. */
    public static void resumeForOpenPr(Map<String, Object> state, int story) {
        Object value = records(state).get(String.valueOf(story));
        if (!(value instanceof Map<?, ?> record)) return;
        if (record.get("checkpoint") instanceof Map<?, ?> checkpoint) {
            checkpoint.forEach((key, item) -> state.put(String.valueOf(key), item));
        }
        Map<String, Object> remaining = new LinkedHashMap<>(records(state));
        remaining.remove(String.valueOf(story));
        state.put(STATE_KEY, remaining);
        state.remove("held_failure");
        state.remove("stuck_head");
        state.remove("stuck_head_reason");
        state.put("recovery_next_prompt", "prompts/reconcile.md");
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

    /** Preserve recovery evidence separately from active state, never in completed_stories. */
    public static void parkWithCheckpoint(Map<String, Object> state, int story, String head,
                                          String reason, String timestamp) {
        park(state, story, head, reason, timestamp);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(WorkflowStateCodec.STORY_SCOPED_KEYS);
        keys.addAll(List.of("branch", "base_commit", "current_pr", "current_pr_head", "current_pr_url",
                "review_findings", "active_review_findings", "active_review_finding"));
        for (String key : keys) {
            if (state.containsKey(key)) checkpoint.put(key, state.get(key));
        }
        @SuppressWarnings("unchecked") Map<String, Object> record =
                (Map<String, Object>) records(state).get(String.valueOf(story));
        record.put("checkpoint", checkpoint);
    }

    /** A new PR head wakes parked work; cumulative story budgets intentionally survive. */
    public static void restoreCheckpoint(Map<String, Object> state, int story, String head) {
        Object existing = records(state).get(String.valueOf(story));
        if (!(existing instanceof Map<?, ?> record) || head.equals(record.get("head"))) return;
        if (record.get("checkpoint") instanceof Map<?, ?> checkpoint) {
            checkpoint.forEach((key, value) -> state.put(String.valueOf(key), value));
        }
        state.remove("held_failure");
        state.remove("stuck_head");
        state.remove("stuck_head_reason");
        state.put("recovery_next_prompt", "prompts/reconcile.md");
        unparkIfHeadAdvanced(state, story, head);
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
