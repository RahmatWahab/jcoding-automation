package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewFindingQueueTest {

    @Test
    void keepsIndependentFindingsAndSelectsOneActiveWorkItem() {
        Map<String, Object> state = new LinkedHashMap<>();
        List<Map<String, Object>> feedback = List.of(
                finding("PRRT_1", "login content type"),
                finding("PRRT_2", "dashboard route"));

        ReviewFindingQueue.synchronize(state, feedback);
        Map<String, Object> active = ReviewFindingQueue.active(state);
        ReviewFindingQueue.markDispatched(state, active);

        assertThat(active.get("thread_id")).isEqualTo("PRRT_1");
        assertThat(ReviewFindingQueue.active(state).get("attempts")).isEqualTo(1);
        assertThat(state.get("review_findings")).asList().hasSize(2);
    }

    @Test
    void selectsAndDispatchesAStableBoundedBatch() {
        Map<String, Object> state = new LinkedHashMap<>();
        List<Map<String, Object>> feedback = List.of(
                finding("PRRT_1", "first"), finding("PRRT_2", "second"),
                finding("PRRT_3", "third"), finding("PRRT_4", "fourth"));

        ReviewFindingQueue.synchronize(state, feedback, 3);
        ReviewFindingQueue.markActiveDispatched(state);

        assertThat(ReviewFindingQueue.activeBatch(state))
                .extracting(item -> item.get("thread_id"))
                .containsExactly("PRRT_1", "PRRT_2", "PRRT_3");
        assertThat(ReviewFindingQueue.findings(state)).allSatisfy(item -> {
            if (List.of("PRRT_1", "PRRT_2", "PRRT_3").contains(item.get("thread_id"))) {
                assertThat(item.get("status")).isEqualTo("IN_PROGRESS");
                assertThat(item.get("attempts")).isEqualTo(1);
            }
        });
    }

    @Test
    void advancesOnlyDeclaredBatchItemsAndLeavesTheRestPending() {
        Map<String, Object> state = new LinkedHashMap<>();
        ReviewFindingQueue.synchronize(state, List.of(
                finding("PRRT_1", "first"), finding("PRRT_2", "second"),
                finding("PRRT_3", "third")), 2);
        ReviewFindingQueue.markActiveDispatched(state);

        ReviewFindingQueue.markVerified(state, java.util.Set.of("PRRT_1"), "commit-1", "VERIFIED");

        assertThat(ReviewFindingQueue.activeBatch(state))
                .extracting(item -> item.get("thread_id"))
                .containsExactly("PRRT_2", "PRRT_3");
        assertThat(ReviewFindingQueue.findings(state)).anySatisfy(item -> {
            assertThat(item.get("thread_id")).isEqualTo("PRRT_1");
            assertThat(item.get("status")).isEqualTo("VERIFIED");
            assertThat(item.get("verified_commit")).isEqualTo("commit-1");
        });
    }

    @Test
    void reopensVerifiedFindingOnlyWhenAuthoritativeCommitChanges() {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> initial = finding("PRRT_1", "finding");
        initial.put("commit", "head-1");
        ReviewFindingQueue.synchronize(state, List.of(initial), 1);
        ReviewFindingQueue.markActiveDispatched(state);
        ReviewFindingQueue.markVerified(state, java.util.Set.of("PRRT_1"), "head-1", "VERIFIED");

        ReviewFindingQueue.synchronize(state, List.of(initial), 1);
        assertThat(ReviewFindingQueue.active(state)).isNull();

        Map<String, Object> changedHead = finding("PRRT_1", "finding");
        changedHead.put("commit", "head-2");
        ReviewFindingQueue.synchronize(state, List.of(changedHead), 1);
        assertThat(ReviewFindingQueue.active(state).get("status")).isEqualTo("PENDING");
    }

    @Test
    void timeoutIsCheckpointedWithoutMakingFindingUnrecoverable() {
        Map<String, Object> state = new LinkedHashMap<>();
        ReviewFindingQueue.synchronize(state, List.of(finding("PRRT_1", "finding")));
        ReviewFindingQueue.markActiveDispatched(state);

        ReviewFindingQueue.recordWorkerResult(state, 124, "BLOCKED", "worker timed out");

        assertThat(ReviewFindingQueue.active(state).get("status")).isEqualTo("PENDING");
        assertThat(ReviewFindingQueue.active(state).get("last_exit_code")).isEqualTo(124);
        assertThat(ReviewFindingQueue.active(state).get("last_detail"))
                .isEqualTo("worker timed out");
    }

    @Test
    void resolvedFindingIsRetainedAsVerifiedAndNextFindingBecomesActive() {
        Map<String, Object> state = new LinkedHashMap<>();
        ReviewFindingQueue.synchronize(state, List.of(
                finding("PRRT_1", "first"), finding("PRRT_2", "second")));
        state.put(ReviewFindingQueue.ACTIVE_KEY,
                new LinkedHashMap<>(ReviewFindingQueue.active(state)));

        ReviewFindingQueue.synchronize(state, List.of(finding("PRRT_2", "second")));

        assertThat(ReviewFindingQueue.active(state).get("thread_id")).isEqualTo("PRRT_2");
        assertThat(state.get("review_findings")).asList()
                .anyMatch(item -> item instanceof Map<?, ?> map
                        && "PRRT_1".equals(map.get("thread_id"))
                        && "VERIFIED".equals(map.get("status")));
    }

    @Test
    void forceCloseMarksFindingTerminalAndExcludesItFromWork() {
        Map<String, Object> state = new LinkedHashMap<>();
        ReviewFindingQueue.synchronize(state, List.of(
                finding("PRRT_1", "unfixable"), finding("PRRT_2", "still pending")), 3);

        ReviewFindingQueue.forceClose(state, "PRRT_1", "UNFIXABLE_AFTER_CAP");

        // The closed finding is terminal (never selected as work again)...
        assertThat(ReviewFindingQueue.findings(state)).anySatisfy(item -> {
            if ("PRRT_1".equals(item.get("thread_id"))) {
                assertThat(item.get("status")).isEqualTo("FORCE_CLOSED");
                assertThat(item.get("force_close_reason")).isEqualTo("UNFIXABLE_AFTER_CAP");
            }
        });
        assertThat(ReviewFindingQueue.next(ReviewFindingQueue.findings(state)))
                .extracting(f -> f.get("thread_id")).isEqualTo("PRRT_2");
        // ...and the active batch now surfaces the remaining pending finding.
        assertThat(ReviewFindingQueue.activeBatch(state))
                .extracting(item -> item.get("thread_id"))
                .containsExactly("PRRT_2");
    }

    @Test
    void forceClosedFindingIsNotResurrectedWhenStillUnresolvedOnGitHub() {
        Map<String, Object> state = new LinkedHashMap<>();
        ReviewFindingQueue.synchronize(state, List.of(finding("PRRT_1", "unfixable")), 3);
        ReviewFindingQueue.forceClose(state, "PRRT_1", "OUTDATED_PASSED_COMMIT");

        // GitHub still reports the thread unresolved on the next sync (before it
        // reflects the resolution). The finding must stay FORCE_CLOSED, not flip
        // back to PENDING and re-enter the work queue.
        ReviewFindingQueue.synchronize(state, List.of(finding("PRRT_1", "unfixable")), 3);

        assertThat(ReviewFindingQueue.findings(state)).anySatisfy(item -> {
            if ("PRRT_1".equals(item.get("thread_id"))) {
                assertThat(item.get("status")).isEqualTo("FORCE_CLOSED");
            }
        });
        assertThat(ReviewFindingQueue.next(ReviewFindingQueue.findings(state))).isNull();
    }

    private static Map<String, Object> finding(String threadId, String body) {
        return new LinkedHashMap<>(Map.of(
                "id", "comment-" + threadId,
                "thread_id", threadId,
                "author", "reviewer",
                "body", body,
                "commit", "head"));
    }
}
