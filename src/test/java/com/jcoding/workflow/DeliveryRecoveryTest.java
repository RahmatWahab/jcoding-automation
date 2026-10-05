package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRecoveryTest {
    @Test
    void budgetSurvivesNewFailuresAndStopsUnchangedRetries() {
        Map<String, Object> state = new LinkedHashMap<>();
        var error = new GitOperations.ProtectedPathsException(List.of("secrets/token"));
        DeliveryRecovery.record(state, error, "STAGING_PREFLIGHT", error.getMessage());
        assertThat(state.get("phase")).isEqualTo("BLOCKED_REPOSITORY");
        assertThat(state.get("blocker_class")).isEqualTo("PROCESS");
        assertThat(DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "same", 2))
                .isEqualTo("prompts/reconcile.md");
        state.put("decision_action", "RESUME_IMPLEMENTATION");
        DeliveryRecovery.applyDecision(state, true);
        assertThat(DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "same", 2))
                .isEqualTo("prompts/delivery-repair.md");
        DeliveryRecovery.record(state, error, "STAGING_PREFLIGHT", error.getMessage());
        assertThat(DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "same", 2)).isNull();
        assertThat(DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "same", 2)).isNull();
        assertThat(DeliveryRecovery.failure(state).get("attempts")).isEqualTo(2);
        assertThat(DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "changed", 2))
                .isEqualTo("prompts/reconcile.md");
    }

    @Test
    void impossibleRepairHoldsUntilEvidenceChanges() {
        Map<String, Object> state = new LinkedHashMap<>();
        DeliveryRecovery.record(state, new IllegalStateException("access denied"), "COMMIT_PUSH", "access denied");
        DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "same", 4);
        state.put("decision_action", "BLOCK_EXTERNAL");
        DeliveryRecovery.applyDecision(state, true);
        assertThat(DeliveryRecovery.nextPrompt(DeliveryRecovery.failure(state), "same", 4)).isNull();
        WorkflowStateCodec.clearStoryScoped(state);
        assertThat(DeliveryRecovery.failure(state)).isNull();
    }

    @Test
    void failedWorkerPreservesEvidenceAndOnlyRetriesItsOriginalPhaseAfterDiagnosis() {
        Map<String, Object> state = new LinkedHashMap<>();
        DeliveryRecovery.recordWorkerFailure(state, "FIXING_FEEDBACK", 124,
                "worker timed out", "test command timed out");
        Map<String, Object> failure = DeliveryRecovery.failure(state);
        assertThat(failure).containsEntry("code", "WORKER_FAILED")
                .containsEntry("exit_code", 124)
                .containsEntry("output", "worker timed out")
                .containsEntry("diagnostics", "test command timed out");
        assertThat(DeliveryRecovery.nextPrompt(failure, "tree-1", 2))
                .isEqualTo("prompts/reconcile.md");
        state.put("decision_action", "PROCESS_FEEDBACK");
        DeliveryRecovery.applyDecision(state, true);
        assertThat(DeliveryRecovery.nextPrompt(failure, "tree-1", 2))
                .isEqualTo("prompts/feedback.md");
    }
}
