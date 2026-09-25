package com.jcoding.workflow;

/**
 * Pure, deterministic anti-loop / progress decision engine (Requirements 4, 10).
 *
 * <p>Single source of truth for "attempts on this head/story without progress".
 * It replaces the previously scattered, independently-reset counters
 * ({@code resume_attempts}/{@code resume_guard_head} — the same-head consecutive
 * guard — and {@code resume_total_attempts}/{@code resume_total_story}/
 * {@code resume_total_resolved_baseline} — the cumulative per-story guard) with
 * one typed state and pure transitions. The reconcile/resume/feedback caps are
 * expressed as thresholds over this one state, not as separate counters.</p>
 *
 * <p>Behavior is preserved verbatim from the former inline orchestrator logic:
 * <ul>
 *   <li><b>Same-head guard:</b> a resume decision on the same head as the last
 *       increments the consecutive counter; a new head rebaselines it to 1.</li>
 *   <li><b>Cumulative guard:</b> every resume for the active story accrues,
 *       resetting only on a story change OR on genuine progress (the resolved
 *       review-thread count increased since the last reset). Head movement alone
 *       is NOT progress — a trivial head bump with no thread resolution still
 *       accrues (the PR #187 field case).</li>
 *   <li><b>Force delivery:</b> when the same-head counter exceeds
 *       {@code maxResume}, or the cumulative counter exceeds
 *       {@code maxResume * loopMultiplier}, the guard signals delivery (commit
 *       what exists, defer validation to CI) rather than looping forever.</li>
 *   <li><b>Non-resume decisions</b> clear only the same-head guard; the
 *       cumulative per-story counters intentionally survive so an oscillating
 *       resume/non-resume loop with an advancing head still converges.</li>
 * </ul></p>
 *
 * <p>This class performs no I/O and holds no mutable state; all inputs are
 * supplied and a new {@link ProgressState} is returned, so it is exhaustively
 * unit-testable without git/GitHub/process mocks.</p>
 */
public final class ProgressGuard {

    private ProgressGuard() {
    }

    /**
     * Typed anti-loop state for the active story/head. Immutable value carrier;
     * {@link #observe} returns a new instance.
     *
     * @param sameHeadGuardHead the head the consecutive same-head counter tracks
     * @param sameHeadAttempts  consecutive resume decisions on that head
     * @param cumulativeStory   the story the cumulative counter tracks
     * @param cumulativeAttempts resume decisions accrued for that story
     * @param resolvedBaseline  resolved-thread watermark at the last cumulative reset
     */
    public record ProgressState(
            String sameHeadGuardHead,
            int sameHeadAttempts,
            String cumulativeStory,
            int cumulativeAttempts,
            int resolvedBaseline) {

        /** Empty initial state. */
        public static ProgressState empty() {
            return new ProgressState("", 0, "", 0, 0);
        }
    }

    /**
     * Observation inputs for one reconcile decision.
     *
     * @param resumeAction   whether the decision is a RESUME_* / PROCESS_FEEDBACK
     * @param head           the current PR head SHA (empty when no PR)
     * @param storyKey       the active story key (empty when none)
     * @param resolvedNow    the current resolved review-thread count (progress signal)
     * @param maxResume      per-head resume cap
     * @param loopMultiplier cumulative cap multiplier (>= 1)
     */
    public record GuardInput(
            boolean resumeAction,
            String head,
            String storyKey,
            int resolvedNow,
            int maxResume,
            int loopMultiplier) {
    }

    /** Whether the guard progressed the counters, and whether delivery is forced. */
    public record GuardOutcome(
            ProgressState state,
            boolean forceDelivery,
            boolean progressReset,
            int sameHeadAttempts,
            int cumulativeAttempts,
            int maxTotalResume) {
    }

