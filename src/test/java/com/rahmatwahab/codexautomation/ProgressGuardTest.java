package com.rahmatwahab.codexautomation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business-value tests for the anti-loop progress engine. Each test protects a
 * specific safety property that a prior production incident violated; none is a
 * mere call/coverage assertion.
 */
class ProgressGuardTest {

    private static ProgressGuard.GuardInput resume(String head, String story, int resolved) {
        return new ProgressGuard.GuardInput(true, head, story, resolved, 3, 4);
    }

    @Test
    void sameHeadNoProgressAccruesUntilItForcesDelivery() {
        // Safety property: a head retried past the cap without progress must
        // stop looping and force delivery (commit + defer to CI), exactly once
        // the consecutive count exceeds maxResume (3).
        ProgressGuard.ProgressState s = ProgressGuard.ProgressState.empty();
        ProgressGuard.GuardOutcome o = null;
        for (int i = 1; i <= 3; i++) {
            o = ProgressGuard.observe(s, resume("HEAD_A", "52", 0));
            s = o.state();
            assertThat(o.forceDelivery()).as("attempt %d within cap", i).isFalse();
        }
        // 4th consecutive same-head resume exceeds maxResume -> force delivery.
        o = ProgressGuard.observe(s, resume("HEAD_A", "52", 0));
        assertThat(o.sameHeadAttempts()).isEqualTo(4);
        assertThat(o.forceDelivery()).isTrue();
    }

    @Test
    void advancingHeadWithThreadResolutionNeverForcesDelivery_pr187() {
        // Field regression (PR #187): a PR legitimately advanced ~40 heads and
        // resolved ~200 threads. Real progress each round must reset the
        // cumulative guard so a healthy review cycle is NEVER falsely delivered.
        ProgressGuard.ProgressState s = ProgressGuard.ProgressState.empty();
        int resolved = 0;
        for (int round = 1; round <= 40; round++) {
            String head = "HEAD_" + round;      // head advances every round
            resolved += 5;                        // and threads are resolved
            ProgressGuard.GuardOutcome o = ProgressGuard.observe(s, resume(head, "52", resolved));
            s = o.state();
            assertThat(o.forceDelivery())
                    .as("healthy advancing round %d must not force delivery", round)
                    .isFalse();
            assertThat(o.progressReset()).isTrue();
        }
    }

    @Test
    void advancingHeadWithoutProgressStillConvergesViaCumulativeCap() {
        // The same-head guard alone is insufficient: a head that bumps slightly
        // every round (resetting the same-head counter) but resolves NOTHING
        // must still converge via the cumulative per-story cap
        // (maxResume * loopMultiplier = 3 * 4 = 12).
        ProgressGuard.ProgressState s = ProgressGuard.ProgressState.empty();
        ProgressGuard.GuardOutcome o = null;
        boolean forcedBeforeCap = false;
        for (int round = 1; round <= 12; round++) {
            o = ProgressGuard.observe(s, resume("HEAD_" + round, "52", 0)); // head moves, no progress
            s = o.state();
            assertThat(o.sameHeadAttempts()).as("head moves so same-head stays 1").isEqualTo(1);
            if (o.forceDelivery()) {
                forcedBeforeCap = true;
            }
        }
        assertThat(forcedBeforeCap).as("must not force before the cumulative cap").isFalse();
        // 13th accrual exceeds the cumulative cap of 12.
        o = ProgressGuard.observe(s, resume("HEAD_13", "52", 0));
        assertThat(o.cumulativeAttempts()).isEqualTo(13);
        assertThat(o.forceDelivery()).isTrue();
    }

