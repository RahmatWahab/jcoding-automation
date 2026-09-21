package com.rahmatwahab.codexautomation;

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

    private static Map<String, Object> finding(String threadId, String body) {
        return new LinkedHashMap<>(Map.of(
                "id", "comment-" + threadId,
                "thread_id", threadId,
                "author", "reviewer",
                "body", body,
                "commit", "head"));
    }
}
