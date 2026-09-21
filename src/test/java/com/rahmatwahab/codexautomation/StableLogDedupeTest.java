package com.rahmatwahab.codexautomation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business-value test for the log-flood suppression contract (Requirement 6):
 * a stable waiting/terminal state must not re-log an identical line every tick,
 * but any real change must log again.
 */
class StableLogDedupeTest {

    @Test
    void identicalConsecutiveSignatureIsSuppressed() {
        // Same waiting signature on the next tick -> repeat -> suppress to DEBUG.
        assertThat(WorkflowOrchestrator.isRepeatStableLog("WAIT|WAITING_FOR_CI|abc123|APPROVED",
                "WAIT|WAITING_FOR_CI|abc123|APPROVED")).isTrue();
    }

    @Test
    void firstSightAlwaysLogs() {
        // No previous signature -> first occurrence of a state always logs at INFO.
        assertThat(WorkflowOrchestrator.isRepeatStableLog("", "WAIT|WAITING_FOR_CI|abc123")).isFalse();
        assertThat(WorkflowOrchestrator.isRepeatStableLog(null, "WAIT|WAITING_FOR_CI|abc123")).isFalse();
    }

    @Test
    void changedStateLogsAgain() {
        // A head advance (or phase/decision change) changes the signature, so the
        // operator sees the new state at INFO rather than it being hidden.
        String prior = "WAIT|WAITING_FOR_CI|abc123|APPROVED";
        assertThat(WorkflowOrchestrator.isRepeatStableLog(prior, "WAIT|WAITING_FOR_CI|def456|APPROVED"))
                .as("head advanced").isFalse();
        assertThat(WorkflowOrchestrator.isRepeatStableLog(prior, "WAIT|READY_TO_MERGE|abc123|APPROVED"))
                .as("phase changed").isFalse();
    }

    @Test
    void emptyCurrentSignatureNeverSuppresses() {
        // A blank signature must never be treated as a repeat (fail toward
        // visibility, not silence).
        assertThat(WorkflowOrchestrator.isRepeatStableLog("", "")).isFalse();
    }
}
