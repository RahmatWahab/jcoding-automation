package com.rahmatwahab.codexautomation.workflow;

/**
 * Exact workflow phases preserved from the frozen Python automation contract.
 */
public enum WorkflowPhase {
    IDLE,
    IMPLEMENTING,
    VERIFYING,
    REVIEWING_CODE,
    WAITING_FOR_CI,
    WAITING_FOR_CODEX_REVIEW,
    WAITING_FOR_HUMAN_REVIEW,
    CHANGES_REQUESTED,
    FIXING_FEEDBACK,
    READY_TO_MERGE,
    WAITING_FOR_MANUAL_MERGE,
    PAUSED_QUOTA,
    BLOCKED_REQUIREMENTS,
    BLOCKED_EXTERNAL,
    CONFLICT_DETECTED,
    RECONCILING,
    EPIC_COMPLETE,
    FAILED_RECOVERABLE,
    CODEX_FAILURE
}
