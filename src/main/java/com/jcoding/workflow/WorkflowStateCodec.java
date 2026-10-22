package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backward-compatible typed view over the durable workflow state map
 * (Requirement 3).
 *
 * <p>The controller historically drives an implicit state machine through a
 * flat {@code Map<String,Object>} of ~60 string keys, with story-reset rules
 * duplicated across call sites. This codec makes the control-relevant substate
 * explicit and gives it a single owned lifecycle without a big-bang rewrite
 * (Requirement 8 / the incremental constraint): the map remains the wire and
 * persistence format, and this class is the one place that (a) reads the typed
 * control view and (b) resets all story-scoped keys.</p>
 *
 * <p><b>Backward compatibility:</b> {@link #toState} writes only the mapped
 * keys and preserves every unknown key verbatim (no data loss); {@link #load}
 * round-trips a legacy {@code epic-<n>.json} unchanged.</p>
 */
public final class WorkflowStateCodec {

    private WorkflowStateCodec() {
    }

    /**
     * The authoritative set of story-scoped control keys. Completing or parking
     * a story clears exactly these so no state leaks into the next story. This
     * is the single definition previously duplicated inline in {@code advanceStory}.
     */
    public static final Set<String> STORY_SCOPED_KEYS = Set.of(
            "last_verified_delivery", "verification_mode", "verification_evidence", "goal_contract", "current_story_issue", "clarification", "clarification_answer", "requirements_changed", "plan_binding", "task_ledger", "task_ledger_history", "plan_replans_used", "replan_request",
            "wait_observation", "recovery_strategy", "verification_failure",
            "worktree_recovery", "worktree_baseline", "base_branch", "preflight_failure",
            "resume_guard_head", "resume_attempts",
            "resume_total_story", "resume_total_attempts",
            "resume_total_resolved_baseline",
            "feedback_guard_head", "feedback_guard_key", "feedback_attempts",
            "last_feedback_result", "last_feedback_result_head",
            "stuck_head", "stuck_head_reason",
            "stuck_finding_key",
            "feedback_reconcile_pending",
            "last_processed_commit", "last_connector_review_commit",
            "last_review_trigger_commit", "review_trigger_pending",
            "delivery_mode", "result_status", "result_evidence",
            "delivery_failure",
            "worker_failure", "held_failure", "recovery_next_prompt", "review_cache", "internal_review_findings",
            "internal_review_snapshot", "internal_review_passed",
            "validation_checkpoint", "failure_history",
            "review_findings", "active_review_finding", "active_review_findings",
            "worker_result_status", "worker_result_evidence",
            "blockers", "error", "raw_error", "message",
            "decision_action", "decision_reason", "decision_rejection",
            "blocker_class", "recovery_action", "reconciliation_attempts",
            "phase_attempt", "phase_started_at", "thread_updates",
            "last_stable_log_signature");

    /**
     * Typed view of the control-relevant substate. Immutable value carrier read
     * from the durable map; the {@link ProgressGuard.ProgressState} it holds is
     * the single source of truth for the anti-loop counters (Requirement 10).
     */
    public record WorkflowState(
            String phase,
            Integer currentStory,
            Integer currentPr,
            String branch,
            ProgressGuard.ProgressState progress) {
    }

    /** Read the typed control view from the durable map. Never mutates it. */
    public static WorkflowState load(Map<String, Object> state) {
        Integer story = state.get("current_story") == null ? null
                : Engine.asInt(state.get("current_story"));
        Integer pr = state.get("current_pr") == null ? null
                : Engine.asInt(state.get("current_pr"));
        ProgressGuard.ProgressState progress = new ProgressGuard.ProgressState(
                Engine.asString(state.get("resume_guard_head")),
                (int) Engine.asLongOrDefault(state.get("resume_attempts"), 0),
                Engine.asString(state.get("resume_total_story")),
                (int) Engine.asLongOrDefault(state.get("resume_total_attempts"), 0),
                (int) Engine.asLongOrDefault(state.get("resume_total_resolved_baseline"), 0));
        return new WorkflowState(
                Engine.asString(state.get("phase")), story, pr,
                Engine.asString(state.get("branch")), progress);
    }

    /**
     * Round-trip a durable map through the typed view, preserving every key not
     * owned by the typed model (backward compatibility / TS-7). Returns a new
     * map; the input is not mutated.
     */
    public static Map<String, Object> roundTrip(Map<String, Object> state) {
        WorkflowState typed = load(state);
        Map<String, Object> result = new LinkedHashMap<>(state);
        writeProgress(result, typed.progress());
        return result;
    }

    /** Mirror a {@link ProgressGuard.ProgressState} back into the durable map keys. */
    public static void writeProgress(Map<String, Object> state, ProgressGuard.ProgressState progress) {
        if (progress.sameHeadGuardHead().isEmpty()) {
            state.remove("resume_guard_head");
        } else {
            state.put("resume_guard_head", progress.sameHeadGuardHead());
        }
        state.put("resume_attempts", progress.sameHeadAttempts());
        state.put("resume_total_story", progress.cumulativeStory());
        state.put("resume_total_attempts", progress.cumulativeAttempts());
        state.put("resume_total_resolved_baseline", progress.resolvedBaseline());
    }

    /**
     * The single mutation point that resets all story-scoped control state.
     * Called when a story completes or is parked so nothing leaks into the next
     * story. Non-story-scoped keys (e.g. {@code completed_stories}, {@code phase})
     * are the caller's responsibility, matching the prior inline behavior.
     */
    public static void clearStoryScoped(Map<String, Object> state) {
        for (String key : STORY_SCOPED_KEYS) {
            state.remove(key);
        }
    }
}
