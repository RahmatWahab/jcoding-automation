package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
                "result_blocker", "NONE"));
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
                + "BLOCKER_CLASS: INVENTED\n";
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.detail()).contains("invalid blocker class");
    }

    @Test
    void reconciliationAcceptsExplicitNextPhaseFallback() {
        Map<String, Object> state = map("current_story", 46, "branch", "story/46");
        String output = "RESULT_STATUS: BLOCKED\nRESULT_NEXT_PHASE: VERIFYING\n"
                + "RESULT_BLOCKER: verification remains\nRESULT_EVIDENCE: BLOCKED\n";
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isTrue();
        assertThat(state.get("phase")).isEqualTo("VERIFYING");
        assertThat(state.get("decision_action")).isEqualTo("RESUME_VERIFICATION");
    }

    @Test
    void reconciliationCannotResumeWithoutStoryAndBranch() {
        Map<String, Object> state = map();
        String output = "DECISION_ACTION: RESUME_IMPLEMENTATION\nDECISION_REASON: evidence\n";
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.detail()).contains("without a current story and branch");
    }

    @Test
    void reconciliationBlocksAfterThreeConsecutiveBlockingActions() {
        Map<String, Object> state = map("reconciliation_attempts", 2);
        String output = "DECISION_ACTION: BLOCK_REQUIREMENTS\nDECISION_REASON: genuinely ambiguous\n";
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, output);
        assertThat(outcome.accepted()).isTrue();
        assertThat(state.get("phase")).isEqualTo("BLOCKED_EXTERNAL");
        assertThat(state.get("error")).isEqualTo(
                "reconciliation selected blocking action three consecutive times");
    }
}
