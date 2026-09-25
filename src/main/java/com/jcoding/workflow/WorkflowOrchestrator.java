package com.jcoding.workflow;
import com.jcoding.github.GitHub;
import com.jcoding.github.GitHubError;
import com.jcoding.state.StateStore;
import com.jcoding.state.WorkflowLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitHub reconciliation, phase dispatch, and bounded Codex execution.
 *
 * <p>Holds the reconciliation and execution flow to keep the
 * {@link Controller} entry point small and this workflow logic independently
 * testable. Preserves the reference sequencing: story selection, PR adoption,
 * dependency and eligibility checks, review-trigger and review-gate handling,
 * wait/merge/blocked short-circuits, prompt rendering, bounded worker
 * execution, result application, delivery verification, and thread updates.</p>
 */
final class WorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestrator.class);

    private final Map<String, Object> config;
    private final Map<String, Object> state;
    private final StateStore store;
    private final GitHub github;
    private final Path repo;
    private final Path root;
    private final Map<String, Object> epic;
    private final int epicNumber;
    private final boolean dryRun;
    private final Controller.GitOperationsFactory gitFactory;
    private final CodexRunner codexRunner;
    private final ReviewCoordinator review;
    private final WorkflowLock lock;
    private final Map<String, Object> automation;
    private final Map<String, Object> workflow;

    WorkflowOrchestrator(Map<String, Object> config, Map<String, Object> state, StateStore store,
                         GitHub github, Path repo, Path root, Map<String, Object> epic, int epicNumber,
                         boolean dryRun, Controller.GitOperationsFactory gitFactory,
                         CodexRunner codexRunner, ReviewCoordinator review, WorkflowLock lock) {
        this.config = config;
        this.state = state;
        this.store = store;
        this.github = github;
        this.repo = repo;
        this.root = root;
        this.epic = epic;
        this.epicNumber = epicNumber;
        this.dryRun = dryRun;
        this.gitFactory = gitFactory;
        this.codexRunner = codexRunner;
        this.review = review;
        this.lock = lock;
        this.automation = Config.section(config, "automation");
        this.workflow = Config.section(config, "workflow");
    }

    private void save() {
        // R1 transition enforcement: an undeclared phase transition is surfaced
        // as a detectable event, not silent behavior. It is logged (not thrown)
        // so a table gap can never destabilize the live control path; the
        // WorkflowMachine table is the authority the WARN is checked against.
        String newPhase = Engine.asString(state.get("phase"));
        String lastPhase = Engine.asString(state.get("last_persisted_phase"));
        if (!lastPhase.isEmpty() && !newPhase.isEmpty()
                && !WorkflowMachine.isLegalTransition(lastPhase, newPhase)) {
            log.warn("ILLEGAL_TRANSITION: undeclared phase transition {} -> {} "
                    + "(not in the WorkflowMachine table)", lastPhase, newPhase);
        }
        state.put("last_persisted_phase", newPhase);
        store.save(state);
    }

    int reconcileAndExecute(List<Map<String, Object>> prs, String forcedPrompt) {
        // R3: read control-relevant state through the typed model rather than
        // ad-hoc string keys. The codec is the single typed view over the durable
        // map (backward compatible; unknown keys preserved on write).
        WorkflowStateCodec.WorkflowState typed = WorkflowStateCodec.load(state);
        Integer current = typed.currentStory();
        Map<String, Object> currentPr = null;

        // Story selection, PR adoption, and eligibility/dependency verification
        // (Requirement 2: SelectStory handler). A non-null exitCode means the run
        // ends here; otherwise the resolved current story/PR flow onward.
        SelectionResult selection = selectAndAdoptStory(prs, current, currentPr);
        if (selection.exitCode() != null) {
            return selection.exitCode();
        }
        current = selection.current();
        currentPr = selection.currentPr();

        List<Map<String, Object>> feedbackToResolve = new ArrayList<>();
        // PR-scoped reconciliation and wait/merge/blocked short-circuits
        // (Requirement 2: Feedback/PR handler). A non-null exitCode ends the run;
        // otherwise the (possibly updated) currentPr flows onward and
        // feedbackToResolve is populated in place.
        PrScopedResult prScoped = reconcilePrScoped(current, currentPr, feedbackToResolve);
        if (prScoped.exitCode() != null) {
            return prScoped.exitCode();
        }
        currentPr = prScoped.currentPr();

        // Generic deviation supervisor (Requirement 2): detect any deviation/
        // stall and either recover deterministically (returning an exit code) or
        // route to the reasoning reconcile prompt.
        DeviationResult deviationResult = runDeviationSupervisor(current, currentPr, forcedPrompt);
        if (deviationResult.exitCode() != null) {
            return deviationResult.exitCode();
        }
        forcedPrompt = deviationResult.forcedPrompt();
        return runWorkerPhase(current, currentPr, feedbackToResolve, forcedPrompt);
    }

    /** Result of PR-scoped reconciliation: non-null exitCode ends the run. */
    private record PrScopedResult(Integer exitCode, Map<String, Object> currentPr) {
        static PrScopedResult exit(int code) {
            return new PrScopedResult(code, null);
        }
        static PrScopedResult proceed(Map<String, Object> currentPr) {
            return new PrScopedResult(null, currentPr);
        }
    }

    /**
     * PR-scoped reconciliation (Requirement 2): fetch PR detail, detect
     * actionable feedback and manage the per-finding queue + feedback anti-loop
     * guard, handle stuck-head/terminal routing, post the Codex review trigger,
     * and short-circuit wait/merge/blocked phases. Behavior-identical to the
     * former inline block. Populates {@code feedbackToResolve} in place; returns
     * a carrier whose non-null exitCode ends the run.
     */
    private PrScopedResult reconcilePrScoped(Integer current, Map<String, Object> currentPr,
                                             List<Map<String, Object>> feedbackToResolve) {
        if (currentPr != null) {
            try {
                log.info("PHASE: GitHub PR detail #" + currentPr.get("number"));
                currentPr = github.pr(Engine.asInt(currentPr.get("number")));
            } catch (GitHubError exc) {
                return PrScopedResult.exit(blockedExternal("Unable to inspect PR: " + exc.getMessage()));
            }
            state.put("current_pr_head", currentPr.get("headRefOid"));
            state.put("pr_state", currentPr.get("state"));
            state.put("pr_review_decision", currentPr.get("reviewDecision"));
            // Park-and-advance (Requirement 5): a parked story whose head has
            // advanced (a new commit landed) is automatically un-parked and
            // resumes the normal cycle. No-op under the default "hold" policy.
            if (current != null && ParkPolicy.enabled(workflow)
                    && ParkPolicy.unparkIfHeadAdvanced(state, current,
                            Engine.asString(currentPr.get("headRefOid")))) {
                log.info("UNPARKED: story #{} head advanced to {}; resuming normal cycle",
                        current, shortSha(currentPr.get("headRefOid")));
                save();
            }
            // Persist the story branch from the PR head so a later worktree
            // recovery can match the active branch even when the PR was already
            // recorded in state (and thus not re-adopted this run).
            if (currentPr.get("headRefName") != null
                    && !Engine.asString(currentPr.get("headRefName")).isEmpty()) {
                state.put("branch", currentPr.get("headRefName"));
                save();
            }
            state.put("ci_status", currentPr.get("statusCheckRollup") != null
                    ? currentPr.get("statusCheckRollup") : new ArrayList<>());

            // Crash recovery: if a previous run posted a fix reply on the
            // current head but crashed before resolving, complete only that
            // already-delivered resolution.
            if (Engine.asBool(workflow.get("allow_thread_resolution"), false)
                    && "OPEN".equals(Engine.asString(currentPr.get("state")).toUpperCase())) {
                for (Object threadObj : Engine.asList(currentPr.get("reviewThreads"))) {
                    if (!(threadObj instanceof Map<?, ?> t)) {
                        continue;
                    }
                    Map<String, Object> thread = castMap(t);
                    String head = Engine.asString(currentPr.get("headRefOid"));
                    if (!Engine.asBool(thread.get("isResolved"), true)
                            && ReviewCoordinator.fixReplyHeads(thread).contains(head)) {
                        try {
                            github.resolveThread(Engine.asString(thread.get("id")));
                            recordThreadUpdate(Engine.asString(thread.get("id")), head,
                                    "RESOLVED_AFTER_DELIVERED_REPLY");
                            log.info("PR THREAD RESOLVED: " + thread.get("id") + " (recovered)");
                        } catch (GitHubError exc) {
                            return PrScopedResult.exit(blockedExternal("Unable to resolve delivered review feedback: " + exc.getMessage()));
                        }
                    }
                }
            }

            List<Map<String, Object>> feedback = Engine.actionableFeedback(
                    currentPr, Engine.asString(state.get("last_feedback_id")).isEmpty()
                            ? null : Engine.asString(state.get("last_feedback_id")));
            boolean legacyPrLevelFindingState = !state.containsKey(ReviewFindingQueue.STATE_KEY);
            // Persist each unresolved finding independently, then select a
            // bounded compatible work batch. A large PR review cannot consume
            // one shared retry budget or time out while attempting the entire
            // backlog in one execution.
            int feedbackBatchSize = (int) Engine.asLongOrDefault(
                    automation.get("max_feedback_batch_size"),
                    ReviewFindingQueue.DEFAULT_BATCH_SIZE);
            state.put("feedback_batch_size", Math.max(1, feedbackBatchSize));
            ReviewFindingQueue.synchronize(state, feedback, feedbackBatchSize);
            // Rule 1: force-close any unresolved thread raised on a commit older
            // than the current head before selecting work. The finding queue is
            // re-read afterwards so a closed thread is not dispatched to the
            // worker this run.
            closeOutdatedThreads(currentPr, Engine.asString(currentPr.get("headRefOid")));
            Map<String, Object> activeFinding = ReviewFindingQueue.active(state);
            List<Map<String, Object>> activeFeedback = ReviewFindingQueue.activeBatch(state);
            List<Map<String, Object>> events = Engine.reviewEvents(
                    currentPr, Engine.asString(state.get("last_processed_review_id")).isEmpty()
                            ? null : Engine.asString(state.get("last_processed_review_id")));
            if (!events.isEmpty()) {
                state.put("last_processed_review_id", events.get(events.size() - 1).get("id"));
                state.put("unresolved_feedback", new ArrayList<>(
                        events.subList(Math.max(0, events.size() - 10), events.size())));
            }
            if (!feedback.isEmpty()) {
                state.put("last_feedback_id", feedback.get(feedback.size() - 1).get("id"));
                state.put("phase", "FIXING_FEEDBACK");

                String head = Engine.asString(currentPr.get("headRefOid"));

                // Terminal stuck head. A stuck_head marker is set in two
                // situations: (a) NON-delivering mode (dry-run or allow_push
                // disabled) after repeated unrecoverable feedback, and (b)
                // DELIVERING mode when AUTO-DELIVER aborts because the worker
                // produced no deliverable fix and no independent findings
                // remain (reason=UNRECOVERABLE). In a delivering mode a *stale*
                // marker (e.g. left over from an earlier dry-run) must be
                // cleared and the normal live path resumed, but a genuine
                // UNRECOVERABLE marker on the current head must be honoured —
                // either parked-and-advanced (park_and_advance policy) or held
                // idempotently (hold policy) — so it never re-enters feedback
                // every tick (the unbounded "reprocessed N times" loop) nor
                // floods the log.
                boolean deliveringMode = !dryRun && Engine.asBool(workflow.get("allow_push"), false);
                PrScopedResult stuck = handleStuckHeadForFeedback(
                        currentPr, head, legacyPrLevelFindingState, activeFinding, deliveringMode);
                if (stuck != null) {
                    return stuck;
                }

                // Respect the worker's verdict: if the previous FIXING_FEEDBACK
                // run on this exact head returned BLOCKED (it could not resolve
                // the finding directly), do not re-dispatch the same feedback.
                // Route to reconciliation, which is designed to classify the
                // blocker (e.g. TEST_HARNESS) and choose RESUME_IMPLEMENTATION
                // or, if genuinely unrecoverable, BLOCK_EXTERNAL. Reconciliation
                // has its own 3-attempt ceiling, so this cannot loop.
                if ("BLOCKED".equals(Engine.asString(state.get("last_feedback_result")))
                        && head.equals(Engine.asString(state.get("last_feedback_result_head")))) {
                    // Clear the feedback phase so we do not re-enter FIXING_FEEDBACK,
                    // then fall through to normal phase dispatch, which routes
                    // BLOCKED_REQUIREMENTS to reconcile.md.
                    state.put("phase", "BLOCKED_REQUIREMENTS");
                    state.put("feedback_reconcile_pending", true);
                    log.info("RECONCILE: feedback BLOCKED on PR #{} head {}; routing to reconciliation",
                            currentPr.get("number"), shortSha(head));
                }

                // Do not consume a worker retry while the current head is still
                // waiting for connector review. Cron observes this state once
                // per minute; counting observations as executions previously
                // produced misleading FEEDBACK ESCALATION messages and could
                // exhaust the guard before the worker was dispatched.
                if (!ReviewCoordinator.reviewGateOpen(currentPr)) {
                // Bounded loop guard: if the same finding on the same PR head
                // is retried repeatedly without progress, isolate that finding
                // instead of consuming the retry budget for the whole PR.
                String findingKey = activeFinding == null
                        ? "unknown" : ReviewFindingQueue.key(activeFinding);
                String lastGuardKey = Engine.asString(state.get("feedback_guard_key"));
                int maxAttempts = (int) Engine.asLongOrDefault(
                        automation.get("max_feedback_attempts"), 3);
                // Single source of truth for the per-finding feedback guard
                // (Requirement 10): ProgressGuard owns the key migration and the
                // increment/reset semantics; state writes below preserve the
                // exact prior persisted shape.
                ProgressGuard.FeedbackOutcome fb = ProgressGuard.observeFeedback(
                        head, findingKey, lastGuardKey,
                        Engine.asString(state.get("feedback_guard_head")),
                        (int) Engine.asLongOrDefault(state.get("feedback_attempts"), 0),
                        maxAttempts);
                state.put("feedback_guard_key", fb.guardKey());
                int feedbackAttempts = fb.attempts();
                state.put("feedback_attempts", feedbackAttempts);
                if (fb.capExceeded()) {
                    PrScopedResult cap = handleFeedbackCapExceeded(
                            current, currentPr, head, feedbackAttempts, activeFeedback);
                    if (cap != null) {
                        return cap;
                    }
                }
                }
            } else {
                // No actionable feedback on this head: reset the guard.
                state.remove("feedback_guard_key");
                state.remove("feedback_attempts");
            }
            // Mutate the caller's list in place (do not reassign the parameter)
            // so the populated feedback reaches runWorkerPhase's delivery.
            feedbackToResolve.clear();
            feedbackToResolve.addAll(activeFeedback);
            String[] reconciled = Engine.reconcile(state, currentPr, !feedback.isEmpty());
            String detectedPhase = reconciled[0];
            // Do not demote a manual-merge wait back to READY_TO_MERGE for a
            // head that was already processed into the manual-merge gate.
            if ("WAITING_FOR_MANUAL_MERGE".equals(state.get("phase"))
                    && "READY_TO_MERGE".equals(detectedPhase)
                    && Engine.asString(currentPr.get("headRefOid"))
                            .equals(Engine.asString(state.get("last_processed_commit")))) {
                detectedPhase = "WAITING_FOR_MANUAL_MERGE";
            }
            // When a repeated BLOCKED feedback verdict routed us to
            // reconciliation, keep BLOCKED_REQUIREMENTS rather than letting
            // classify_pr pull us back into CHANGES_REQUESTED/feedback.
            if (Engine.asBool(state.get("feedback_reconcile_pending"), false)) {
                state.remove("feedback_reconcile_pending");
                state.put("phase", "BLOCKED_REQUIREMENTS");
            } else {
                state.put("phase", detectedPhase);
            }
            lock.refresh();

            try {
                if (review.ensureCodexReviewTrigger(github, currentPr, state, workflow, dryRun, this::save)) {
                    log.info("PR COMMENT POSTED: @codex review on PR #" + currentPr.get("number"));
                }
            } catch (GitHubError exc) {
                return PrScopedResult.exit(blockedExternal("Unable to post Codex review trigger: " + exc.getMessage()));
            }

            if (ReviewCoordinator.reviewGateOpen(currentPr)) {
                state.put("phase", "WAITING_FOR_CODEX_REVIEW");
                state.put("review_gate_head", currentPr.get("headRefOid"));
                state.put("next_action", "WAIT_FOR_CODEX_REVIEW");
                save();
                logStableState("WAIT_CODEX|" + current + "|" + shortSha(currentPr.get("headRefOid")),
                        "WAITING: story #{}, phase=WAITING_FOR_CODEX_REVIEW", current);
                return PrScopedResult.exit(0);
            }
            // The gate has closed for this head; record the connector review.
            if (Engine.asString(state.get("review_gate_head"))
                    .equals(Engine.asString(currentPr.get("headRefOid")))
                    && !Engine.asString(currentPr.get("headRefOid")).isEmpty()) {
                state.put("last_connector_review_commit", currentPr.get("headRefOid"));
                state.remove("review_gate_head");
            }

            String phase = Engine.asString(state.get("phase"));
            if (Set.of("WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW", "WAITING_FOR_HUMAN_REVIEW",
                    "READY_TO_MERGE", "WAITING_FOR_MANUAL_MERGE").contains(phase)) {
                Integer gate = handleWaitAndMergeGate(current, currentPr, phase);
                if (gate != null) {
                    return PrScopedResult.exit(gate);
                }
            }
            if (phase.equals("MERGED")) {
                advanceStory(current);
                save();
                log.info("MERGED: story #" + current + "; next run may select another story");
                return PrScopedResult.exit(0);
            }
            if (Set.of("BLOCKED_EXTERNAL", "CONFLICT_DETECTED").contains(phase)) {
                // Do not dead-end: let the generic deviation supervisor below
                // detect this and escalate to the reasoning reconcile phase.
                // Only a non-recoverable deviation results in a hard stop.
                int maxAttempts = (int) Engine.asLongOrDefault(
                        automation.get("max_phase_attempts"), 5);
                Engine.Deviation stopDeviation = Engine.detectDeviation(state, currentPr, maxAttempts);
                if (stopDeviation == null || !stopDeviation.recoverable()) {
                    save();
                    logStableState("STOPPED|" + current + "|" + state.get("phase"),
                            "STOPPED: story #{}, phase={}", current, state.get("phase"));
                    return PrScopedResult.exit(1);
                }
                log.info("STOP DEFERRED: story #{} phase={} — escalating to supervisor",
                        current, phase);
                // fall through to the deviation supervisor
            }
        }

        // No PR yet (or the PR-scoped block fell through): continue with the
        // (possibly updated) current PR.
        return PrScopedResult.proceed(currentPr);
    }

    /** Result of the deviation supervisor: non-null exitCode ends the run. */
    private record DeviationResult(Integer exitCode, String forcedPrompt) {
        static DeviationResult exit(int code) {
            return new DeviationResult(code, null);
        }
        static DeviationResult proceed(String forcedPrompt) {
            return new DeviationResult(null, forcedPrompt);
        }
    }

    /**
     * Generic deviation supervisor (Requirement 2): stale stuck-head handling
     * (incl. park-and-advance), deterministic COMMITTED_NO_PR recovery, and
     * escalation to the reconcile prompt. Behavior-identical to the former
     * inline block. Returns a carrier: a non-null exit code ends the run,
     * otherwise the (possibly forced) prompt flows onward.
     */
    private DeviationResult runDeviationSupervisor(Integer current, Map<String, Object> currentPr,
                                                   String forcedPrompt) {
        int maxPhaseAttempts = (int) Engine.asLongOrDefault(
                automation.get("max_phase_attempts"), 5);
        // A prior terminal stuck_head marker must not suppress a genuinely new
        // fix. If the worktree now has deliverable changes (the worker produced
        // a real fix since the head was marked stuck), clear the marker so the
        // deviation supervisor can route it to delivery instead of treating the
        // head as permanently dead.
        if (!Engine.asString(state.get("stuck_head")).isEmpty()) {
            // stuck_head has two distinct meanings, tracked by stuck_head_reason:
            //  - DELIVERY_DISABLED: stuck only because delivery is off (dry-run /
            //    allow_push=false). Stale once delivery is enabled -> clear.
            //  - UNRECOVERABLE: the worker genuinely cannot produce a fix on this
            //    head after repeated attempts. Terminal in ALL modes; only a NEW
            //    head (advanced commit) clears it (handled by Engine.detectDeviation
            //    returning no deviation while stuck_head == currentHead). It must
            //    NOT be cleared just because delivery is enabled — doing so
            //    re-enabled the reconcile/feedback loop.
            String stuckReason = Engine.asString(state.get("stuck_head_reason"));
            boolean unrecoverable = "UNRECOVERABLE".equals(stuckReason);
            if (unrecoverable && ParkPolicy.enabled(workflow) && current != null) {
                // Park-and-advance (Requirement 5): instead of holding this story
                // forever, record it as parked on the current head, notify once,
                // reset its story-scoped state, and go IDLE so the next tick
                // selects a different eligible story. It auto-unparks when the
                // head advances (handled at PR reconciliation).
                String parkHead = Engine.asString(state.get("stuck_head"));
                boolean newlyParked = ParkPolicy.park(state, current, parkHead,
                        "UNRECOVERABLE", Controller.now());
                if (newlyParked && !dryRun && Engine.asBool(workflow.get("allow_comments"), false)
                        && currentPr != null) {
                    try {
                        github.comment(Engine.asInt(currentPr.get("number")),
                                "⏸️ PARKED — story #" + current + " could not be advanced on head "
                                + shortSha(parkHead) + " after repeated automated attempts. The "
                                + "controller has parked it and will continue with the next eligible "
                                + "story; it auto-resumes if this head advances. Operator action may "
                                + "be needed.");
                    } catch (GitHubError ignored) {
                        // notification is best-effort; parking still proceeds
                    }
                }
                log.info("PARKED: story #{} on head {} (reason UNRECOVERABLE); advancing to the "
                        + "next eligible story", current, shortSha(parkHead));
                WorkflowStateCodec.clearStoryScoped(state);
                state.put("current_story", null);
                state.put("current_pr", null);
                state.put("phase", "IDLE");
                save();
                return DeviationResult.exit(0);
            }
            if (unrecoverable) {
                // Leave the terminal marker in place. detectDeviation returns no
                // deviation for this head, so the controller holds it blocked
                // quietly until the head changes or an operator intervenes.
                logStableState("STUCK_TERMINAL|" + shortSha(state.get("stuck_head")),
                        "STUCK-HEAD TERMINAL: PR head {} is unrecoverable by the automated worker; "
                        + "holding blocked until the head advances or an operator acts",
                        shortSha(state.get("stuck_head")));
            } else if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)) {
                log.info("STUCK-HEAD CLEARED: delivery is enabled; discarding stale delivery-disabled "
                        + "marker for head {}", shortSha(state.get("stuck_head")));
                state.remove("stuck_head");
                state.remove("stuck_head_reason");
            } else {
                try {
                    GitOperations probe = gitFactory.create();
                    if (probe.classifyWorktree(state) != GitOperations.WorktreeState.CLEAN) {
                        log.info("STUCK-HEAD CLEARED: deliverable changes present; allowing delivery "
                                + "of the new fix for head {}", shortSha(state.get("stuck_head")));
                        state.remove("stuck_head");
                        state.remove("stuck_head_reason");
                    }
                } catch (RuntimeException ignored) {
                    // If we cannot classify, leave the marker; the guard still caps loops.
                }
            }
        }
        Engine.Deviation deviation = Engine.detectDeviation(state, currentPr, maxPhaseAttempts);
        if (deviation != null && deviation.recoverable()) {
            log.info("DEVIATION DETECTED: reason={} detail={}", deviation.code(), deviation.detail());
            state.put("deviation_code", deviation.code());
            state.put("deviation_detail", deviation.detail());

            // Fast, deterministic recovery when the fix is obvious and safe:
            // committed work with no PR just needs the PR opened and CI to run.
            boolean committedNoPr = "COMMITTED_NO_PR".equals(deviation.code());
            if (!dryRun && committedNoPr
                    && Engine.asBool(workflow.get("allow_push"), false)
                    && Engine.asBool(workflow.get("allow_pr_creation"), false)) {
                GitOperations recoveryGit = gitFactory.create();
                try {
                    recoveryGit.checkoutStoryBranch(Engine.asString(state.get("branch")));
                } catch (RuntimeException exc) {
                    log.error("SUPERVISOR RECOVERY: could not check out story branch {}: {}",
                            state.get("branch"), exc.getMessage());
                }
                log.info("SUPERVISOR RECOVERY: story #{} committed but no PR (phase {}, attempt {}); "
                        + "creating PR and deferring to CI", current,
                        Engine.asString(state.get("phase")), state.get("phase_attempt"));
                state.put("phase", "WAITING_FOR_CI");
                state.put("result_status", "SUCCESS");
                state.put("result_evidence", "DEFERRED_TO_CI");
                state.put("delivery_mode", "CI_DEFERRED");
                state.remove("error");
                save();
                int deliveryResult = runDeliveryPipeline(
                        recoveryGit, current, null, "", new ArrayList<>(), true);
                state.put("run_finished_at", Controller.now());
                save();
                // Report the concrete outcome so a persistent failure is visible
                // in the logs instead of appearing to silently loop.
                if (state.get("current_pr") != null) {
                    log.info("SUPERVISOR RECOVERY COMPLETE: story #{} PR #{} created; phase={}",
                            current, state.get("current_pr"), state.get("phase"));
                } else {
                    log.error("SUPERVISOR RECOVERY DID NOT CREATE A PR: story #{} phase={} error={}",
                            current, state.get("phase"), state.get("error"));
                }
                return DeviationResult.exit(deliveryResult == Integer.MIN_VALUE ? 0 : deliveryResult);
            }

            // Otherwise escalate to the reasoning supervisor (reconcile.md),
            // which diagnoses the deviation and returns a bounded recovery
            // action the controller validates and applies. Force the reconcile
            // prompt regardless of the raw phase so the intelligence layer runs.
            forcedPrompt = "prompts/reconcile.md";
            log.info("SUPERVISOR ESCALATION: routing deviation {} to reasoning reconcile",
                    deviation.code());
        }
        return DeviationResult.proceed(forcedPrompt);
    }

    /**
     * Render the phase prompt, run the bounded worker, and apply its result:
     * worktree reconciliation, structured-result application, reconcile-decision
     * handling (with the ProgressGuard resume anti-loop), and delivery. Extracted
     * verbatim from the former inline tail of {@code reconcileAndExecute}
     * (Requirement 2). Returns the run exit code.
     */
    private int runWorkerPhase(Integer current, Map<String, Object> currentPr,
                               List<Map<String, Object>> feedbackToResolve, String forcedPrompt) {
        // Phase dispatch: render prompt and run the bounded worker.
        String phasePrompt = forcedPrompt != null ? forcedPrompt
                : Controller.phasePromptFor(Engine.asString(state.get("phase")));
        Path prompt = root.resolve(phasePrompt);
        if (!Files.exists(prompt)) {
            state.put("phase", "BLOCKED_CONFIGURATION");
            state.put("error", "Prompt not found: " + prompt);
            save();
            log.error(String.valueOf(state.get("error")));
            return 1;
        }

        GitOperations git = gitFactory.create();

        // Reconcile the worktree (recover/checkout the story branch, classify the
        // tree, enforce the allow_push gate, and cut a fresh branch when needed)
        // before dispatching the worker. Returns a non-null exit code when the
        // run must end here (blocked/reconcile), or null to proceed.
        Integer prep = prepareWorktreeForPhase(git, current, currentPr);
        if (prep != null) {
            return prep;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("epic", epic);
        context.put("current_story", current);
        context.put("current_pr", currentPr);
        context.put("state", state);
        Path renderedPrompt = root.resolve("state").resolve("prompt-" + epicNumber + ".md");
        try {
            Files.writeString(renderedPrompt, PromptRenderer.render(root, phasePrompt, context));
        } catch (java.io.IOException exc) {
            return blockedExternal("Unable to render prompt: " + exc.getMessage());
        }

        String executionPhase = PromptRenderer.executionPhaseForPrompt(prompt.getFileName().toString());
        String previousPhase = Engine.asString(state.get("phase"));
        state.put("phase", executionPhase);
        state.put("worker_status", "RUNNING");
        state.put("worker_started_at", Controller.now());
        state.put("worker_heartbeat_at", Controller.now());
        state.put("phase_started_at", !previousPhase.equals(executionPhase)
                ? Controller.now()
                : (Engine.asString(state.get("phase_started_at")).isEmpty()
                        ? Controller.now() : state.get("phase_started_at")));
        state.put("phase_attempt", (int) Engine.asLongOrDefault(state.get("phase_attempt"), 0) + 1);
        if ("FIXING_FEEDBACK".equals(executionPhase)) {
            ReviewFindingQueue.markActiveDispatched(state);
        }
        save();

        String executionId = Engine.asString(state.get("execution_id")).isEmpty()
                ? ProcessHandle.current().pid() + "-" + System.nanoTime()
                : Engine.asString(state.get("execution_id"));
        String jobId = executionId + ":codex";
        store.startJob(jobId, "CODEX_WORKER", executionPhase,
                current, currentPr == null ? null : Engine.asInt(currentPr.get("number")),
                currentPr == null ? null : Engine.asString(currentPr.get("headRefOid")),
                System.getenv("CODEX_AUTOMATION_LOG_FILE"));

        Integer phaseBudget = phaseBudget(executionPhase);
        Runnable heartbeat = () -> {
            lock.refresh();
            store.heartbeatJob(jobId, "codex pid active; phase=" + executionPhase);
            // Also refresh the durable state heartbeat so operators (and the
            // stale-heartbeat monitor) see the worker is alive during a long
            // phase, not a value frozen at phase entry.
            state.put("worker_heartbeat_at", Controller.now());
            state.put("heartbeat_at", Controller.now());
            try {
                save();
            } catch (RuntimeException ignored) {
                // best-effort; a heartbeat persistence failure must not abort work
            }
        };
        log.info("PHASE ENTER: {} story=#{} pr=#{} attempt={} budget={}s prompt={}",
                executionPhase, current,
                currentPr == null ? "none" : currentPr.get("number"),
                state.get("phase_attempt"),
                phaseBudget == null ? "default" : phaseBudget,
                prompt.getFileName());
        long phaseStartNanos = System.nanoTime();
        CodexRunner.CodexResult result = codexRunner.run(configForPhase(executionPhase),
                renderedPrompt, repo, dryRun, heartbeat, phaseBudget);
        long phaseElapsedMs = (System.nanoTime() - phaseStartNanos) / 1_000_000L;
        log.info("PHASE EXIT: {} exit_code={} elapsed={}ms", executionPhase,
                result.exitCode(), phaseElapsedMs);
        lock.refresh();

        String workerStatus = result.exitCode() == 0 ? "SUCCEEDED"
                : (result.exitCode() == 124 ? "TIMED_OUT" : "FAILED");
        // NOTE: the terminal job record is written once, below, as `journalStatus`
        // (which downgrades a zero-exit-but-non-SUCCESS worker to BLOCKED). An
        // earlier finishJob call here was redundant — it wrote a status that was
        // immediately overwritten a few lines later — so it was removed to avoid
        // duplicate state writes and a duplicate output redaction pass per phase.

        // Defensive post-Codex PR adoption: a worker or an interrupted
        // controller may have created a PR without persisting its number.
        currentPr = adoptPrAfterCodex(current, currentPr);

        state.putAll(Engine.applyCodexResult(state, result.exitCode(), result.output(),
                (int) Engine.asLongOrDefault(automation.get("quota_retry_seconds"), 1800)));

        if ("FIXING_FEEDBACK".equals(executionPhase)) {
            ReviewFindingQueue.recordWorkerResult(state, result.exitCode(),
                    Engine.asString(state.get("result_status")),
                    codexRunner.redactSensitive(tail(result.output(), 1000)));
        }

        // Record the FIXING_FEEDBACK verdict per head so a repeated BLOCKED
        // result escalates to human review instead of being re-dispatched.
        if ("FIXING_FEEDBACK".equals(executionPhase)) {
            String resultStatus = Engine.asString(state.get("result_status"));
            if (!"SUCCESS".equals(resultStatus)) {
                state.put("last_feedback_result", "BLOCKED");
                state.put("last_feedback_result_head",
                        currentPr == null ? "" : Engine.asString(currentPr.get("headRefOid")));
            } else {
                state.remove("last_feedback_result");
                state.remove("last_feedback_result_head");
            }
        }

        // Journal: a zero exit with a non-SUCCESS structured result is BLOCKED.
        String journalStatus = workerStatus;
        if (result.exitCode() == 0 && !"SUCCESS".equals(state.get("result_status"))) {
            journalStatus = "BLOCKED";
        }
        store.finishJob(jobId, journalStatus, result.exitCode(),
                codexRunner.redactSensitive(tail(result.output(), 1000)));

        // Reconciliation phase applies a bounded decision, not completion.
        if (prompt.getFileName().toString().equals("reconcile.md")) {
            return handleReconcileDecision(git, current, currentPr, result, renderedPrompt,
                    feedbackToResolve);
        }

        if (result.exitCode() != 0) {
            state.put("raw_error", codexRunner.redactSensitive(tail(result.output(), 2000)));
        }

        // A zero exit only means the CLI completed; the structured worker
        // result controls whether mutations are allowed. Non-SUCCESS fails closed.
        if (result.exitCode() == 0 && "SUCCESS".equals(state.get("result_status"))) {
            // Pre-commit sequential specialist review gate (opt-in via
            // workflow.internal_review). Reviews the LOCAL, uncommitted diff and
            // runs bounded per-specialist fix loops BEFORE the change is
            // committed/pushed, so issues are caught privately instead of
            // flooding the post-PR Codex review with threads. Fully autonomous:
            // it never blocks the loop — an unsatisfiable specialist is carried
            // forward as advisory and the post-PR Codex review remains the
            // authority. Only runs for phases that produce a committable diff.
            if (Engine.asBool(workflow.get("internal_review"), false)
                    && ("IMPLEMENTING".equals(executionPhase)
                        || "FIXING_FEEDBACK".equals(executionPhase))) {
                runInternalReviewGate(git, current, context, heartbeat, phaseBudget);
            }
            int deliveryResult = runDeliveryPipeline(git, current, currentPr, result.output(), feedbackToResolve);
            if (deliveryResult != Integer.MIN_VALUE) {
                cleanup(renderedPrompt);
                return deliveryResult;
            }
        }

        if (result.exitCode() == 0 && !"SUCCESS".equals(state.get("result_status"))) {
            state.put("phase", "BLOCKED_REQUIREMENTS");
            Object blockers = state.get("blockers");
            String error = "Codex did not return an explicit SUCCESS result";
            if (blockers instanceof List<?> list && !list.isEmpty()) {
                error = String.valueOf(list.get(list.size() - 1));
            }
            state.put("error", error);
        }
        state.put("exit_code", result.exitCode());
        state.put("run_finished_at", Controller.now());
        cleanup(renderedPrompt);
        save();
        return result.exitCode();
    }

    /**
     * Post-SUCCESS delivery: commit/push, verify local==origin==PR head, reply
     * to and resolve fixed threads (gated by workflow flags), and create a PR
     * for a READY_TO_MERGE story with no PR. Returns an exit code to propagate,
     * or {@link Integer#MIN_VALUE} to continue the normal completion path.
     */
    @SuppressWarnings("unchecked")
    private int runDeliveryPipeline(GitOperations git, Integer current, Map<String, Object> currentPr,
                                    String output, List<Map<String, Object>> feedbackToResolve) {
        return runDeliveryPipeline(git, current, currentPr, output, feedbackToResolve, false);
    }

    /**
     * @param autoResolveAllThreads when true (autonomous auto-delivery), treat
     *        every currently-unresolved review thread as addressed by the
     *        delivered changes: reply with the fix evidence and resolve them,
     *        then re-trigger {@code @codex review}. The connector review is the
     *        self-correcting safety net — a prematurely resolved thread is
     *        re-surfaced by the next review. This keeps the fully autonomous
     *        loop closing threads without a per-thread worker declaration.
     */
    @SuppressWarnings("unchecked")
    private int runDeliveryPipeline(GitOperations git, Integer current, Map<String, Object> currentPr,
                                    String output, List<Map<String, Object>> feedbackToResolve,
                                    boolean autoResolveAllThreads) {
        boolean deliveryVerified = false;
        String deliveredCommit = null;
        boolean newCommitDelivered = false;
        Set<String> declaredFixedIds = ReviewCoordinator.fixedThreadIds(output);
        Set<String> declaredSupersededIds = ReviewCoordinator.supersededThreadIds(output);
        declaredFixedIds.addAll(declaredSupersededIds);

        // On autonomous auto-delivery there is no per-thread FIXED_THREAD
        // declaration (the worker returned a reconcile decision). Treat all
        // currently-unresolved threads on the PR as addressed by the delivered
        // controller-owned changes, and synthesize the feedback list so the
        // reply/resolve loop below has items to act on.
        if (autoResolveAllThreads && currentPr != null) {
            List<Map<String, Object>> synthesized = new ArrayList<>();
            Set<String> allUnresolved = new LinkedHashSet<>();
            for (Object t : Engine.asList(currentPr.get("reviewThreads"))) {
                if (t instanceof Map<?, ?> tm) {
                    Map<String, Object> thread = castMap(tm);
                    String threadId = Engine.asString(thread.get("id"));
                    if (!threadId.isEmpty() && !Engine.asBool(thread.get("isResolved"), true)) {
                        allUnresolved.add(threadId);
                        Map<String, Object> item = new LinkedHashMap<>(thread);
                        item.put("thread_id", threadId);
                        synthesized.add(item);
                    }
                }
            }
            declaredFixedIds = allUnresolved;
            feedbackToResolve = synthesized;
        }

        if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)) {
            try {
                String branch = git.gitBranch();
                String committed = git.commitAndPush(branch, current);
                deliveredCommit = committed;
                if (committed != null) {
                    newCommitDelivered = true;
                    state.put("branch", branch);
                    state.put("last_processed_commit", committed);
                    // A real head advance means progress was made; clear the
                    // deterministic resume-loop guard so future reconciliation
                    // starts fresh rather than counting pre-commit attempts.
                    state.remove("resume_guard_head");
                    state.remove("resume_attempts");
                    // The head advanced, so any prior terminal-stuck marker no
                    // longer applies.
                    state.remove("stuck_head");
                    state.remove("stuck_head_reason");
                }
                if (currentPr != null) {
                    int attempts = (int) Engine.asLongOrDefault(
                            automation.get("delivery_verification_attempts"), 5);
                    double delay = Engine.asLongOrDefault(
                            automation.get("delivery_verification_delay_seconds"), 2);
                    GitOperations.DeliveryResult delivery = git.verifyDeliveredHeadWithRetry(
                            branch, github, Engine.asInt(currentPr.get("number")),
                            attempts, delay, seconds -> sleepSeconds(seconds));
                    deliveryVerified = delivery.verified();
                    currentPr = delivery.pr();
                    if (deliveredCommit == null) {
                        // Nothing new to commit: the fix from a prior run is
                        // already on the PR head. Treat the current verified
                        // head as the delivered commit so declared threads are
                        // still replied to/resolved and cursors advance —
                        // otherwise the same feedback loops forever.
                        deliveredCommit = Engine.asString(currentPr.get("headRefOid"));
                        if (deliveryVerified && !deliveredCommit.isEmpty()) {
                            state.put("last_processed_commit", deliveredCommit);
                        }
                    }
                    if (!deliveryVerified) {
                        throw new IllegalStateException(
                                "local, origin, and PR heads do not match after delivery");
                    }
                    review.ensureCodexReviewTrigger(github, currentPr, state, workflow, dryRun, this::save);
                }
            } catch (RuntimeException exc) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "Unable to commit/push Codex changes: " + exc.getMessage());
                save();
                log.error("DELIVERY FAILED (commit/push): {}", exc.getMessage());
            }
        }

        // Map declared fix ids to canonical thread ids. A worker may declare an
        // id that is not in the authoritative PR thread snapshot — e.g. a
        // truncated/hallucinated id, or a real thread that was already resolved
        // and dropped from the snapshot. Previously this failed CLOSED and
        // BLOCKED the entire run (a single bad id dead-ended the PR forever;
        // observed in production as a permanent BLOCKED on PR #190). Instead,
        // SKIP the unmappable ids (log + record for audit) and proceed to
        // resolve the mappable ones. Anything left genuinely unresolved stays a
        // pending finding and is bounded by the feedback attempt cap (Rule 2),
        // which force-closes it rather than looping.
        ReviewCoordinator.ThreadIdMapping mapping = ReviewCoordinator.canonicalFixedThreadIds(
                currentPr != null ? currentPr : new LinkedHashMap<>(), declaredFixedIds);
        if (!mapping.unknown().isEmpty()) {
            List<String> sorted = new ArrayList<>(mapping.unknown());
            sorted.sort(String::compareTo);
            for (String unknownId : sorted) {
                recordThreadUpdate(unknownId, deliveredCommit == null ? "" : deliveredCommit,
                        "DECLARED_ID_NOT_IN_SNAPSHOT");
            }
            save();
            log.warn("DECLARED IDS SKIPPED: Codex declared review IDs not present in the "
                    + "authoritative PR thread snapshot (skipping, not blocking): {}",
                    String.join(", ", sorted));
        }

        // Reply to and resolve only verified, delivered, explicitly-fixed threads.
        // For the autonomous auto-resolve-all path, additionally require that a
        // genuinely NEW commit was delivered this run — otherwise we would be
        // resolving unfixed findings on an unchanged head, which hides real
        // feedback. When nothing advanced, threads are left open for the
        // connector to re-evaluate.
        boolean mayResolveThreads = deliveryVerified && deliveredCommit != null && currentPr != null
                && !mapping.canonical().isEmpty()
                && (!autoResolveAllThreads || newCommitDelivered);
        if (mayResolveThreads
                && (Engine.asBool(workflow.get("allow_comments"), false)
                    || Engine.asBool(workflow.get("allow_thread_resolution"), false))) {
            try {
                Map<String, Map<String, Object>> threadMap = new LinkedHashMap<>();
                for (Object t : Engine.asList(currentPr.get("reviewThreads"))) {
                    if (t instanceof Map<?, ?> tm) {
                        Map<String, Object> thread = castMap(tm);
                        threadMap.put(Engine.asString(thread.get("id")), thread);
                    }
                }
                for (Map<String, Object> item : feedbackToResolve) {
                    String threadId = Engine.asString(item.get("thread_id"));
                    if (!threadId.isEmpty() && mapping.canonical().contains(threadId)) {
                        Map<String, Object> thread = threadMap.getOrDefault(threadId, item);
                        if (Engine.asBool(workflow.get("allow_comments"), false)
                                && !ReviewCoordinator.threadHasFixReply(thread, deliveredCommit)) {
                            github.replyThread(threadId,
                                    ReviewCoordinator.threadFixReply(thread, deliveredCommit, output));
                            recordThreadUpdate(threadId, deliveredCommit, "REPLIED");
                            save();
                            log.info("PR THREAD REPLIED: " + threadId);
                        }
                        if (Engine.asBool(workflow.get("allow_thread_resolution"), false)) {
                            github.resolveThread(threadId);
                            recordThreadUpdate(threadId, deliveredCommit, "RESOLVED");
                            save();
                            log.info("PR THREAD RESOLVED: " + threadId);
                        }
                    }
                }
            } catch (RuntimeException exc) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "Unable to resolve delivered review feedback: " + exc.getMessage());
            }
        }

        // Advance the durable batch only after the delivered head was verified.
        // A successful worker result without a verified delivery must remain
        // retryable; otherwise an interrupted push could make unresolved
        // findings disappear from the queue.
        if (deliveryVerified && deliveredCommit != null && !mapping.canonical().isEmpty()) {
            Set<String> batchThreadIds = new LinkedHashSet<>();
            for (Map<String, Object> item : feedbackToResolve) {
                String threadId = Engine.asString(item.get("thread_id"));
                if (!threadId.isEmpty()) {
                    batchThreadIds.add(threadId);
                }
            }
            Set<String> fixedThreads = new LinkedHashSet<>(mapping.canonical());
            fixedThreads.retainAll(batchThreadIds);
            Set<String> supersededThreads = new LinkedHashSet<>();
            ReviewCoordinator.ThreadIdMapping supersededMapping =
                    ReviewCoordinator.canonicalFixedThreadIds(
                            currentPr != null ? currentPr : new LinkedHashMap<>(),
                            declaredSupersededIds);
            supersededThreads.addAll(supersededMapping.canonical());
            supersededThreads.retainAll(batchThreadIds);
            ReviewFindingQueue.markVerified(state, fixedThreads, deliveredCommit, "VERIFIED");
            ReviewFindingQueue.markVerified(state, supersededThreads, deliveredCommit, "SUPERSEDED");
            save();
        }

        // Create a PR for a story that has delivered work but no PR yet. This
        // fires for ANY forward phase the worker chose after a SUCCESS result
        // (implementation done -> wants review/CI/merge), not only
        // READY_TO_MERGE. Previously a first-time story whose worker returned
        // WAITING_FOR_CODEX_REVIEW/WAITING_FOR_CI never got a PR, so the
        // workflow stalled waiting for a review on a PR that did not exist.
        Set<String> prReadyPhases = Set.of(
                "READY_TO_MERGE", "WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW",
                "WAITING_FOR_HUMAN_REVIEW", "REVIEWING_CODE", "VERIFYING", "FIXING_FEEDBACK");
        if (!dryRun && currentPr == null
                && prReadyPhases.contains(Engine.asString(state.get("phase")))) {
            if (!Engine.asBool(workflow.get("allow_push"), false)
                    || !Engine.asBool(workflow.get("allow_pr_creation"), false)) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "PR creation is disabled by configuration "
                        + "(requires workflow.allow_push=true and allow_pr_creation=true)");
                save();
                log.error("DELIVERY BLOCKED: {}", state.get("error"));
            } else {
                try {
                    Map<String, Object> created = castMap(
                            review.createStoryPr(git, github, config, current, epicNumber));
                    state.put("current_pr", created.get("number"));
                    state.put("current_pr_url", created.get("url"));
                    state.put("branch", created.get("headRefName"));
                    // After opening the PR, wait for CI/Codex review to run
                    // against the new PR head rather than assuming readiness.
                    state.put("phase", "WAITING_FOR_CI");
                    save();
                    log.info("PR CREATED: story #{} PR #{} (first delivery)",
                            current, created.get("number"));
                    Map<String, Object> createdDetail = github.pr(Engine.asInt(created.get("number")));
                    review.ensureCodexReviewTrigger(github, createdDetail, state, workflow, dryRun, this::save);
                } catch (RuntimeException exc) {
                    state.put("phase", "BLOCKED_EXTERNAL");
                    state.put("error", "Unable to create PR: " + exc.getMessage());
                    save();
                    log.error("PR CREATION FAILED: story #{} — {}", current, exc.getMessage());
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Force-close a review thread without a code fix: post an explanatory reply
     * (when comments are enabled) and resolve the thread on GitHub (when thread
     * resolution is enabled), record the update in the durable journal, and mark
     * the finding FORCE_CLOSED so it is never reprocessed. Shared by Rule 1
     * (outdated thread) and Rule 2 (unfixable after the attempt cap). No-op in
     * dry-run. Returns true if the thread was resolved on GitHub.
     *
     * <p>The reply intentionally records that a genuinely-still-present issue
     * will be re-raised by the next Codex review on the advanced head, so
     * closing here does not permanently hide a real defect.</p>
     */
    private boolean forceCloseThread(Map<String, Object> currentPr, String threadId,
                                     String head, String replyBody, String reason) {
        if (threadId == null || threadId.isEmpty()) {
            return false;
        }
        boolean resolved = false;
        if (!dryRun) {
            try {
                if (Engine.asBool(workflow.get("allow_comments"), false)
                        && replyBody != null && !replyBody.isEmpty()) {
                    github.replyThread(threadId, replyBody);
                    recordThreadUpdate(threadId, head, "FORCE_CLOSE_REPLIED");
                }
                if (Engine.asBool(workflow.get("allow_thread_resolution"), false)) {
                    github.resolveThread(threadId);
                    recordThreadUpdate(threadId, head, "RESOLVED");
                    resolved = true;
                }
            } catch (GitHubError exc) {
                // Best-effort: if GitHub rejects the close, leave the finding as
                // is so a later run can retry; do not crash the workflow.
                log.warn("FORCE-CLOSE: unable to resolve thread {} on PR #{}: {}",
                        threadId, currentPr == null ? "?" : currentPr.get("number"),
                        exc.getMessage());
                return false;
            }
        }
        ReviewFindingQueue.forceClose(state, threadId, reason);
        return resolved;
    }

    /**
     * Rule 1: force-close any unresolved review thread that was raised on a
     * commit strictly older than the current PR head. The rationale: the code
     * the finding anchored to has since changed, so the old thread is not a
     * reliable signal; if the underlying issue is genuinely still present, the
     * next Codex review on the advanced head raises a fresh thread. This keeps
     * the PR clean and prevents the worker from grinding on stale findings.
     *
     * <p>Only threads whose finding carries a non-empty anchoring commit that
     * differs from the current head are closed; a finding raised on the current
     * head (no advance yet) is left untouched so live feedback is honoured.
     * Returns the number of threads closed.</p>
     */
    private int closeOutdatedThreads(Map<String, Object> currentPr, String head) {
        if (currentPr == null || head == null || head.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (Map<String, Object> finding : ReviewFindingQueue.findings(state)) {
            String status = Engine.asString(finding.get("status"));
            if (!"PENDING".equals(status) && !"IN_PROGRESS".equals(status)) {
                continue;
            }
            String threadId = Engine.asString(finding.get("thread_id"));
            if (threadId.isEmpty()) {
                continue;
            }
            String findingCommit = Engine.asString(finding.get("commit"));
            // Only close a thread that was anchored to an EARLIER commit. A
            // finding with no recorded commit, or one raised on the current
            // head, is treated as live and left for the normal feedback path.
            if (findingCommit.isEmpty() || findingCommit.equals(head)) {
                continue;
            }
            String reply = "Auto-closing this review thread: it was raised on commit `"
                    + shortSha(findingCommit) + "`, and the pull request head has since advanced to `"
                    + shortSha(head) + "`. The affected code has changed, so this thread no longer "
                    + "reflects the current head. If the issue is still present, the next automated "
                    + "review will re-raise it against the current commit.\n\n"
                    + "<!-- codex-automation-force-close:thread=" + threadId + ":reason=OUTDATED:head="
                    + head + " -->";
            forceCloseThread(currentPr, threadId, head, reply, "OUTDATED_PASSED_COMMIT");
            log.info("THREAD FORCE-CLOSED (outdated): PR #{} thread {} raised on {} < head {}",
                    currentPr.get("number"), threadId, shortSha(findingCommit), shortSha(head));
            closed++;
        }
        if (closed > 0) {
            save();
        }
        return closed;
    }

    /**
     * Rule 2: force-close the active review finding as unresolvable and set the
     * next phase so the workflow advances instead of dead-ending. Posts an
     * explanatory reply/resolve (best-effort, honoring the config gates), marks
     * the finding FORCE_CLOSED, clears the per-finding/terminal/reconcile guards,
     * and selects {@code FIXING_FEEDBACK} when independent findings remain or
     * {@code REVIEWING_CODE} otherwise. Does not persist or return — the caller
     * owns save() and the run's exit code. Shared by the feedback attempt-cap
     * path and the reconcile no-deliverable path so both behave identically.
     */
    private void forceCloseUnfixableAndAdvance(Map<String, Object> currentPr, String head,
                                               String reasonPhrase) {
        Map<String, Object> active = ReviewFindingQueue.active(state);
        String threadId = active == null ? "" : Engine.asString(active.get("thread_id"));
        if (!threadId.isEmpty()) {
            String reply = "Auto-closing this review thread as unresolvable by automation: "
                    + reasonPhrase + ". Marking the finding invalid and continuing. If the issue is "
                    + "still present, the next automated review will re-raise it against the current "
                    + "commit.\n\n<!-- codex-automation-force-close:thread=" + threadId
                    + ":reason=UNFIXABLE_AFTER_CAP:head=" + head + " -->";
            forceCloseThread(currentPr, threadId, head, reply, "UNFIXABLE_AFTER_CAP");
            log.info("THREAD FORCE-CLOSED (unfixable): PR #{} thread {} on head {}",
                    currentPr == null ? "?" : currentPr.get("number"), threadId, shortSha(head));
        }
        state.remove("feedback_guard_key");
        state.remove("feedback_attempts");
        state.remove("stuck_head");
        state.remove("stuck_head_reason");
        state.remove("stuck_finding_key");
        state.remove("resume_guard_head");
        state.remove("resume_attempts");
        state.remove("resume_total_story");
        state.remove("resume_total_attempts");
        state.remove("resume_total_resolved_baseline");
        state.remove("last_feedback_result");
        state.remove("last_feedback_result_head");
        state.remove("error");
        state.put("reconciliation_attempts", 0);
        Map<String, Object> nextFinding = ReviewFindingQueue.next(
                ReviewFindingQueue.findings(state));
        state.put("phase", nextFinding != null ? "FIXING_FEEDBACK" : "REVIEWING_CODE");
        state.put("phase_attempt", 0);
    }

    private void recordThreadUpdate(String threadId, String head, String action) {
        Object existing = state.get("thread_updates");
        List<Object> updates = existing instanceof List<?> list
                ? new ArrayList<>(list) : new ArrayList<>();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("thread_id", threadId);
        update.put("head", head);
        update.put("action", action);
        update.put("at", Controller.now());
        updates.add(update);
        state.put("thread_updates", updates);
    }

    /**
     * Count of review threads this run has RESOLVED for the active story, read
     * from the durable {@code thread_updates} journal. Used as the "genuine
     * progress" signal that resets the cumulative resume guard: a PR advancing
     * through its review cycle resolves threads, so it must never be treated as
     * a no-progress spin.
     */
    private int resolvedThreadCount() {
        Object existing = state.get("thread_updates");
        if (!(existing instanceof List<?> list)) {
            return 0;
        }
        int resolved = 0;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && "RESOLVED".equals(String.valueOf(m.get("action")))) {
                resolved++;
            }
        }
        return resolved;
    }

    private void cleanup(Path renderedPrompt) {
        try {
            Files.deleteIfExists(renderedPrompt);
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }

    private static void sleepSeconds(double seconds) {
        try {
            Thread.sleep((long) (Math.max(0, seconds) * 1000));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Defensive post-Codex PR adoption (Requirement 2). A worker or an
     * interrupted controller may have created a PR without persisting its
     * number; adopt an unambiguous open PR for the story. Returns the
     * (possibly updated) current PR. Behavior-identical to the former inline block.
     */
    private Map<String, Object> adoptPrAfterCodex(Integer current, Map<String, Object> currentPr) {
        if (current != null && currentPr == null && !dryRun) {
            try {
                List<Map<String, Object>> refreshedPrs = new ArrayList<>();
                Object refreshed = github.prs();
                if (refreshed instanceof List<?> list) {
                    for (Object p : list) {
                        if (p instanceof Map<?, ?> m) {
                            refreshedPrs.add(castMap(m));
                        }
                    }
                }
                List<Map<String, Object>> storyPrs = openPrsForStory(refreshedPrs, current);
                if (storyPrs.size() > 1) {
                    state.put("phase", "CONFLICT_DETECTED");
                    state.put("error", "Multiple open PRs for story #" + current);
                } else if (!storyPrs.isEmpty()) {
                    currentPr = github.pr(Engine.asInt(storyPrs.get(0).get("number")));
                    state.put("current_pr", currentPr.get("number"));
                    state.put("current_pr_url", currentPr.get("url"));
                    state.put("current_pr_head", currentPr.get("headRefOid"));
                    state.put("branch", currentPr.get("headRefName"));
                    log.info("PR ADOPTED AFTER CODEX: story #" + current
                            + ", PR #" + currentPr.get("number"));
                    review.ensureCodexReviewTrigger(github, currentPr, state, workflow, dryRun, this::save);
                }
            } catch (GitHubError exc) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "Unable to reconcile PR after Codex execution: " + exc.getMessage());
            }
        }
        return currentPr;
    }

    /** Result of story selection/adoption: non-null exitCode ends the run. */
    private record SelectionResult(Integer exitCode, Integer current, Map<String, Object> currentPr) {
        static SelectionResult exit(int code) {
            return new SelectionResult(code, null, null);
        }
        static SelectionResult proceed(Integer current, Map<String, Object> currentPr) {
            return new SelectionResult(null, current, currentPr);
        }
    }

    /**
     * Select the active story, adopt an unambiguous open PR, verify eligibility
     * and dependencies, and clear PR-scoped state when idle (Requirement 2:
     * SelectStory handler). Behavior-identical to the former inline block.
     */
    private SelectionResult selectAndAdoptStory(List<Map<String, Object>> prs,
                                                Integer current, Map<String, Object> currentPr) {
        // Story selection when none is active.
        if (current == null) {
            List<Object[]> candidates = new ArrayList<>();
            for (int story : Engine.linkedStoryNumbers(epic)) {
                List<Map<String, Object>> matches = Engine.prForStory(prs, story);
                List<Map<String, Object>> open = new ArrayList<>();
                for (Map<String, Object> p : matches) {
                    if ("OPEN".equals(p.get("state"))) {
                        open.add(p);
                    }
                }
                if (open.size() > 1) {
                    return SelectionResult.exit(conflict("Multiple open PRs for story #" + story));
                }
                if (!open.isEmpty()) {
                    candidates.add(new Object[] {story, open.get(0)});
                }
            }
            if (candidates.size() > 1) {
                return SelectionResult.exit(conflict("Multiple open story PRs"));
            }
            if (!candidates.isEmpty()) {
                current = (Integer) candidates.get(0)[0];
                currentPr = castMap(candidates.get(0)[1]);
                state.put("current_story", current);
                state.put("current_pr", currentPr.get("number"));
                state.put("current_pr_url", currentPr.get("url"));
            } else {
                List<Map<String, Object>> stories = new ArrayList<>();
                try {
                    for (int number : Engine.linkedStoryNumbers(epic)) {
                        stories.add(castMap(github.issue(number)));
                    }
                } catch (GitHubError exc) {
                    return SelectionResult.exit(
                            blockedExternal("Unable to inspect linked stories: " + exc.getMessage()));
                }
                // Park-and-advance (Requirement 5, config-gated): exclude
                // stories parked as terminally unrecoverable so the controller
                // continues with the next eligible story. Under the default
                // "hold" policy the parked set is empty and selection is
                // unchanged.
                List<Map<String, Object>> selectable = ParkPolicy.enabled(workflow)
                        ? ParkPolicy.excludeParked(stories, state) : stories;
                Map<String, Object> selected = Engine.selectStory(selectable, completedStories());
                if (selected == null) {
                    state.put("phase", "EPIC_COMPLETE");
                    state.put("message", "No eligible incomplete stories remain");
                    save();
                    log.info("EPIC COMPLETE");
                    return SelectionResult.exit(0);
                }
                current = Engine.asInt(selected.get("number"));
                state.put("current_story", current);
                state.put("current_story_url", selected.get("url"));
                state.put("current_story_title", selected.getOrDefault("title", ""));
                // PLANNING gate (Requirement 9, config-gated): when planning is
                // enabled and no valid committed plan exists yet, run PLANNING
                // first; otherwise (default, or a plan already present on resume)
                // go straight to IMPLEMENTING — preserving the current path.
                boolean planningEnabled = Engine.asBool(automation.get("enable_planning"), false);
                if (planningEnabled && !PlanLedgerReader.hasValidPlan(repo)) {
                    state.put("phase", "PLANNING");
                } else {
                    state.put("phase", "IMPLEMENTING");
                }
                save();
            }
        }

        // Adopt an unambiguous open PR for the active story if not recorded.
        currentPr = findRecordedPr(prs, currentPr);
        if (current != null && currentPr == null) {
            List<Map<String, Object>> storyPrs = openPrsForStory(prs, current);
            if (storyPrs.size() > 1) {
                return SelectionResult.exit(conflict("Multiple open PRs for story #" + current));
            }
            if (!storyPrs.isEmpty()) {
                currentPr = storyPrs.get(0);
                state.put("current_pr", currentPr.get("number"));
                state.put("current_pr_url", currentPr.get("url"));
                state.put("branch", currentPr.get("headRefName"));
                // Persist immediately: recovery and reconciliation require a
                // durable branch to resume, and a crash before the next save
                // must not lose it (this was a root cause of the recovery loop).
                save();
                log.info("PR ADOPTED: story #" + current + ", PR #" + currentPr.get("number"));
            }
        }

        // Verify current story eligibility and dependencies.
        if (current != null) {
            Map<String, Object> currentIssue;
            try {
                currentIssue = castMap(github.issue(current));
            } catch (GitHubError exc) {
                return SelectionResult.exit(
                        blockedExternal("Unable to verify current story #" + current + ": " + exc.getMessage()));
            }
            boolean linked = Engine.linkedStoryNumbers(epic).contains(current);
            boolean mergedCurrentPr = currentPr != null && currentPr.get("mergedAt") != null;
            if (!linked || (!"OPEN".equals(Engine.asString(currentIssue.get("state")).toUpperCase()) && !mergedCurrentPr)) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Current story #" + current + " is no longer an eligible linked open story");
                save();
                log.error("BLOCKED: " + state.get("error"));
                return SelectionResult.exit(1);
            }
            state.put("current_story_url", currentIssue.get("url"));
            state.put("current_story_title",
                    currentIssue.getOrDefault("title", state.getOrDefault("current_story_title", "")));
            if (currentPr == null && StateRecovery.clearStaleStorySelectionState(state)) {
                save();
                log.info("RECOVERY: cleared stale story-selection blocker for story #" + current);
            }
            Set<Integer> completed = completedStories();
            for (int dependency : Engine.storyDependencies(currentIssue)) {
                if (completed.contains(dependency)) {
                    continue;
                }
                Map<String, Object> dependencyIssue;
                try {
                    dependencyIssue = castMap(github.issue(dependency));
                } catch (GitHubError exc) {
                    return SelectionResult.exit(
                            blockedExternal("Unable to inspect dependency #" + dependency + ": " + exc.getMessage()));
                }
                if (!"CLOSED".equals(Engine.asString(dependencyIssue.get("state")).toUpperCase())) {
                    state.put("phase", "BLOCKED_REQUIREMENTS");
                    state.put("error", "Story #" + current + " dependency #" + dependency + " is not complete");
                    save();
                    log.error("BLOCKED: " + state.get("error"));
                    return SelectionResult.exit(1);
                }
            }
        }

        // PR-scoped data is cleared when there is no active PR, so stale review
        // threads, CI, or cursors are not misattributed to the next story.
        if (currentPr == null && state.get("current_pr") == null) {
            state.put("current_pr_url", null);
            state.put("current_pr_head", null);
            state.put("pr_state", null);
            state.put("pr_review_decision", null);
            state.put("ci_status", new ArrayList<>());
            state.put("unresolved_feedback", new ArrayList<>());
            state.put("last_feedback_id", null);
            state.put("last_processed_review_id", null);
            state.put("last_review_trigger_commit", null);
            state.put("last_review_trigger_verified_commit", null);
            state.put("review_gate_head", null);
        }
        return SelectionResult.proceed(current, currentPr);
    }

    /**
     * Handle the terminal wait / merge-gate phases for the current PR. Returns
     * a non-null exit code when the run should end here, or {@code null} to fall
     * through to the deviation supervisor. Behavior-identical to the former
     * inline block (extracted for Requirement 2).
     */
    private Integer handleWaitAndMergeGate(Integer current, Map<String, Object> currentPr, String phase) {
        Map<String, Object> workflow = Config.section(config, "workflow");
        if (phase.equals("READY_TO_MERGE") && !dryRun
                && Engine.asBool(workflow.get("allow_comments"), false)) {
            if (!github.hasComment(currentPr, "READY TO MERGE")) {
                github.comment(Engine.asInt(currentPr.get("number")),
                        "✅ READY TO MERGE — Codex review found no issues on the current head ("
                        + shortSha(currentPr.get("headRefOid")) + "), CI is green, and all review "
                        + "threads are resolved. This PR is ready for the manual merge gate. "
                        + "The automation does not merge; a maintainer must merge.");
            }
            state.put("phase", "WAITING_FOR_MANUAL_MERGE");
            state.put("last_processed_commit", currentPr.get("headRefOid"));
            log.info("READY TO MERGE: story #{} PR #{} head={} — posted ready comment; "
                    + "awaiting manual merge", current, currentPr.get("number"),
                    shortSha(currentPr.get("headRefOid")));
        }

        // Fully autonomous merge (opt-in): when workflow.auto_merge is enabled,
        // the ready gate merges the PR instead of waiting for a human. It never
        // force-merges: it merges only when GitHub reports the PR mergeable, and
        // routes fixable blockers back into the autonomous fix loop so the
        // workflow drives toward a legitimate merge without dead-ending. Guarded
        // by dry_run and allow_push.
        if (Set.of("READY_TO_MERGE", "WAITING_FOR_MANUAL_MERGE").contains(
                    Engine.asString(state.get("phase")))
                && !dryRun
                && Engine.asBool(workflow.get("auto_merge"), false)
                && Engine.asBool(workflow.get("allow_push"), false)) {
            String mergeable = Engine.asString(currentPr.get("mergeable")).toUpperCase();
            String mergeStateStatus =
                    Engine.asString(currentPr.get("mergeStateStatus")).toUpperCase();

            if ("CONFLICTING".equals(mergeable) || "DIRTY".equals(mergeStateStatus)) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Auto-merge blocked: PR #" + currentPr.get("number")
                        + " has merge conflicts with the base branch; rebase and resolve "
                        + "conflicts before merging.");
                save();
                log.info("AUTO-MERGE DEFERRED: PR #{} conflicts with base; routing to "
                        + "reconciliation to rebase/resolve", currentPr.get("number"));
                return 1;
            }
            if ("UNKNOWN".equals(mergeable) || "BEHIND".equals(mergeStateStatus)
                    || "UNSTABLE".equals(mergeStateStatus)) {
                state.put("phase", "WAITING_FOR_CI");
                save();
                log.info("AUTO-MERGE WAIT: PR #{} mergeable={} state={}; retrying next run",
                        currentPr.get("number"), mergeable, mergeStateStatus);
                return 0;
            }
            if ("BLOCKED".equals(mergeStateStatus)) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "Auto-merge blocked by branch protection or required "
                        + "checks on PR #" + currentPr.get("number") + " (mergeStateStatus="
                        + mergeStateStatus + "); the automation will not bypass repository "
                        + "protection. Adjust protection rules or merge manually.");
                save();
                log.error("BLOCKED: " + state.get("error"));
                return 1;
            }
            try {
                String mergeMethod = Engine.asString(
                        workflow.getOrDefault("merge_method", "squash"));
                github.mergePr(Engine.asInt(currentPr.get("number")), mergeMethod);
                log.info("AUTO-MERGE: merged PR #{} for story #{} (head {}) via {}",
                        currentPr.get("number"), current,
                        shortSha(currentPr.get("headRefOid")), mergeMethod);
                advanceStory(current);
                save();
                log.info("MERGED: story #{}; next run may select another story", current);
                return 0;
            } catch (GitHubError exc) {
                state.put("phase", "WAITING_FOR_CI");
                state.put("error", "Auto-merge attempt failed for PR #"
                        + currentPr.get("number") + " (will re-evaluate next run): "
                        + exc.getMessage());
                save();
                log.info("AUTO-MERGE RETRY: {}", state.get("error"));
                return 0;
            }
        }
        save();
        String waitSignature = "WAIT|" + state.get("phase") + "|"
                + shortSha(currentPr.get("headRefOid")) + "|"
                + Engine.asString(currentPr.get("reviewDecision"));
        logStableState(waitSignature,
                "WAITING: story #{} phase={} reason={} reviewDecision={} ciChecks={} head={}",
                current, state.get("phase"), waitReason(state.get("phase"), currentPr),
                Engine.asString(currentPr.get("reviewDecision")),
                Engine.asList(currentPr.get("statusCheckRollup")).size(),
                shortSha(currentPr.get("headRefOid")));
        return 0;
    }

    /**
     * Reconcile the worktree before a worker phase (Requirement 2): recover a
     * missing story branch, check it out, classify the tree (routing a dirty
     * default/unknown branch to bounded reconciliation), enforce the allow_push
     * gate, and cut a fresh synchronized story branch when none exists. Returns
     * a non-null exit code when the run must end here, or {@code null} to
     * proceed. Behavior-identical to the former inline preamble.
     */
    /**
     * Handle a feedback head that carries a stuck-head marker (Requirement 2):
     * migrate a legacy PR-level marker, bypass a blocked finding for independent
     * work, clear the marker when delivery is enabled or a new fix exists, or
     * hold terminally. Returns a non-null PrScopedResult to end the run, or
     * {@code null} to continue the feedback path. Behavior-identical to the
     * former inline block.
     */
    /**
     * Handle the feedback attempt cap being exceeded on an unchanging head
     * (Requirement 2): auto-deliver committed work to CI when deliverable,
     * escalate to the reasoning supervisor when nothing is deliverable (returns
     * {@code null} to continue into the pending-reconcile path), or mark the
     * head terminally stuck when delivery is disabled. Behavior-identical to the
     * former inline block.
     */
    private PrScopedResult handleFeedbackCapExceeded(Integer current, Map<String, Object> currentPr,
                                                     String head, int feedbackAttempts,
                                                     List<Map<String, Object>> activeFeedback) {
        if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)) {
            GitOperations fbGit = gitFactory.create();
            try {
                fbGit.checkoutStoryBranch(Engine.asString(state.get("branch")));
            } catch (RuntimeException exc) {
                log.error("FEEDBACK AUTO-DELIVER: checkout failed: {}", exc.getMessage());
            }
            boolean hasDeliverableChanges;
            try {
                hasDeliverableChanges =
                        fbGit.classifyWorktree(state) != GitOperations.WorktreeState.CLEAN;
            } catch (RuntimeException exc) {
                hasDeliverableChanges = false;
            }
            if (hasDeliverableChanges) {
                log.info("FEEDBACK AUTO-DELIVER: PR #{} head {} reprocessed {} times; "
                        + "delivering worker changes and deferring to CI",
                        currentPr.get("number"), shortSha(head), feedbackAttempts);
                state.put("phase", "WAITING_FOR_CI");
                state.put("result_status", "SUCCESS");
                state.put("result_evidence", "DEFERRED_TO_CI");
                state.put("delivery_mode", "CI_DEFERRED");
                state.remove("feedback_guard_key");
                state.remove("feedback_attempts");
                state.remove("error");
                save();
                int deliveryResult = runDeliveryPipeline(
                        fbGit, current, currentPr, "", new ArrayList<>(activeFeedback), false);
                state.put("run_finished_at", Controller.now());
                save();
                log.info("FEEDBACK AUTO-DELIVER COMPLETE: PR #{} phase={}",
                        currentPr.get("number"), state.get("phase"));
                return PrScopedResult.exit(deliveryResult == Integer.MIN_VALUE ? 0 : deliveryResult);
            }
            // Nothing to deliver: Rule 2 — the worker has tried up to the
            // attempt cap and cannot produce a fix. Rather than looping or
            // dead-ending, force-close the active finding thread as invalid and
            // advance. Any committed work stays on the PR; if the issue is
            // genuinely still present, the next Codex review re-raises it.
            forceCloseUnfixableAndAdvance(currentPr, head,
                    "the worker attempted a fix " + feedbackAttempts + " times on head `"
                    + shortSha(head) + "` without a verifiable resolution");
            state.put("run_finished_at", Controller.now());
            save();
            log.info("FEEDBACK CAP RESOLVED BY FORCE-CLOSE: PR #{} advancing (next phase={})",
                    currentPr.get("number"), state.get("phase"));
            // End this tick so the next run re-reads the PR from GitHub with the
            // thread now resolved and re-evaluates from clean state. Continuing
            // within this tick would re-run reconcile on the stale PR snapshot
            // (which still reports CHANGES_REQUESTED until GitHub reflects the
            // resolution) and re-route into the loop we just broke.
            return PrScopedResult.exit(0);
        }
        // Simulation (dry-run) or push disabled: mark terminally stuck and stop
        // so the controller does not re-enter feedback every tick.
        state.put("phase", "BLOCKED_EXTERNAL");
        state.put("stuck_head", head);
        state.put("stuck_head_reason", "DELIVERY_DISABLED");
        state.remove("feedback_guard_key");
        state.remove("feedback_attempts");
        state.put("error", "Feedback for PR #" + currentPr.get("number")
                + " head " + shortSha(head) + " was reprocessed " + feedbackAttempts
                + " times without resolution and delivery is disabled (dry_run or "
                + "allow_push=false)");
        save();
        log.error("BLOCKED: " + state.get("error"));
        return PrScopedResult.exit(1);
    }

    private PrScopedResult handleStuckHeadForFeedback(Map<String, Object> currentPr, String head,
                                                      boolean legacyPrLevelFindingState,
                                                      Map<String, Object> activeFinding,
                                                      boolean deliveringMode) {
        if (!head.equals(Engine.asString(state.get("stuck_head")))) {
            return null;
        }
        String stuckReason = Engine.asString(state.get("stuck_head_reason"));
        if ("UNRECOVERABLE".equals(stuckReason)) {
            boolean legacyMarker = legacyPrLevelFindingState
                    || Engine.asString(state.get("stuck_finding_key")).isEmpty();
            if (legacyMarker) {
                state.remove("stuck_head");
                state.remove("stuck_head_reason");
                state.remove("feedback_guard_head");
                state.remove("feedback_guard_key");
                state.remove("feedback_attempts");
                state.put("phase_attempt", 0);
                state.remove("deviation_code");
                state.remove("deviation_detail");
                log.info("STUCK-HEAD MIGRATED: replacing legacy PR-level marker "
                        + "with per-finding feedback checkpoints");
            } else if (activeFinding != null
                    && !Engine.asString(state.get("stuck_finding_key")).isEmpty()
                    && !ReviewFindingQueue.key(activeFinding).equals(
                            Engine.asString(state.get("stuck_finding_key")))) {
                state.remove("stuck_head");
                state.remove("stuck_head_reason");
                state.remove("stuck_finding_key");
                log.info("STUCK-FINDING BYPASSED: continuing with independent "
                        + "review finding on head {}", shortSha(head));
            } else {
                String findingLabel = activeFinding == null ? "all-findings"
                        : ReviewFindingQueue.key(activeFinding);
                Integer parkStory = state.get("current_story") == null
                        ? null : Engine.asInt(state.get("current_story"));
                // Park-and-advance (Requirement 5) also applies on the feedback
                // path: when the policy is enabled, do not hold this story
                // forever. Record it as parked on the current head, notify once,
                // reset its story-scoped state, and go IDLE so the next tick
                // selects a different eligible story. It auto-unparks when the
                // head advances. This mirrors runDeviationSupervisor and honours
                // the "no dead-end, no human in the delivery loop" guarantee.
                if (ParkPolicy.enabled(workflow) && parkStory != null) {
                    boolean newlyParked = ParkPolicy.park(state, parkStory, head,
                            "UNRECOVERABLE", Controller.now());
                    if (newlyParked && !dryRun
                            && Engine.asBool(workflow.get("allow_comments"), false)) {
                        try {
                            github.comment(Engine.asInt(currentPr.get("number")),
                                    "⏸️ PARKED — story #" + parkStory + " could not be advanced on "
                                    + "head " + shortSha(head) + " after repeated automated attempts "
                                    + "on review finding " + findingLabel + ". The controller has "
                                    + "parked it and will continue with the next eligible story; it "
                                    + "auto-resumes if this head advances. Operator action may be "
                                    + "needed.");
                        } catch (GitHubError ignored) {
                            // notification is best-effort; parking still proceeds
                        }
                    }
                    log.info("PARKED: story #{} on head {} finding {} (reason UNRECOVERABLE); "
                            + "advancing to the next eligible story",
                            parkStory, shortSha(head), findingLabel);
                    WorkflowStateCodec.clearStoryScoped(state);
                    state.put("current_story", null);
                    state.put("current_pr", null);
                    state.put("phase", "IDLE");
                    save();
                    return PrScopedResult.exit(0);
                }
                // Default "hold" policy: keep the head blocked, but log the hold
                // idempotently. A raw per-tick log.info here produced an
                // unbounded flood (observed as 800+ identical lines/hours in
                // production); logStableState suppresses the repeat to DEBUG.
                // Log BEFORE save() so the updated last_stable_log_signature is
                // persisted and the next tick recognises the repeat.
                state.put("phase", "BLOCKED_EXTERNAL");
                logStableState("FEEDBACK_STUCK_TERMINAL|" + shortSha(head) + "|" + findingLabel,
                        "BLOCKED (terminal): PR #{} finding {} on head {} is unrecoverable; "
                                + "holding until the finding or head changes",
                        currentPr.get("number"), findingLabel, shortSha(head));
                save();
                return PrScopedResult.exit(1);
            }
        }
        if (deliveringMode) {
            log.info("STUCK-HEAD CLEARED: delivery is enabled for head {}; "
                    + "resuming the normal feedback path", shortSha(head));
            state.remove("stuck_head");
            state.remove("stuck_head_reason");
        } else {
            boolean newFix = false;
            try {
                newFix = gitFactory.create().classifyWorktree(state)
                        != GitOperations.WorktreeState.CLEAN;
            } catch (RuntimeException ignored) {
                newFix = false;
            }
            if (newFix) {
                log.info("STUCK-HEAD CLEARED: deliverable changes present for head {}; "
                        + "allowing a fresh feedback attempt", shortSha(head));
                state.remove("stuck_head");
                state.remove("stuck_head_reason");
            } else {
                state.put("phase", "BLOCKED_EXTERNAL");
                save();
                log.info("BLOCKED (terminal): PR #{} head {} feedback remains unresolved "
                        + "and delivery is disabled; awaiting a new head or operator action",
                        currentPr.get("number"), shortSha(head));
                return PrScopedResult.exit(1);
            }
        }
        return null;
    }

    /**
     * Terminally stick the current PR head after the consecutive
     * reconcile-failure/rejection cap is reached. Setting {@code stuck_head} to
     * the current head makes {@link Engine#detectDeviation} return {@code null}
     * for this head on the next cron cycle, so the loop halts deterministically
     * WITHOUT a human — and it survives the PR-scoped {@code FIXING_FEEDBACK}
     * re-entry, which was the flag-flip that previously prevented the cap from
     * accumulating. A genuinely new head (a fresh commit) does not equal
     * {@code stuck_head}, so normal processing resumes automatically.
     */
    private void markReconcileHeadStuck(Map<String, Object> currentPr, int attempts, String reason) {
        state.put("phase", "BLOCKED_EXTERNAL");
        String head = currentPr == null ? "" : Engine.asString(currentPr.get("headRefOid"));
        if (!head.isEmpty()) {
            state.put("stuck_head", head);
            // UNRECOVERABLE is the existing terminal reason honored by BOTH the
            // deviation supervisor (holds blocked, never re-clears in delivering
            // mode) and Engine.detectDeviation (returns null for this head). Any
            // other reason would be treated as a stale delivery-disabled marker
            // and cleared, re-enabling the loop.
            state.put("stuck_head_reason", "UNRECOVERABLE");
            Map<String, Object> blockedFinding = ReviewFindingQueue.active(state);
            if (blockedFinding != null) {
                state.put("stuck_finding_key", ReviewFindingQueue.key(blockedFinding));
            }
        }
        state.put("error", reason);
        log.error("BLOCKED (terminal): PR #{} head {} — {}",
                currentPr == null ? "none" : currentPr.get("number"), shortSha(head), reason);
    }

    /**
     * Apply the reconcile-worker decision (Requirement 2): accept/reject the
     * bounded decision, run the ProgressGuard resume anti-loop, and perform the
     * autonomous auto-deliver / terminal-block / PROCESS_FEEDBACK routing.
     * Behavior-identical to the former inline reconcile.md branch. Returns the
     * run exit code.
     */
    private int handleReconcileDecision(GitOperations git, Integer current, Map<String, Object> currentPr,
                                        CodexRunner.CodexResult result, Path renderedPrompt,
                                        List<Map<String, Object>> feedbackToResolve) {
        // A fresh PR head (a new commit) is a recovery signal: clear any stale
        // terminal-stuck marker and the consecutive reconcile-failure counter so
        // normal processing resumes automatically without operator action.
        String currentHead = currentPr == null
                ? "" : Engine.asString(currentPr.get("headRefOid"));
        String stuckHead = Engine.asString(state.get("stuck_head"));
        if (!currentHead.isEmpty() && !stuckHead.isEmpty() && !currentHead.equals(stuckHead)) {
            state.remove("stuck_head");
            state.remove("stuck_head_reason");
            state.put("reconciliation_attempts", 0);
            log.info("RECONCILE HEAD ADVANCED: cleared terminal stuck marker; "
                    + "resuming normal processing on head {}", shortSha(currentHead));
        }
        // FIX (root cause of the infinite reconcile loop): a NON-ZERO reconcile
        // worker exit is a WORKER/TOOLING FAILURE, not an AI decision. It must
        // NOT be parsed by applyReconciliationDecision and mislabeled as an
        // "invalid decision". Engine.applyCodexResult already ran in
        // runWorkerPhase and classified the exit into PAUSED_QUOTA /
        // FAILED_RECOVERABLE / CODEX_FAILURE — keep that phase.
        if (result.exitCode() != 0) {
            String failurePhase = Engine.asString(state.get("phase"));
            // A quota pause is transient: honor it via the retry_at backoff and
            // do NOT count it toward the terminal reconcile-failure cap.
            if ("PAUSED_QUOTA".equals(failurePhase)) {
                state.put("exit_code", result.exitCode());
                state.put("run_finished_at", Controller.now());
                save();
                log.info("RECONCILE WORKER PAUSED (quota): exit={} phase={} retry_at={}",
                        result.exitCode(), failurePhase, state.get("retry_at"));
                return result.exitCode();
            }
            int attempts = (int) Engine.asLongOrDefault(
                    state.get("reconciliation_attempts"), 0) + 1;
            state.put("reconciliation_attempts", attempts);
            log.info("RECONCILE WORKER FAILED: exit={} phase={} attempt={}",
                    result.exitCode(), failurePhase, attempts);
            if (ProgressGuard.reconcileCapReached(attempts)) {
                markReconcileHeadStuck(currentPr, attempts,
                        "reconcile worker failed " + attempts
                        + " consecutive times on this head; awaiting a new head or "
                        + "operator action");
            }
            state.put("exit_code", result.exitCode());
            state.put("run_finished_at", Controller.now());
            save();
            return result.exitCode();
        }
        StateRecovery.ReconciliationOutcome outcome =
                StateRecovery.applyReconciliationDecision(state, result.output());
        if (!outcome.accepted()) {
            int attempts = (int) Engine.asLongOrDefault(state.get("reconciliation_attempts"), 0) + 1;
            state.put("phase", "BLOCKED_REQUIREMENTS");
            state.put("reconciliation_attempts", attempts);
            state.put("error", outcome.detail());
            state.put("decision_rejection", outcome.detail());
            if (ProgressGuard.reconcileCapReached(attempts)) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "reconciliation failed three consecutive times; manual intervention required");
                // Durably stick the head so the terminal stop survives the
                // PR-scoped FIXING_FEEDBACK re-entry (Engine.detectDeviation
                // checks stuck_head first and returns null for this head).
                markReconcileHeadStuck(currentPr, attempts,
                        "reconcile returned an invalid/unusable decision " + attempts
                        + " consecutive times on this head; awaiting a new head or "
                        + "operator action");
            }
            state.put("exit_code", result.exitCode());
            state.put("run_finished_at", Controller.now());
            save();
            log.info("RECONCILIATION REJECTED: " + outcome.detail());
            return 1;
        }
        Map<String, String> parsed = StateRecovery.parseResult(result.output());
        state.put("worker_result_status", parsed.getOrDefault("result_status", "UNKNOWN"));
        state.put("worker_result_evidence", parsed.getOrDefault("result_evidence", "BLOCKED"));
        state.put("result_status", "RECONCILIATION_ACCEPTED");
        state.put("result_evidence", "DECISION_COMPLETE");
        state.put("exit_code", result.exitCode());
        state.put("run_finished_at", Controller.now());

        // Deterministic anti-loop mechanism for a fully autonomous system:
        // reconciliation that repeatedly resumes work (RESUME_* / PROCESS_FEEDBACK)
        // on the SAME PR head without the head ever advancing means the resumed
        // phase keeps failing to fully validate and nothing is being committed.
        // Instead of blocking we DELIVER after a bounded number of consecutive
        // same-head resumes; a cumulative per-story counter also bounds the case
        // where each resume advances the head slightly (see ProgressGuard).
        String action = Engine.asString(state.get("decision_action"));
        boolean resumeAction = action.startsWith("RESUME") || action.equals("PROCESS_FEEDBACK");
        String reconcileHead = currentPr == null
                ? "" : Engine.asString(currentPr.get("headRefOid"));
        boolean forceDelivery = false;
        int maxResume = (int) Engine.asLongOrDefault(
                automation.get("max_resume_attempts"), 3);
        int loopMultiplier = Math.max(1, (int) Engine.asLongOrDefault(
                automation.get("resume_loop_multiplier"), 4));
        String currentStoryKey = current == null ? "" : String.valueOf(current);
        int resolvedNow = resolvedThreadCount();
        int resolvedAtReset = (int) Engine.asLongOrDefault(
                state.get("resume_total_resolved_baseline"), 0);
        ProgressGuard.ProgressState priorProgress = new ProgressGuard.ProgressState(
                Engine.asString(state.get("resume_guard_head")),
                (int) Engine.asLongOrDefault(state.get("resume_attempts"), 0),
                Engine.asString(state.get("resume_total_story")),
                (int) Engine.asLongOrDefault(state.get("resume_total_attempts"), 0),
                resolvedAtReset);
        ProgressGuard.GuardOutcome guard = ProgressGuard.observe(priorProgress,
                new ProgressGuard.GuardInput(resumeAction, reconcileHead, currentStoryKey,
                        resolvedNow, maxResume, loopMultiplier));
        ProgressGuard.ProgressState progress = guard.state();
        if (resumeAction) {
            boolean madeProgress = resolvedNow > resolvedAtReset;
            WorkflowStateCodec.writeProgress(state, progress);
            if (madeProgress && progress.cumulativeAttempts() == 1) {
                log.info("PROGRESS: story #{} resolved {} review thread(s) (was {}); "
                        + "resetting cumulative resume guard — the PR is advancing",
                        current, resolvedNow, resolvedAtReset);
            }
            forceDelivery = guard.forceDelivery();
            if (forceDelivery && progress.sameHeadAttempts() > maxResume) {
                log.info("AUTO-DELIVER: reconciliation resumed PR head {} {} times without "
                        + "advancing; delivering controller-owned changes and deferring "
                        + "validation to CI", shortSha(reconcileHead), progress.sameHeadAttempts());
            } else if (forceDelivery) {
                log.info("AUTO-DELIVER: story #{} accumulated {} resume decisions "
                        + "(cap {}); the head keeps advancing without converging, "
                        + "delivering controller-owned changes and deferring "
                        + "validation to CI", current, progress.cumulativeAttempts(),
                        guard.maxTotalResume());
            }
        } else {
            state.remove("resume_guard_head");
            state.remove("resume_attempts");
        }

        if (forceDelivery && currentPr != null) {
            boolean hasDeliverable;
            try {
                hasDeliverable = git.classifyWorktree(state) != GitOperations.WorktreeState.CLEAN;
            } catch (RuntimeException exc) {
                hasDeliverable = false;
            }
            if (!hasDeliverable) {
                // Rule 2: the active finding could not be advanced after the
                // resume cap and nothing is deliverable. Force-close it as
                // unresolvable instead of dead-ending on operator intervention.
                // Any committed work remains on the PR; a genuinely-present
                // issue is re-raised by the next Codex review on the head.
                forceCloseUnfixableAndAdvance(currentPr, reconcileHead,
                        "the fix could not be advanced on head `" + shortSha(reconcileHead)
                        + "` after repeated attempts, and no deliverable change was produced");
                state.put("exit_code", result.exitCode());
                state.put("run_finished_at", Controller.now());
                save();
                cleanup(renderedPrompt);
                log.info("AUTO-DELIVER RESOLVED BY FORCE-CLOSE: PR #{} advancing (next phase={})",
                        currentPr.get("number"), state.get("phase"));
                return 0;
            }
            state.put("result_status", "SUCCESS");
            state.put("result_evidence", "DEFERRED_TO_CI");
            state.put("delivery_mode", "CI_DEFERRED");
            state.put("phase", "WAITING_FOR_CI");
            state.remove("resume_guard_head");
            state.remove("resume_attempts");
            state.remove("resume_total_story");
            state.remove("resume_total_attempts");
            state.remove("resume_total_resolved_baseline");
            save();
            int deliveryResult = runDeliveryPipeline(git, current, currentPr,
                    result.output(), feedbackToResolve, true);
            cleanup(renderedPrompt);
            if (deliveryResult != Integer.MIN_VALUE) {
                return deliveryResult;
            }
            state.put("exit_code", result.exitCode());
            state.put("run_finished_at", Controller.now());
            save();
            log.info("AUTO-DELIVER COMPLETE: story #{} phase={}", current, state.get("phase"));
            return 0;
        }

        // Autonomous execution of a RESUME/PROCESS decision when the story has
        // NO PR yet. The supervisor (reconcile via Codex) is the on-behalf-of-
        // human actor: when it decides to resume a PR-dependent phase
        // (REVIEWING_CODE / FIXING_FEEDBACK) but no PR exists, the controller
        // must MAKE that decision productive instead of merely flipping the
        // phase — otherwise the target phase is a no-op and the workflow loops
        // forever (observed: story blocked pre-PR, RESUME_REVIEW re-entered 50+
        // times with pr=none). The fully autonomous rule is deliver-or-advance,
        // never wait:
        //   • deliverable work on the story branch -> commit + push + create the
        //     PR (the existing delivery pipeline), so review/CI can actually run;
        //   • nothing deliverable and the resume caps are exhausted -> advance to
        //     the next story so the EPIC keeps moving.
        // States that already work (a PR exists) are untouched: this block is
        // gated on currentPr == null and a PR-dependent resume target.
        if (currentPr == null && resumeAction
                && isPrDependentPhase(Engine.asString(state.get("phase")))) {
            int prLessResult = executePrLessResume(git, current, result,
                    renderedPrompt, feedbackToResolve, forceDelivery);
            if (prLessResult != Integer.MIN_VALUE) {
                return prLessResult;
            }
        }

        save();
        log.info("RECONCILIATION ACCEPTED: reason={} action={} detail={}",
                state.get("blocker_class"), state.get("decision_action"), outcome.detail());

        // When reconciliation EXPLICITLY decides PROCESS_FEEDBACK, clear the stale
        // last_feedback_result=BLOCKED so the FIXING_FEEDBACK worker actually runs
        // (otherwise the feedback guard re-routes to reconcile forever). Do NOT
        // reset feedback_attempts/guard_key — those accrue the cap the loop needs.
        if ("PROCESS_FEEDBACK".equals(Engine.asString(state.get("decision_action")))) {
            state.remove("last_feedback_result");
            state.remove("last_feedback_result_head");
            state.put("phase", "FIXING_FEEDBACK");
            state.put("phase_attempt", 0);
            state.remove("deviation_code");
            state.remove("deviation_detail");
            Map<String, Object> queuedFinding = ReviewFindingQueue.active(state);
            if (queuedFinding != null
                    && Engine.asLongOrDefault(queuedFinding.get("attempts"), 0) == 0) {
                state.remove("feedback_guard_head");
                state.remove("feedback_guard_key");
                state.remove("feedback_attempts");
            }
            save();
        }
        return 0;
    }

    /**
     * Phases that cannot make progress without an associated pull request.
     * A RESUME/PROCESS decision targeting one of these while {@code current_pr}
     * is null is a no-op that re-loops unless the controller first delivers a PR
     * (see {@link #executePrLessResume}).
     */
    private static boolean isPrDependentPhase(String phase) {
        return "REVIEWING_CODE".equals(phase) || "FIXING_FEEDBACK".equals(phase);
    }

    /**
     * Autonomously carry out a supervisor RESUME/PROCESS decision for a story
     * that has NO PR yet. Fully autonomous, never waits for a human:
     * <ul>
     *   <li>deliverable story work present ({@code RECOVERABLE_STORY_BRANCH}) ->
     *       commit + push + create the PR via the existing delivery pipeline,
     *       then let CI/review run against the new head;</li>
     *   <li>nothing deliverable ({@code CLEAN}) AND the resume caps are exhausted
     *       ({@code forceDelivery}) -> advance to the next story so the EPIC keeps
     *       moving instead of looping on an empty pre-PR state.</li>
     * </ul>
     * Returns an exit code to propagate, or {@link Integer#MIN_VALUE} to let the
     * caller fall through to the normal {@code RECONCILIATION ACCEPTED} path
     * (used when work exists but push/PR creation is disabled, or when the
     * worktree is clean but the caps are not yet exhausted — a bounded retry).
     */
    private int executePrLessResume(GitOperations git, Integer current,
                                    CodexRunner.CodexResult result, Path renderedPrompt,
                                    List<Map<String, Object>> feedbackToResolve,
                                    boolean forceDelivery) {
        GitOperations.WorktreeState worktreeState;
        try {
            worktreeState = git.classifyWorktree(state);
        } catch (RuntimeException exc) {
            // Cannot classify the tree — do not guess. Fall through to the
            // normal accepted path (a subsequent tick re-evaluates).
            log.warn("PRE-PR RESUME: unable to classify worktree for story #{}: {}",
                    current, exc.getMessage());
            return Integer.MIN_VALUE;
        }

        boolean hasDeliverable =
                worktreeState == GitOperations.WorktreeState.RECOVERABLE_STORY_BRANCH;

        // Case 1: deliver the existing story work as the first PR. Only when the
        // workflow permits real mutations; otherwise fall through so the plain
        // accepted path records the decision (dry-run / push disabled).
        if (hasDeliverable && !dryRun
                && Engine.asBool(workflow.get("allow_push"), false)
                && Engine.asBool(workflow.get("allow_pr_creation"), false)) {
            log.info("PRE-PR DELIVER: story #{} has implementation but no PR; committing, "
                    + "pushing, and creating the PR so review/CI can proceed (phase={})",
                    current, state.get("phase"));
            // runDeliveryPipeline creates the PR for a currentPr==null story when
            // the phase is a PR-ready phase (REVIEWING_CODE / FIXING_FEEDBACK
            // qualify) and then returns MIN_VALUE. Clear the resume guards: a real
            // head is about to exist, mirroring the post-commit reset in the
            // delivery pipeline so a fresh PR does not inherit pre-commit counters.
            state.remove("resume_guard_head");
            state.remove("resume_attempts");
            state.remove("resume_total_story");
            state.remove("resume_total_attempts");
            state.remove("resume_total_resolved_baseline");
            save();
            int deliveryResult = runDeliveryPipeline(git, current, null,
                    result.output(), feedbackToResolve);
            cleanup(renderedPrompt);
            if (deliveryResult != Integer.MIN_VALUE) {
                return deliveryResult;
            }
            state.put("exit_code", result.exitCode());
            state.put("run_finished_at", Controller.now());
            save();
            log.info("PRE-PR DELIVER COMPLETE: story #{} phase={}", current, state.get("phase"));
            return 0;
        }

        // Case 2: nothing to deliver AND the resume caps are exhausted. The
        // supervisor cannot advance a PR-dependent phase without a PR and there
        // is no work to turn into one. Autonomously advance to the next story
        // rather than looping on an empty pre-PR state (never waits for a human).
        if (!hasDeliverable && forceDelivery && current != null) {
            log.info("PRE-PR ADVANCE: story #{} has no deliverable work and the resume caps "
                    + "are exhausted with no PR; advancing to the next story to keep the EPIC "
                    + "moving", current);
            advanceStory(current);
            state.put("exit_code", result.exitCode());
            state.put("run_finished_at", Controller.now());
            save();
            cleanup(renderedPrompt);
            log.info("PRE-PR ADVANCE COMPLETE: story #{} completed-and-advanced; phase={}",
                    current, state.get("phase"));
            return 0;
        }

        // Otherwise (work exists but mutations disabled, or clean tree but caps
        // not yet reached) let the normal accepted path handle it — a bounded
        // retry, not a permanent stall.
        return Integer.MIN_VALUE;
    }

    /**
     * Build a per-call config whose {@code codex} section is resolved for the
     * given phase (base settings overlaid with {@code codex.phase_overrides
     * .<PHASE>}, if any). Returns the original config unchanged when there is no
     * applicable override, so the worker command is byte-identical to legacy.
     * Passing the resolved section through the existing {@code run(...)} entry
     * keeps the injectable worker seam (and its test fakes) intact.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> configForPhase(String phase) {
        Object codexObj = config.get("codex");
        if (!(codexObj instanceof Map<?, ?>)) {
            return config;
        }
        Map<String, Object> codexSection = (Map<String, Object>) codexObj;
        Map<String, Object> effective = CodexRunner.effectiveCodexConfig(codexSection, phase);
        if (effective.equals(codexSection)) {
            return config; // no override applied: identical to legacy
        }
        Map<String, Object> perCall = new LinkedHashMap<>(config);
        perCall.put("codex", effective);
        return perCall;
    }

    /**
     * Run the pre-commit specialist review gate against the local diff. Renders
     * and runs each relevant specialist prompt via the bounded Codex worker,
     * reusing the same budget/heartbeat as a normal phase. Best-effort: any
     * failure to render/run a specialist is logged and treated as a
     * non-blocking pass, because the post-PR Codex review is the final
     * authority and the autonomous loop must never dead-end on the gate.
     */
    private void runInternalReviewGate(GitOperations git, Integer current,
                                       Map<String, Object> context, Runnable heartbeat,
                                       Integer phaseBudget) {
        int maxFix = (int) Engine.asLongOrDefault(
                automation.get("internal_review_max_fix_attempts"), 2);
        List<String> changedPaths;
        try {
            changedPaths = git.changedSourcePaths();
        } catch (RuntimeException exc) {
            log.warn("INTERNAL REVIEW: unable to read changed paths ({}); skipping gate", exc.getMessage());
            return;
        }
        if (changedPaths.isEmpty()) {
            log.info("INTERNAL REVIEW: no local diff to review; skipping gate");
            return;
        }
        InternalReviewGate gate = new InternalReviewGate(
                InternalReviewGate.DEFAULT_PANEL, maxFix);
        Path rendered = root.resolve("state").resolve("prompt-" + epicNumber + ".md");
        InternalReviewGate.ReviewRunner runner = promptFile -> {
            Path promptPath = root.resolve(promptFile);
            if (!Files.exists(promptPath)) {
                // A missing specialist prompt must not block the loop: treat as a
                // pass so the gate degrades gracefully when a prompt is absent.
                log.warn("INTERNAL REVIEW: prompt {} not found; treating specialist as pass", promptFile);
                return "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: PROMPT_ABSENT\n";
            }
            try {
                Files.writeString(rendered, PromptRenderer.render(root, promptFile, context));
            } catch (java.io.IOException exc) {
                log.warn("INTERNAL REVIEW: unable to render {} ({}); treating as pass",
                        promptFile, exc.getMessage());
                return "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: RENDER_FAILED\n";
            }
            CodexRunner.CodexResult r = codexRunner.run(config, rendered, repo, dryRun,
                    heartbeat, phaseBudget);
            return r.output();
        };
        gate.run(changedPaths, runner);
    }

    private Integer prepareWorktreeForPhase(GitOperations git, Integer current,
                                            Map<String, Object> currentPr) {
        // A crash can occur after a story branch is created but before its name
        // is persisted. Recover only an unambiguous branch generated for the
        // selected story; arbitrary dirty branches must remain blocked.
        if (git.recoverMissingStoryBranch(state)) {
            save();
            log.warn("STATE RECOVERY: restored missing story branch {} for story #{}",
                    state.get("branch"), current);
        }

        // Before validating the worktree, actively check out the active story
        // branch so validation never runs on the default branch.
        String activeBranch = Engine.asString(state.get("branch"));
        if (!activeBranch.isEmpty()) {
            try {
                git.checkoutStoryBranch(activeBranch);
            } catch (RuntimeException exc) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Unable to check out story branch " + activeBranch
                        + ": " + exc.getMessage());
                save();
                log.error("RECONCILE: " + state.get("error"));
                return 1;
            }
        }

        // Classify the worktree without throwing so a dirty default branch or a
        // stray-changes situation is routed to bounded reconciliation.
        GitOperations.WorktreeState worktreeState = git.classifyWorktree(state);
        switch (worktreeState) {
            case CLEAN, RECOVERABLE_STORY_BRANCH -> {
                try {
                    git.ensureRecoverableWorktree(state);
                } catch (RuntimeException exc) {
                    state.put("phase", "BLOCKED_REQUIREMENTS");
                    state.put("error", "Unsafe working tree: " + exc.getMessage());
                    save();
                    log.error("RECONCILE: Unsafe working tree: " + exc.getMessage());
                    return 1;
                }
            }
            case DIRTY_DEFAULT_BRANCH -> {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Working tree has uncommitted changes on the default branch; "
                        + "reconcile before proceeding. Commit or relocate these changes onto a "
                        + "story branch, or remove them, then rerun.");
                save();
                log.error("RECONCILE: uncommitted changes on the default branch require reconciliation");
                return 1;
            }
            case DIRTY_UNKNOWN_BRANCH -> {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Working tree has changes on a branch that is not the active "
                        + "story branch; reconcile before proceeding.");
                save();
                log.error("RECONCILE: changes outside the active story branch require reconciliation");
                return 1;
            }
            default -> { }
        }

        if (!dryRun && !Engine.asBool(workflow.get("allow_push"), false)) {
            return blockedExternal("real Codex execution requires workflow.allow_push=true; "
                    + "use dry_run=true for simulation");
        }

        // Create a fresh, synchronized story branch when none exists yet.
        if (currentPr == null && Engine.asString(state.get("branch")).isEmpty()) {
            try {
                String baseBranch = Engine.asString(
                        Config.section(config, "repository").getOrDefault("base_branch", "main"));
                GitOperations.StoryBranch prepared = git.prepareStoryBranch(
                        baseBranch, current, Engine.asString(state.get("current_story_title")));
                state.put("branch", prepared.branch());
                state.put("base_branch", baseBranch);
                state.put("base_commit", prepared.baseHead());
                state.put("worktree_baseline", prepared.baseHead());
                save();
                log.info("BRANCH READY: " + prepared.branch() + " from " + prepared.baseHead());
            } catch (RuntimeException exc) {
                state.put("phase", "BLOCKED_REPOSITORY");
                state.put("error", "Unable to prepare synchronized story branch: " + exc.getMessage());
                save();
                log.error("BLOCKED: " + state.get("error"));
                return 1;
            }
        }
        return null;
    }

    private void advanceStory(int current) {
        List<Object> completed = new ArrayList<>();
        Object existing = state.get("completed_stories");
        if (existing instanceof List<?> list) {
            completed.addAll(list);
        }
        completed.add(current);
        state.put("completed_stories", completed);
        state.put("current_story", null);
        state.put("current_pr", null);
        state.put("current_pr_url", null);
        state.put("current_pr_head", null);
        state.put("pr_state", null);
        state.put("pr_review_decision", null);
        state.put("ci_status", new ArrayList<>());
        state.put("unresolved_feedback", new ArrayList<>());
        state.put("last_feedback_id", null);
        state.put("last_processed_review_id", null);
        state.put("last_review_trigger_verified_commit", null);
        state.put("review_gate_head", null);
        state.put("branch", null);
        state.put("base_commit", null);
        state.put("phase", "IDLE");
        // Clear ALL remaining story-scoped metadata so nothing from the
        // completed story leaks into the next one. A worker was observed
        // reasoning about (and having to dismiss) stale story-47 recovery
        // metadata while story-180 was active; that cross-story contamination
        // is eliminated here. Loop guards especially must reset so a fresh
        // story never inherits another story's resume/feedback counters. The
        // authoritative key set and this single reset live in WorkflowStateCodec
        // (Requirement 3): one mutation point, no duplicated reset lists.
        WorkflowStateCodec.clearStoryScoped(state);
    }

    private Integer phaseBudget(String executionPhase) {
        Object value = Config.section(config, "budgets").get(executionPhase.toLowerCase());
        return value == null ? null : Engine.asInt(value);
    }

    private Set<Integer> completedStories() {
        Set<Integer> completed = new LinkedHashSet<>();
        Object existing = state.get("completed_stories");
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                completed.add(Engine.asInt(item));
            }
        }
        return completed;
    }

    /**
     * Log a stable waiting/terminal state without flooding (Requirement 6).
     * A given {@code signature} logs at INFO the first time it is seen and at
     * DEBUG on every subsequent identical tick, so a run that sits in the same
     * waiting/terminal state does not emit an identical INFO line each cron
     * tick. The last-logged signature is stored durably (per-EPIC, reset with
     * story-scoped state) so dedupe survives restarts. Any change to the phase,
     * head, or counters produces a new signature and logs at INFO again.
     */
    private void logStableState(String signature, String message, Object... args) {
        String previous = Engine.asString(state.get("last_stable_log_signature"));
        if (isRepeatStableLog(previous, signature)) {
            log.debug(message, args);
        } else {
            log.info(message, args);
            state.put("last_stable_log_signature", signature);
        }
    }

    /**
     * Pure dedupe decision (Requirement 6): a non-empty signature identical to
     * the previously-logged one is a repeat (suppress to DEBUG); anything else
     * (first sight, changed phase/head/counters, or empty) logs at INFO.
     */
    static boolean isRepeatStableLog(String previousSignature, String currentSignature) {
        return currentSignature != null && !currentSignature.isEmpty()
                && currentSignature.equals(previousSignature);
    }

    private Map<String, Object> findRecordedPr(List<Map<String, Object>> prs, Map<String, Object> fallback) {
        Object number = state.get("current_pr");
        if (number != null) {
            for (Map<String, Object> p : prs) {
                if (p.get("number") != null && Engine.asInt(p.get("number")) == Engine.asInt(number)) {
                    return p;
                }
            }
        }
        return fallback;
    }

    private List<Map<String, Object>> openPrsForStory(List<Map<String, Object>> prs, int story) {
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> p : Engine.prForStory(prs, story)) {
            if ("OPEN".equals(Engine.asString(p.get("state")).toUpperCase())) {
                open.add(p);
            }
        }
        return open;
    }

    private int conflict(String message) {
        state.put("phase", "CONFLICT_DETECTED");
        state.put("error", message);
        save();
        log.error("CONFLICT_DETECTED: " + message);
        return 1;
    }

    private int blockedExternal(String message) {
        state.put("phase", "BLOCKED_EXTERNAL");
        state.put("error", message);
        save();
        log.error("BLOCKED: " + message);
        return 1;
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    /** Human-readable reason for a WAITING phase, for operator logs. */
    private static String waitReason(Object phase, Map<String, Object> pr) {
        String p = String.valueOf(phase);
        return switch (p) {
            case "WAITING_FOR_CI" -> "CI checks in progress";
            case "WAITING_FOR_CODEX_REVIEW" -> "awaiting Codex connector review of current head";
            case "WAITING_FOR_HUMAN_REVIEW" -> "awaiting human review";
            case "READY_TO_MERGE", "WAITING_FOR_MANUAL_MERGE" -> "approved; manual merge gate";
            default -> "waiting";
        };
    }

    private static String shortSha(Object sha) {
        String s = sha == null ? "" : String.valueOf(sha);
        return s.length() <= 12 ? s : s.substring(0, 12);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }
}
