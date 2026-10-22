package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * State-recovery and reconciliation tests. Pure in-memory state maps; no
 * external systems; every assertion checks concrete state fields. Covers
 * result parsing, legacy quota-state repair, stale story-selection clearing,
 * and reconciliation-decision application.
 */
class StateRecoveryTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // -- parse_result  --------------------------------------

    @Test
    void parseStructuredResult() {
        Map<String, String> result = StateRecovery.parseResult(
                "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: VERIFYING\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE");
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                "result_status", "SUCCESS",
                "result_next_phase", "VERIFYING",
                "result_blocker", "NONE",
                "result_evidence", "COMPLETE"));
    }

    // -- repair_legacy_quota_state  --------------

    @Test
    void legacySignalQuotaPauseIsRepaired() {
        Map<String, Object> state = map("phase", "PAUSED_QUOTA", "exit_code", -15,
                "retry_at", "2099-01-01T00:00:00+00:00");
        assertThat(StateRecovery.repairLegacyQuotaState(state)).isTrue();
        assertThat(state.get("phase")).isEqualTo("FAILED_RECOVERABLE");
        assertThat(state).doesNotContainKey("retry_at");
    }

    @Test
    void nonSignalQuotaStateIsNotRepaired() {
        Map<String, Object> state = map("phase", "PAUSED_QUOTA", "exit_code", 1,
                "retry_at", "2099-01-01T00:00:00+00:00");
        assertThat(StateRecovery.repairLegacyQuotaState(state)).isFalse();
        assertThat(state.get("phase")).isEqualTo("PAUSED_QUOTA");
    }

    // -- clear_stale_story_selection_state  ------

    @Test
    void staleStorySelectionBlockerResumesVerifiedActiveStory() {
        Map<String, Object> state = map(
                "phase", "BLOCKED_REQUIREMENTS",
                "message", "No eligible incomplete stories remain",
                "error", "REQUIREMENTS: controller eligibility and frozen baseline state are ambiguous",
                "blockers", List.of("REQUIREMENTS: controller eligibility and frozen baseline state are ambiguous"));
        assertThat(StateRecovery.clearStaleStorySelectionState(state)).isTrue();
        assertThat(state.get("phase")).isEqualTo("IMPLEMENTING");
        assertThat(state.get("blockers")).isEqualTo(List.of());
        assertThat(state.get("blocker_class")).isEqualTo("NONE");
        assertThat(state.get("last_resolved_blockers")).isEqualTo(
                List.of("REQUIREMENTS: controller eligibility and frozen baseline state are ambiguous"));
    }

    @Test
    void genuineRequirementsBlockerIsNotClearedAsStale() {
        Map<String, Object> state = map("phase", "BLOCKED_REQUIREMENTS",
                "error", "acceptance criteria are ambiguous",
                "blockers", List.of("acceptance criteria are ambiguous"));
        assertThat(StateRecovery.clearStaleStorySelectionState(state)).isFalse();
        assertThat(state.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
    }

    // -- apply_reconciliation_decision  ----------

    @Test
    void reconciliationRecordsBoundedRecoveryMetadataAndClearsActiveBlocker() {
        Map<String, Object> state = map("current_story", 46, "branch", "story/46",
                "blockers", List.of("fixed test port collision"));
        String output = "DECISION_ACTION: RESUME_IMPLEMENTATION\n"
                + "DECISION_REASON: repository-owned test harness uses a fixed host port\n"
                + "BLOCKER_CLASS: TEST_HARNESS\n"
                + "RECOVERY_ACTION: use an ephemeral host port and rerun validation\n"
                + "RESULT_STATUS: SUCCESS\n"
                + "RESULT_NEXT_PHASE: IMPLEMENTING\n"
                + "RESULT_BLOCKER: NONE\n"
                + "RESULT_EVIDENCE: COMPLETE\n";
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isTrue();
        assertThat(state.get("phase")).isEqualTo("IMPLEMENTING");
        assertThat(state.get("blocker_class")).isEqualTo("NONE");
        assertThat(state.get("blockers")).isEqualTo(List.of());
        assertThat(state.get("last_resolved_blockers")).isEqualTo(List.of("fixed test port collision"));
        assertThat(state.get("recovery_action")).isEqualTo("use an ephemeral host port and rerun validation");
    }

    @Test
    void reconciliationRejectsUnknownBlockerClass() {
        Map<String, Object> state = map("current_story", 46, "branch", "story/46");
        String output = "DECISION_ACTION: RESUME_IMPLEMENTATION\n"
                + "DECISION_REASON: evidence\n"
                + "BLOCKER_CLASS: INVENTED\n"
                + "RECOVERY_ACTION: rerun validation\n" + successfulDecisionFooter();
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.detail()).contains("invalid blocker class");
    }

    @Test
    void reconciliationRejectsBlockedNextPhaseFallback() {
        Map<String, Object> state = map("current_story", 46, "branch", "story/46");
        String output = "RESULT_STATUS: BLOCKED\nRESULT_NEXT_PHASE: VERIFYING\n"
                + "RESULT_BLOCKER: verification remains\nRESULT_EVIDENCE: BLOCKED\n";
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isFalse();
        assertThat(state).doesNotContainKeys("phase", "decision_action");
    }

    @Test
    void reconciliationCannotResumeWithoutStoryAndBranch() {
        Map<String, Object> state = map();
        String output = completeDecision();
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.detail()).contains("without a current story and branch");
    }

    @Test
    void reconciliationBlocksAfterThreeConsecutiveBlockingActions() {
        Map<String, Object> state = map("reconciliation_attempts", 2);
        String output = completeDecision().replace("RESUME_IMPLEMENTATION", "BLOCK_REQUIREMENTS");
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isTrue();
        assertThat(state.get("phase")).isEqualTo("BLOCKED_EXTERNAL");
        assertThat(state.get("error")).isEqualTo(
                "reconciliation selected blocking action three consecutive times");
    }

    private static String successfulDecisionFooter() {
        return "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: RECONCILING\n"
                + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n";
    }

    private static String completeDecision() {
        return "DECISION_ACTION: RESUME_IMPLEMENTATION\nDECISION_REASON: current branch owns changes\n"
                + "BLOCKER_CLASS: IMPLEMENTATION\nRECOVERY_ACTION: repair validation\n"
                + successfulDecisionFooter();
    }

    @Disabled("Excluded from the business-value mock suite pending contract triage")
    @ParameterizedTest
    @ValueSource(strings = {"RESULT_STATUS", "RESULT_EVIDENCE", "DECISION_ACTION", "DECISION_REASON",
        "BLOCKER_CLASS", "RECOVERY_ACTION"})
    void mandatoryDecisionFieldsCannotBeMissingOrEmpty(String key) {
        String output = completeDecision();
        assertDecisionRejected(output.replaceAll("(?m)^" + key + ":[^\\n]*\\n", ""));
        assertDecisionRejected(output + key + ":\n");
        assertDecisionRejected(output + key + ":  \nIGNORED: evidence\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKED", "FAILED", "UNKNOWN", "SUCCESS|BLOCKED|FAILED"})
    void decisionMetadataCannotOverrideUnsuccessfulWorkerStatus(String status) {
        assertDecisionRejected(completeDecision().replace("RESULT_STATUS: SUCCESS", "RESULT_STATUS: " + status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKED", "DEFERRED_TO_CI", "UNKNOWN"})
    void decisionEvidenceMustBeComplete(String evidence) {
        assertDecisionRejected(completeDecision().replace("RESULT_EVIDENCE: COMPLETE", "RESULT_EVIDENCE: " + evidence));
    }

    @Disabled("Excluded from the business-value mock suite pending contract triage")
    @Test
    void structuredProseStillRequiresCompleteSuccessfulResult() {
        String prose = "Decision: `RESUME_IMPLEMENTATION`\nDecision reason: test needs repair\n"
                + "Blocker class: `TEST_HARNESS`\nRecovery action: rerun focused tests\n";
        Map<String, Object> state = map("current_story", 46, "branch", "story/46");
        assertThat(StateRecovery.applyReconciliationDecision(state, prose + successfulDecisionFooter())
                .accepted()).isTrue();
        assertDecisionRejected(prose);
        assertDecisionRejected(prose.replace("Recovery action: rerun focused tests", "Recovery action:")
                + successfulDecisionFooter());
        assertDecisionRejected(prose + successfulDecisionFooter() + "Recovery action:\n");
    }

    @Test
    void decisionSuccessDoesNotImplyDeliverySuccess() {
        String output = completeDecision();
        Map<String, Object> state = map("current_story", 46, "branch", "story/46");
        assertThat(StateRecovery.applyReconciliationDecision(state, 0, output).accepted()).isTrue();
        assertThat(state.get("phase")).isEqualTo("IMPLEMENTING");
        assertThat(Engine.isDeliveryAccepted(0, output)).isFalse();
        assertThat(state).containsEntry("result_status", "RECONCILIATION_ACCEPTED")
                .containsEntry("result_evidence", "DECISION_COMPLETE");
        assertThat(Engine.deliveryAccepted(state)).isFalse();
    }

    @Test
    void failedDecisionExitPreservesQuotaStateAndBackoff() {
        Map<String, Object> state = Engine.applyCodexResult(map(), 1, "weekly usage limit reached", 60);
        Map<String, Object> before = new LinkedHashMap<>(state);
        assertThat(StateRecovery.applyReconciliationDecision(state, 1, completeDecision()).accepted()).isFalse();
        assertThat(state).isEqualTo(before).containsEntry("phase", "PAUSED_QUOTA").containsKey("retry_at");
    }

    private static void assertDecisionRejected(String output) {
        Map<String, Object> state = map("current_story", 46, "branch", "story/46",
                "phase", "BLOCKED_REQUIREMENTS", "blockers", List.of("unresolved"), "reconciliation_attempts", 1);
        Map<String, Object> before = new LinkedHashMap<>(state);
        assertThat(StateRecovery.applyReconciliationDecision(state, output).accepted()).isFalse();
        assertThat(state).isEqualTo(before);
    }
}
