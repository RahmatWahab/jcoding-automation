package com.rahmatwahab.codexautomation.workflow;

import java.util.Objects;

/**
 * Pure workflow decision produced from authoritative facts.
 */
public record WorkflowDecision(WorkflowPhase nextPhase, String reason) {
    public WorkflowDecision {
        Objects.requireNonNull(nextPhase, "nextPhase must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
