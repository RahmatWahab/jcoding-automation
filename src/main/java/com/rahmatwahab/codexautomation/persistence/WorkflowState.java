package com.rahmatwahab.codexautomation.persistence;

/**
 * Minimal state identity; later stories add phase-specific fields and migrations.
 */
public record WorkflowState(String executionId) {
}
