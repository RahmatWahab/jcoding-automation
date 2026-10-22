package com.jcoding.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Business-value tests for the declarative workflow state machine. They protect
 * two properties: the live phase→prompt mapping is unchanged by the refactor,
 * and an undeclared transition is a detectable error rather than silent.
 */
class WorkflowMachineTest {

    @Test
    void promptMappingMatchesTheLegacySwitchExactly() {
        // Parity: every phase must dispatch the same prompt the previous
        // Controller.phasePromptFor switch produced, or the live worker path
        // changes silently.
        assertThat(WorkflowMachine.promptFor("CHANGES_REQUESTED")).isEqualTo("prompts/feedback.md");
        assertThat(WorkflowMachine.promptFor("FIXING_FEEDBACK")).isEqualTo("prompts/feedback.md");
        assertThat(WorkflowMachine.promptFor("VERIFYING")).isEqualTo("prompts/verify.md");
        assertThat(WorkflowMachine.promptFor("REVIEWING_CODE")).isEqualTo("prompts/review.md");
        assertThat(WorkflowMachine.promptFor("BLOCKED_REQUIREMENTS")).isEqualTo("prompts/reconcile.md");
        assertThat(WorkflowMachine.promptFor("BLOCKED_REPOSITORY")).isEqualTo("prompts/reconcile.md");
        assertThat(WorkflowMachine.promptFor("FAILED_RECOVERABLE")).isEqualTo("prompts/reconcile.md");
        assertThat(WorkflowMachine.promptFor("CODEX_FAILURE")).isEqualTo("prompts/reconcile.md");
        assertThat(WorkflowMachine.promptFor("READY_TO_MERGE")).isEqualTo("prompts/final-review.md");
        assertThat(WorkflowMachine.promptFor("IDLE")).isEqualTo("prompts/discover.md");
        assertThat(WorkflowMachine.promptFor("PLANNING")).isEqualTo("prompts/plan.md");
        // Default / unknown / implementation phases -> implement.md.
        assertThat(WorkflowMachine.promptFor("IMPLEMENTING")).isEqualTo("prompts/implement.md");
        assertThatThrownBy(() -> WorkflowMachine.promptFor("SOMETHING_UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowMachine.promptFor(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WorkflowMachine.isLegalTransition("UNKNOWN", "UNKNOWN")).isFalse();
    }

    @Test
    void knownPhasesPermitRetryAndUnknownPhasesDoNot() {
        // Same-phase retry is a supported recovery outcome; unknown phase names
        // must not silently enter the workflow.
        for (String phase : new String[] {
                "IDLE", "PLANNING", "IMPLEMENTING", "VERIFYING", "REVIEWING_CODE",
                "FIXING_FEEDBACK", "RECONCILING", "WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW",
                "WAITING_FOR_MANUAL_MERGE", "READY_TO_MERGE", "BLOCKED_REQUIREMENTS",
                "BLOCKED_EXTERNAL", "BLOCKED_REPOSITORY", "BLOCKED_CONFIGURATION",
                "CONFLICT_DETECTED", "FAILED_RECOVERABLE", "CODEX_FAILURE", "MERGED"}) {
            assertThat(WorkflowMachine.isLegalTransition(phase, phase))
                    .as("phase %s must support retry", phase).isTrue();
        }
        assertThat(WorkflowMachine.isLegalTransition("NOT_A_PHASE", "NOT_A_PHASE")).isFalse();
    }

    @Test
    void undeclaredTransitionIsADetectableError_ts6() {
        // TS-6: an illegal transition must raise a detectable error, not pass
        // silently. MERGED may only advance to IDLE/EPIC_COMPLETE.
        assertThatThrownBy(() -> WorkflowMachine.requireLegalTransition("MERGED", "IMPLEMENTING"))
                .isInstanceOf(WorkflowMachine.IllegalTransitionException.class)
                .hasMessageContaining("MERGED -> IMPLEMENTING");
        assertThat(WorkflowMachine.isLegalTransition("MERGED", "IMPLEMENTING")).isFalse();
    }

    @Test
    void declaredTransitionsAndRetriesAreLegal() {
        // Real transitions the engine produces must be accepted so the guard
        // never falsely rejects live behavior.
        assertThatCode(() -> {
            WorkflowMachine.requireLegalTransition("IMPLEMENTING", "VERIFYING");
            WorkflowMachine.requireLegalTransition("VERIFYING", "REVIEWING_CODE");
            WorkflowMachine.requireLegalTransition("REVIEWING_CODE", "WAITING_FOR_CI");
            WorkflowMachine.requireLegalTransition("FIXING_FEEDBACK", "RECONCILING");
            WorkflowMachine.requireLegalTransition("RECONCILING", "IMPLEMENTING");
            WorkflowMachine.requireLegalTransition("MERGED", "IDLE");
            WorkflowMachine.requireLegalTransition("IDLE", "PLANNING");
            WorkflowMachine.requireLegalTransition("PLANNING", "IMPLEMENTING");
            WorkflowMachine.requireLegalTransition("IMPLEMENTING", "IMPLEMENTING"); // retry
        }).doesNotThrowAnyException();
    }
}
