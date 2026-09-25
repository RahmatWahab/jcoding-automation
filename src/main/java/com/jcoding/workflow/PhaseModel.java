package com.jcoding.workflow;

import java.util.Set;

/**
 * Enumerated workflow phases and controller vocabularies.
 *
 * <p>Pure workflow logic
 * ({@code PHASES}, {@code DECISION_ACTIONS}, {@code BLOCKER_CLASSES}). The
 * controller accepts only enumerated values; unknown values fail closed.</p>
 */
public final class PhaseModel {

    private PhaseModel() {
    }

    /** All recognized workflow phases. */
    public static final Set<String> PHASES = Set.of(
            "IDLE", "PLANNING", "IMPLEMENTING", "VERIFYING", "REVIEWING_CODE", "WAITING_FOR_CI",
            "WAITING_FOR_CODEX_REVIEW", "WAITING_FOR_HUMAN_REVIEW", "CHANGES_REQUESTED",
            "FIXING_FEEDBACK", "READY_TO_MERGE", "WAITING_FOR_MANUAL_MERGE", "PAUSED_QUOTA",
            "BLOCKED_REQUIREMENTS", "BLOCKED_EXTERNAL", "CONFLICT_DETECTED", "RECONCILING",
            "EPIC_COMPLETE", "FAILED_RECOVERABLE", "CODEX_FAILURE");

    /** Reconciliation decision actions. */
    public static final Set<String> DECISION_ACTIONS = Set.of(
            "RESUME_CURRENT_STORY", "RESUME_IMPLEMENTATION", "RESUME_VERIFICATION",
            "RESUME_REVIEW", "PROCESS_FEEDBACK", "WAIT_FOR_CI", "WAIT_FOR_HUMAN_MERGE",
            "PAUSE_QUOTA", "BLOCK_REQUIREMENTS", "BLOCK_EXTERNAL");

    /** Blocker classifications. */
    public static final Set<String> BLOCKER_CLASSES = Set.of(
            "NONE", "IMPLEMENTATION", "TEST_HARNESS", "VALIDATION", "REVIEW_FEEDBACK",
            "CI", "QUOTA", "PROCESS", "EXTERNAL", "REQUIREMENTS");
}
