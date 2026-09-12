package com.rahmatwahab.codexautomation.workflow;

/**
 * High-level workflow phases preserved from the Python automation contract.
 */
public enum WorkflowPhase {
    SELECT_STORY,
    IMPLEMENTING,
    VERIFYING,
    REVIEWING_CODE,
    CREATING_OR_UPDATING_PR,
    WAITING_FOR_CODEX_REVIEW,
    WAITING_FOR_HUMAN_REVIEW,
    WAITING_FOR_MANUAL_MERGE,
    BLOCKED
}