    @Test
    void nonResumeDecisionClearsSameHeadButKeepsCumulative() {
        // A non-resume decision (WAIT/BLOCK) must clear the consecutive same-head
        // guard yet preserve the cumulative per-story counter, so an oscillating
        // resume/non-resume loop still converges.
        ProgressGuard.ProgressState s = ProgressGuard.ProgressState.empty();
        s = ProgressGuard.observe(s, resume("HEAD_A", "52", 0)).state();
        s = ProgressGuard.observe(s, resume("HEAD_A", "52", 0)).state();
        assertThat(s.sameHeadAttempts()).isEqualTo(2);
        int cumulativeBefore = s.cumulativeAttempts();

        ProgressGuard.GuardInput nonResume =
                new ProgressGuard.GuardInput(false, "HEAD_A", "52", 0, 3, 4);
        ProgressGuard.GuardOutcome o = ProgressGuard.observe(s, nonResume);
        assertThat(o.state().sameHeadAttempts()).as("same-head cleared").isZero();
        assertThat(o.state().cumulativeAttempts())
                .as("cumulative preserved across non-resume").isEqualTo(cumulativeBefore);
    }

    @Test
    void reconcileCapPolicyIsCentralizedAtThree() {
        // R10: the reconcile-failure threshold lives in one owner. Below the cap
        // the run continues; at/after the cap it escalates.
        assertThat(ProgressGuard.reconcileCapReached(0)).isFalse();
        assertThat(ProgressGuard.reconcileCapReached(2)).isFalse();
        assertThat(ProgressGuard.reconcileCapReached(3)).isTrue();
        assertThat(ProgressGuard.reconcileCapReached(4)).isTrue();
        assertThat(ProgressGuard.RECONCILE_CAP).isEqualTo(3);
    }

    @Test
    void feedbackGuardIncrementsOnSameFindingAndResetsOnChange() {
        // Same head:finding increments the feedback attempt count; a different
        // finding (or head) rebaselines to 1.
        ProgressGuard.FeedbackOutcome a =
                ProgressGuard.observeFeedback("H1", "F1", "H1:F1", "", 2, 3);
        assertThat(a.attempts()).isEqualTo(3);
        assertThat(a.capExceeded()).isFalse();

        ProgressGuard.FeedbackOutcome capped =
                ProgressGuard.observeFeedback("H1", "F1", "H1:F1", "", 3, 3);
        assertThat(capped.attempts()).isEqualTo(4);
        assertThat(capped.capExceeded()).as("cap exceeded past maxFeedbackAttempts").isTrue();

        ProgressGuard.FeedbackOutcome reset =
                ProgressGuard.observeFeedback("H1", "F2", "H1:F1", "", 3, 3);
        assertThat(reset.attempts()).as("different finding rebaselines").isEqualTo(1);
        assertThat(reset.guardKey()).isEqualTo("H1:F2");
    }

    @Test
    void feedbackGuardMigratesLegacyHeadOnlyKey() {
        // Pre-queue state stored only feedback_guard_head. When the empty key's
        // head matches, the guard adopts head:finding and the in-flight retry
        // keeps its budget (increments rather than resetting to 1).
        ProgressGuard.FeedbackOutcome migrated =
                ProgressGuard.observeFeedback("H1", "F1", "", "H1", 2, 3);
        assertThat(migrated.guardKey()).isEqualTo("H1:F1");
        assertThat(migrated.attempts()).as("legacy budget preserved").isEqualTo(3);
    }

    @Test
    void storyChangeRebaselinesCumulativeGuard() {
        // Moving to a new story resets the cumulative guard so one story's churn
        // never penalizes the next.
        ProgressGuard.ProgressState s = ProgressGuard.ProgressState.empty();
        s = ProgressGuard.observe(s, resume("HEAD_A", "52", 0)).state();
        s = ProgressGuard.observe(s, resume("HEAD_A", "52", 0)).state();
        assertThat(s.cumulativeAttempts()).isEqualTo(2);
        ProgressGuard.GuardOutcome o = ProgressGuard.observe(s, resume("HEAD_B", "99", 0));
        assertThat(o.cumulativeAttempts()).as("new story rebaselines to 1").isEqualTo(1);
        assertThat(o.progressReset()).isTrue();
    }
}
