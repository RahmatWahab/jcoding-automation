package com.rahmatwahab.codexautomation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowPhaseTest {
    @Test
    @DisplayName("Should preserve frozen Python workflow phase contract")
    void shouldPreserveFrozenPythonWorkflowPhaseContract() {
        var phases = Arrays.stream(WorkflowPhase.values())
                .map(Enum::name)
                .toList();

        assertThat(phases).containsExactly(
                "IDLE",
                "IMPLEMENTING",
                "VERIFYING",
                "REVIEWING_CODE",
                "WAITING_FOR_CI",
                "WAITING_FOR_CODEX_REVIEW",
                "WAITING_FOR_HUMAN_REVIEW",
                "CHANGES_REQUESTED",
                "FIXING_FEEDBACK",
                "READY_TO_MERGE",
                "WAITING_FOR_MANUAL_MERGE",
                "PAUSED_QUOTA",
                "BLOCKED_REQUIREMENTS",
                "BLOCKED_EXTERNAL",
                "CONFLICT_DETECTED",
                "RECONCILING",
                "EPIC_COMPLETE",
                "FAILED_RECOVERABLE",
                "CODEX_FAILURE"
        );
    }
}
