package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Normal-engine tests. All inputs are realistic in-memory mocks (worker output
 * strings, GitHub-shaped maps); no real GitHub or network is used, and every
 * assertion checks a concrete business value.
 *
 * <p>Covers result application, decision/result parsing, reconciliation
 * decisions, the policy matrix, actionable-feedback selection, review-thread
 * classification, and Codex review-state behavior.</p>
 */
class EngineResultTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> prBase(Object... overrides) {
        Map<String, Object> base = map(
                "state", "OPEN", "reviewThreadsAvailable", true,
                "statusCheckRollup", new ArrayList<>(), "reviewDecision", "");
        for (int i = 0; i < overrides.length; i += 2) {
            base.put((String) overrides[i], overrides[i + 1]);
        }
        return base;
    }

    // -- apply_codex_result  ---------------------

    @Test
    void successMovesToDeclaredPhase() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 0,
                "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: VERIFYING\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE");
        assertThat(result.get("phase")).isEqualTo("VERIFYING");
    }

    @Test
    void blockedWorkerResultFailsClosed() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 0,
                "RESULT_STATUS: BLOCKED\nRESULT_NEXT_PHASE: BLOCKED\nRESULT_BLOCKER: decision required");
        assertThat(result.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(result.get("result_status")).isEqualTo("BLOCKED");
        assertThat(result.get("blockers")).isEqualTo(List.of("decision required"));
    }

    @Test
    void successWithInvalidPhaseFailsClosed() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 0,
                "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: UNKNOWN\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE");
        assertThat(result.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(result.get("blockers")).isEqualTo(List.of("Codex returned SUCCESS with an invalid next phase"));
    }

    @Test
    void ciDeferredSuccessIsDeliverableAndMovesToWaitingForCi() {
        // A complete fix that defers a heavy E2E lane to CI must be a deliverable
        // success (so the controller commits/pushes), not a fail-closed block.
        Map<String, Object> result = Engine.applyCodexResult(map(), 0,
                "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: WAITING_FOR_CI\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: DEFERRED_TO_CI");
        assertThat(result.get("phase")).isEqualTo("WAITING_FOR_CI");
        assertThat(result.get("result_status")).isEqualTo("SUCCESS");
        assertThat(result.get("delivery_mode")).isEqualTo("CI_DEFERRED");
        assertThat(result.get("blockers")).isEqualTo(new ArrayList<>());
    }

    @Test
    void deferredEvidenceWithoutWaitingForCiFailsClosed() {
        // DEFERRED_TO_CI is only valid when the next phase is WAITING_FOR_CI;
        // otherwise it must not be treated as a deliverable success.
        Map<String, Object> result = Engine.applyCodexResult(map(), 0,
                "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: REVIEWING_CODE\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: DEFERRED_TO_CI");
        assertThat(result.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(result.get("delivery_mode")).isNull();
    }

    @Test
    void blockedWithDeferredEvidenceStillFailsClosed() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 0,
                "RESULT_STATUS: BLOCKED\nRESULT_NEXT_PHASE: WAITING_FOR_CI\n"
                        + "RESULT_BLOCKER: cannot fix\nRESULT_EVIDENCE: DEFERRED_TO_CI");
        assertThat(result.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(result.get("delivery_mode")).isNull();
    }

    @Test
    void cleanCodexReviewCommentAtHeadClosesGate() {
        String head = "90bb92b6304993d218f317070fbef07e236b5142";
        Map<String, Object> pr = prBase(
                "headRefOid", head,
                "reviews", new ArrayList<>(),
                "comments", List.of(map(
                        "author", map("login", "chatgpt-codex-connector[bot]"),
                        "body", "Codex Review: Didn't find any major issues. Already looking "
                                + "forward to the next diff.\n\n**Reviewed commit:** `90bb92b630`")));
        assertThat(Engine.codexReviewPending(pr)).isFalse();
    }

    @Test
    void cleanCodexReviewCommentForOldHeadDoesNotCloseGate() {
        Map<String, Object> pr = prBase(
                "headRefOid", "newhead000000000000",
                "reviews", new ArrayList<>(),
                "comments", List.of(map(
                        "author", map("login", "chatgpt-codex-connector[bot]"),
                        "body", "Codex Review: Didn't find any major issues.\n\n"
                                + "**Reviewed commit:** `oldhead111`")));
        assertThat(Engine.codexReviewPending(pr)).isTrue();
    }

    @Test
    void cleanCodexReviewWithGreenCiIsReadyToMerge() {
        String head = "90bb92b6304993d218f317070fbef07e236b5142";
        Map<String, Object> pr = prBase(
                "headRefOid", head,
                "statusCheckRollup", List.of(map("status", "COMPLETED", "conclusion", "SUCCESS")),
                "reviewThreads", new ArrayList<>(),
                "reviews", new ArrayList<>(),
                "reviewDecision", "",
                "comments", List.of(map(
                        "author", map("login", "chatgpt-codex-connector[bot]"),
                        "body", "Codex Review: Didn't find any major issues.\n\n"
                                + "**Reviewed commit:** `90bb92b630`")));
        assertThat(Engine.classifyPr(pr)).isEqualTo("READY_TO_MERGE");
    }

    // -- deviation detection (generic supervisor sensor) ---------------------

    @Test
    void healthyWaitingStateHasNoDeviation() {
        Map<String, Object> state = map("phase", "WAITING_FOR_CI",
                "current_pr", 178, "branch", "story/47-x",
                "last_processed_commit", "abc", "phase_attempt", 1);
        assertThat(Engine.detectDeviation(state, prBase("headRefOid", "abc"), 5)).isNull();
    }

    @Test
    void committedWithoutPrIsADeviation() {
        Map<String, Object> state = map("phase", "WAITING_FOR_CODEX_REVIEW",
                "current_pr", null, "branch", "story/180-x",
                "last_processed_commit", "1f8860d", "phase_attempt", 3);
        Engine.Deviation d = Engine.detectDeviation(state, null, 5);
        assertThat(d).isNotNull();
        assertThat(d.code()).isEqualTo("COMMITTED_NO_PR");
        assertThat(d.recoverable()).isTrue();
    }

    @Test
    void blockedExternalIsADeviation() {
        Map<String, Object> state = map("phase", "BLOCKED_EXTERNAL",
                "current_pr", null, "branch", "", "error", "stuck");
        Engine.Deviation d = Engine.detectDeviation(state, null, 5);
        assertThat(d).isNotNull();
        assertThat(d.code()).isEqualTo("BLOCKED_PHASE");
    }

    @Test
    void stalledPhaseIsADeviation() {
        Map<String, Object> state = map("phase", "IMPLEMENTING",
                "current_pr", null, "branch", "", "phase_attempt", 79);
        Engine.Deviation d = Engine.detectDeviation(state, null, 5);
        assertThat(d).isNotNull();
        assertThat(d.code()).isEqualTo("STALLED_PHASE");
    }

    @Test
    void staleReviewCursorIsADeviation() {
        Map<String, Object> state = map("phase", "WAITING_FOR_CODEX_REVIEW",
                "current_pr", 178, "branch", "story/180-x",
                "last_processed_commit", "1f8860d",
                "last_connector_review_commit", "90bb92b6", "phase_attempt", 2);
        Map<String, Object> pr = prBase("headRefOid", "1f8860d", "reviews", new ArrayList<>());
        Engine.Deviation d = Engine.detectDeviation(state, pr, 5);
        assertThat(d).isNotNull();
        assertThat(d.code()).isEqualTo("STALE_REVIEW_CURSOR");
    }

    @Test
    void finalMachineFooterWinsOverPromptExamples() {
        String output = "RESULT_STATUS: SUCCESS|BLOCKED|FAILED\n"
                + "RESULT_NEXT_PHASE: VERIFYING|REVIEWING_CODE\n"
                + "RESULT_BLOCKER: NONE or a concise blocker\n"
                + "RESULT_STATUS: SUCCESS\n"
                + "RESULT_NEXT_PHASE: VERIFYING\n"
                + "RESULT_BLOCKER: NONE\n"
                + "RESULT_EVIDENCE: COMPLETE\n";
        Map<String, Object> result = Engine.applyCodexResult(map(), 0, output);
        assertThat(result.get("result_status")).isEqualTo("SUCCESS");
        assertThat(result.get("phase")).isEqualTo("VERIFYING");
    }

    @Test
    void quotaFailurePauses() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 1, "weekly usage limit reached", 1);
        assertThat(result.get("phase")).isEqualTo("PAUSED_QUOTA");
        assertThat(result).containsKey("retry_at");
    }

    @Test
    void quotaResultIsPausedNotFailed() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 1, "5-hour usage limit reached", 60);
        assertThat(result.get("phase")).isEqualTo("PAUSED_QUOTA");
        assertThat(result).containsKey("retry_at");
    }

    @Test
    void timeoutIsRecoverableAndRequiresReconciliation() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 124, "Codex execution timed out");
        assertThat(result.get("phase")).isEqualTo("FAILED_RECOVERABLE");
        assertThat((String) result.get("error")).contains("reconcile");
    }

    @Test
    void signalTerminationIsNotClassifiedAsQuota() {
        Map<String, Object> result = Engine.applyCodexResult(map(), -15,
                "weekly usage limit appears in a source diff");
        assertThat(result.get("phase")).isEqualTo("FAILED_RECOVERABLE");
        assertThat((String) result.get("error")).contains("signal 15");
    }

    @Test
    void unknownSuccessIsNotClaimedComplete() {
        Map<String, Object> result = Engine.applyCodexResult(map(), 0, "finished");
        assertThat(result.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(result.get("result_status")).isEqualTo("UNSTRUCTURED");
    }

    // -- parse_decision  -------------------------

    @Test
    void reconciliationParserUsesFinalDecision() {
        String output = "DECISION_ACTION: BLOCK_REQUIREMENTS\n"
                + "DECISION_REASON: example\n"
                + "DECISION_ACTION: RESUME_VERIFICATION\n"
                + "DECISION_REASON: active story branch and preserved changes match\n";
        Map<String, String> decision = Engine.parseDecision(output);
        assertThat(decision.get("decision_action")).isEqualTo("RESUME_VERIFICATION");
    }

    @Test
    void reconciliationParserAcceptsStructuredProseReport() {
        String output = "Decision: `RESUME_IMPLEMENTATION`\n"
                + "Decision reason: the repository-owned test requires repair\n"
                + "Blocker class: `TEST_HARNESS`\n"
                + "Recovery action: allocate an ephemeral host port\n";
        Map<String, String> decision = Engine.parseDecision(output);
        assertThat(decision).containsExactlyInAnyOrderEntriesOf(Map.of(
                "decision_action", "RESUME_IMPLEMENTATION",
                "decision_reason", "the repository-owned test requires repair",
                "blocker_class", "TEST_HARNESS",
                "recovery_action", "allocate an ephemeral host port"));
    }

    // -- reconcile  ----------------------------------

    @Test
    void noPrRunsImplementation() {
        assertThat(Engine.reconcile(map(), null)).containsExactly("IMPLEMENTING", "RUN_CODEX");
    }

    @Test
    void pendingCiWaits() {
        assertThat(Engine.reconcile(map(),
                prBase("statusCheckRollup", List.of(map("status", "IN_PROGRESS")))))
                .containsExactly("WAITING_FOR_CI", "WAIT");
    }

    @Test
    void mergedAdvances() {
        assertThat(Engine.reconcile(map(), prBase("mergedAt", "2026-01-01T00:00:00Z")))
                .containsExactly("MERGED", "ADVANCE_STORY");
    }

    @Test
    void newFeedbackOverridesNormalWait() {
        assertThat(Engine.reconcile(map(), prBase("reviewDecision", "APPROVED"), true))
                .containsExactly("FIXING_FEEDBACK", "RUN_FEEDBACK");
    }

    @Test
    void missingReviewDataBlocks() {
        assertThat(Engine.reconcile(map(), prBase("reviewThreadsAvailable", false))[0])
                .isEqualTo("BLOCKED_EXTERNAL");
    }

    // -- policy matrix  -------------------------------

    private static Map<String, Object> policyBase(Map<String, Object> overrides) {
        Map<String, Object> base = map("state", "OPEN", "headRefOid", "h",
                "reviewThreadsAvailable", true,
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-01-01", "commit", map("oid", "h"))),
                "statusCheckRollup", new ArrayList<>(), "reviewDecision", "");
        base.putAll(overrides);
        return base;
    }

    @Test
    void ciFailureIsRepairable() {
        assertThat(Engine.reconcile(map(), policyBase(map("statusCheckRollup",
                List.of(map("status", "COMPLETED", "conclusion", "FAILURE")))))[0])
                .isEqualTo("CHANGES_REQUESTED");
    }

    @Test
    void formalApprovalIsReady() {
        assertThat(Engine.reconcile(map(), policyBase(map("reviewDecision", "APPROVED"))))
                .containsExactly("READY_TO_MERGE", "WAIT");
    }

    @Test
    void unmergedPrNeverAdvancesWithoutMerge() {
        String[] result = Engine.reconcile(map(), policyBase(map("reviewDecision", "")));
        assertThat(result[0]).isNotEqualTo("MERGED");
        assertThat(result[1]).isEqualTo("WAIT");
    }

    // -- actionable_feedback  ------------------------------

    @Test
    void connectorInformationalReviewIsNotActionable() {
        Map<String, Object> pr = map(
                "reviews", List.of(map("id", "r1", "body", "Here are automated review suggestions.",
                        "author", map("login", "chatgpt-codex-connector"))),
                "comments", List.of());
        assertThat(Engine.actionableFeedback(pr)).isEmpty();
    }

    @Test
    void humanFeedbackIsActionable() {
        Map<String, Object> pr = map(
                "reviews", List.of(map("id", "r1", "body", "Please fix this validation bug.",
                        "author", map("login", "reviewer"))),
                "comments", List.of());
        assertThat(Engine.actionableFeedback(pr).get(0).get("id")).isEqualTo("r1");
    }

    // -- thread classification  ---------------

    @Test
    void resolvedThreadsDoNotBlock() {
        Map<String, Object> pr = map("state", "OPEN", "headRefOid", "abc",
                "statusCheckRollup", List.of(),
                "reviewThreads", List.of(map("isResolved", true)),
                "reviewDecision", "APPROVED",
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-01-01", "commit", map("oid", "abc"))));
        assertThat(Engine.classifyPr(pr)).isEqualTo("READY_TO_MERGE");
    }

    @Test
    void automationFixRepliesAreNotReprocessedAsFeedback() {
        Map<String, Object> pr = map("reviews", List.of(), "comments", List.of(),
                "reviewThreads", List.of(map("id", "thread-1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "reply-1",
                                "body", "<!-- codex-automation-fix:thread=thread-1:head=abc -->",
                                "createdAt", "2026-01-01", "author", map("login", "owner")))))));
        assertThat(Engine.actionableFeedback(pr)).isEmpty();
    }

    @Test
    void unresolvedInlineBodyIsActionable() {
        Map<String, Object> pr = map("reviewThreads", List.of(map("isResolved", false,
                "comments", map("nodes", List.of(map("id", "inline-1",
                        "body", "Please fix this validation bug", "createdAt", "2026-01-01",
                        "author", map("login", "reviewer")))))));
        List<Map<String, Object>> feedback = Engine.actionableFeedback(pr);
        assertThat(feedback).hasSize(1);
        assertThat(feedback.get(0).get("id")).isEqualTo("inline-1");
    }

    @Test
    void unresolvedInlineFeedbackSurvivesLaterCursor() {
        Map<String, Object> pr = map(
                "comments", List.of(map("id", "later", "body", "later", "submittedAt", "2026-01-02")),
                "reviewThreads", List.of(map("id", "thread-1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "inline-1",
                                "body", "Please fix this", "createdAt", "2026-01-01",
                                "author", map("login", "chatgpt-codex-connector")))))));
        List<Map<String, Object>> feedback = Engine.actionableFeedback(pr, "later");
        assertThat(feedback).anyMatch(item -> "inline-1".equals(item.get("id")));
    }

    @Test
    void unresolvedConnectorInlineFindingWithoutKeywordIsActionable() {
        Map<String, Object> pr = map("reviewThreads", List.of(map("id", "thread-1", "isResolved", false,
                "comments", map("nodes", List.of(map("id", "inline-1",
                        "body", "P2: improve startup timing", "createdAt", "2026-01-01",
                        "author", map("login", "chatgpt-codex-connector")))))));
        assertThat(Engine.actionableFeedback(pr).get(0).get("id")).isEqualTo("inline-1");
    }

    // -- codex review state  ---------------------

    @Test
    void codexReviewPendingWithoutCurrentHeadReview() {
        assertThat(Engine.codexReviewPending(prBase("headRefOid", "abc"))).isTrue();
        assertThat(Engine.classifyPr(prBase("headRefOid", "abc"))).isEqualTo("WAITING_FOR_CODEX_REVIEW");
    }

    @Test
    void codexReviewCompleteForCurrentHead() {
        Map<String, Object> pr = prBase("headRefOid", "abc");
        pr.put("reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                "submittedAt", "2026-01-01", "commit", map("oid", "abc"))));
        assertThat(Engine.codexReviewPending(pr)).isFalse();
        assertThat(Engine.classifyPr(pr)).isEqualTo("WAITING_FOR_HUMAN_REVIEW");
    }
}