    /**
     * Apply one reconcile observation, returning the new state and whether the
     * caps force autonomous delivery. Pure: no side effects.
     */
    public static GuardOutcome observe(ProgressState prior, GuardInput in) {
        if (!in.resumeAction()) {
            // Non-resume decision: clear only the same-head guard. Cumulative
            // per-story counters survive (they reset only on story advance or a
            // completed auto-deliver, handled by the caller).
            ProgressState next = new ProgressState(
                    "", 0,
                    prior.cumulativeStory(), prior.cumulativeAttempts(), prior.resolvedBaseline());
            return new GuardOutcome(next, false, false, 0, prior.cumulativeAttempts(),
                    in.maxResume() * Math.max(1, in.loopMultiplier()));
        }

        // Same-head consecutive guard.
        int sameHeadAttempts;
        String guardHead = prior.sameHeadGuardHead();
        if (!in.head().isEmpty() && in.head().equals(guardHead)) {
            sameHeadAttempts = prior.sameHeadAttempts() + 1;
        } else {
            sameHeadAttempts = 1;
            guardHead = in.head();
        }

        // Cumulative per-story guard: reset on story change OR genuine progress
        // (resolved-thread count increased since the last reset).
        boolean madeProgress = in.resolvedNow() > prior.resolvedBaseline();
        String cumulativeStory = prior.cumulativeStory();
        int cumulativeAttempts;
        int resolvedBaseline = prior.resolvedBaseline();
        boolean sameStory = !in.storyKey().isEmpty() && in.storyKey().equals(cumulativeStory);
        if (sameStory && !madeProgress) {
            cumulativeAttempts = prior.cumulativeAttempts() + 1;
        } else {
            cumulativeAttempts = 1;
            cumulativeStory = in.storyKey();
            resolvedBaseline = in.resolvedNow();
        }

        int loopMultiplier = Math.max(1, in.loopMultiplier());
        int maxTotalResume = in.maxResume() * loopMultiplier;
        boolean forceDelivery = sameHeadAttempts > in.maxResume()
                || cumulativeAttempts > maxTotalResume;

        ProgressState next = new ProgressState(
                guardHead, sameHeadAttempts, cumulativeStory, cumulativeAttempts, resolvedBaseline);
        return new GuardOutcome(next, forceDelivery,
                madeProgress || !sameStory, sameHeadAttempts, cumulativeAttempts, maxTotalResume);
    }

    /**
     * The single reconcile-failure cap: after this many consecutive failed or
     * blocking reconciliation decisions the run is escalated to a durable block.
     * Kept here so every anti-loop threshold lives in one owner (Requirement 10),
     * even though the reconcile counter measures a different axis (consecutive
     * reconcile failures) than the per-head no-progress counter.
     */
    public static final int RECONCILE_CAP = 3;

    /** Whether the consecutive-reconcile-failure count has reached the cap. */
    public static boolean reconcileCapReached(int attempts) {
        return attempts >= RECONCILE_CAP;
    }

    /**
     * Result of the per-finding feedback guard: the (possibly migrated) guard
     * key, the attempt count for it, and whether the cap is exceeded.
     */
    public record FeedbackOutcome(String guardKey, int attempts, boolean capExceeded) {
    }

    /**
     * Compute the feedback attempt count for the active finding on a head,
     * preserving the historical semantics exactly (Requirement 10):
     * <ul>
     *   <li>the guard is keyed by {@code head:findingKey};</li>
     *   <li>a legacy guard written by the pre-queue controller (empty key but a
     *       matching {@code feedback_guard_head}) is migrated to the current key
     *       so an in-flight retry keeps its budget;</li>
     *   <li>a matching key increments the count; any other key rebaselines to 1;</li>
     *   <li>the cap is exceeded when the incremented count exceeds
     *       {@code maxFeedbackAttempts}.</li>
     * </ul>
     *
     * @param head             current PR head
     * @param findingKey       active finding key ("unknown" when none)
     * @param priorGuardKey    persisted {@code feedback_guard_key} (may be empty)
     * @param priorGuardHead   persisted {@code feedback_guard_head} (for legacy migration)
     * @param priorAttempts    persisted {@code feedback_attempts}
     * @param maxFeedbackAttempts the cap
     */
    public static FeedbackOutcome observeFeedback(
            String head, String findingKey, String priorGuardKey, String priorGuardHead,
            int priorAttempts, int maxFeedbackAttempts) {
        String guardKey = priorGuardKey == null ? "" : priorGuardKey;
        // Legacy migration: old guard keyed by head only.
        if (guardKey.isEmpty() && head.equals(priorGuardHead)) {
            guardKey = head + ":" + findingKey;
        }
        String currentKey = head + ":" + findingKey;
        int attempts;
        if (currentKey.equals(guardKey)) {
            attempts = priorAttempts + 1;
        } else {
            attempts = 1;
            guardKey = currentKey;
        }
        return new FeedbackOutcome(guardKey, attempts, attempts > maxFeedbackAttempts);
    }
}
