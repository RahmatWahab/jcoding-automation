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
    private long phaseDeadlineNanos;
    private int callSequence;
    private final ActionPolicy actionPolicy;
    private final RuntimeVerifier verifier;

    WorkflowOrchestrator(Map<String, Object> config, Map<String, Object> state, StateStore store,
                         GitHub github, Path repo, Path root, Map<String, Object> epic, int epicNumber,
                         boolean dryRun, Controller.GitOperationsFactory gitFactory,
                         CodexRunner codexRunner, ReviewCoordinator review, WorkflowLock lock) {
        this(config, state, store, github, repo, root, epic, epicNumber, dryRun, gitFactory,
                codexRunner, review, lock, RuntimeVerifier.isolated(config));
    }

    WorkflowOrchestrator(Map<String, Object> config, Map<String, Object> state, StateStore store,
                         GitHub github, Path repo, Path root, Map<String, Object> epic, int epicNumber,
                         boolean dryRun, Controller.GitOperationsFactory gitFactory,
                         CodexRunner codexRunner, ReviewCoordinator review, WorkflowLock lock, RuntimeVerifier verifier) {
        this.config = config;
        this.verifier = verifier;
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
        this.actionPolicy = new ActionPolicy(config, dryRun);
        if (!dryRun && Files.isDirectory(repo)) {
            try {
                IsolatedCheckout.recordLifecycle(repo, "ACTIVE");
            } catch (RuntimeException exc) {
                // The GitHub worker path is only valid inside its persisted, owned checkout.
                if (state.get("worker_checkout_owner") instanceof Map<?, ?>) throw exc;
            }
        }
        github.cancellationCheck(actionPolicy::checkActive);
        github.intentIdentity(() -> GoalContract.requirementsHash(state));
        github.effectStore(store);
        github.mutationGuard(action -> {
            validateTransition();
            DeploymentPolicy.admit(config, state, state.get("current_story") == null ? null : Engine.asInt(state.get("current_story")));
            actionPolicy.require(action);
        });
    }

    private void validateTransition() {
        String phase = Engine.asString(state.get("phase"));
        String previous = Engine.asString(state.get("last_persisted_phase"));
        if (!WorkflowMachine.KNOWN_PHASES.contains(phase)) throw new IllegalStateException("Unknown workflow phase: " + phase);
        if (!previous.isBlank()) WorkflowMachine.requireLegalTransition(previous, phase);
    }

    private GitOperations git() {
        Set<String> owned = new java.util.HashSet<>();
        if (Engine.asString(state.get("worker_owned_story")).equals(Engine.asString(state.get("current_story")))
                && Engine.asString(state.get("worker_owned_branch")).equals(Engine.asString(state.get("branch")))) {
            for (Object path : Engine.asList(state.get("worker_owned_paths"))) owned.add(String.valueOf(path));
        }
        Map<String, Object> repository = Config.section(config, "repository");
        Map<String, Object> github = Config.section(config, "github");
        return gitFactory.create().effectStore(store).cancellationCheck(actionPolicy::checkActive)
                .githubCredentials(Engine.asString(github.getOrDefault("token_environment_variable", "GH_TOKEN")),
                        Engine.asString(github.getOrDefault("host", "github.com")),
                        Engine.asString(repository.get("owner")) + "/" + Engine.asString(repository.get("name")))
                .authorizedPaths(owned)
                .protectedPaths(Engine.asList(Config.section(config, "verification")
                        .getOrDefault("protected_paths", List.of(".github/**", "config/**", "prompts/**"))))
                .intentIdentity(() -> GoalContract.requirementsHash(state)).mutationGuard(() -> {
            validateTransition();
            DeploymentPolicy.admit(config, state, state.get("current_story") == null ? null : Engine.asInt(state.get("current_story")));
            actionPolicy.require("git");
        });
    }

    private void save() {
        String newPhase = Engine.asString(state.get("phase"));
        String lastPhase = Engine.asString(state.get("last_persisted_phase"));
        if (!WorkflowMachine.KNOWN_PHASES.contains(newPhase)) throw new IllegalStateException("Unknown workflow phase: " + newPhase);
        if (!lastPhase.isEmpty()) WorkflowMachine.requireLegalTransition(lastPhase, newPhase);
        state.put("last_persisted_phase", newPhase);
        lock.refresh();
        store.save(state);
    }

    int reconcileAndExecute(List<Map<String, Object>> prs, String forcedPrompt) {
        try {
            return executeTick(prs, forcedPrompt);
        } finally {
            // A completed decision can select IMPLEMENTING for the next tick;
            // that does not mean a worker is still running in this process.
            lock.refresh();
            state.put("worker_status", "IDLE");
            state.put("run_finished_at", Controller.now());
            save();
        }
    }

    private int executeTick(List<Map<String, Object>> prs, String forcedPrompt) {
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
        if (!dryRun) {
            try { DeploymentPolicy.admit(config, state, current); }
            catch (IllegalStateException exc) {
                state.put("phase", "BLOCKED_CONFIGURATION");
                state.put("error", exc.getMessage());
                save();
                return 1;
            }
        }
        // A dry run with no PR remains a selection-only observation. With an
        // existing PR, continue through read-only reconciliation so durable
        // blockers and review/CI gates are still classified accurately.
        if (dryRun && currentPr == null) {
            state.put("next_action", "DRY_RUN_OBSERVATION_ONLY");
            save();
            return 0;
        }

        // Retry deterministic preflight recovery before PR feedback, held-story gates,
        // or another generic escalation can overwrite the original diagnosis.
        if (state.get("preflight_failure") instanceof Map<?, ?>) {
            return runWorkerPhase(current, currentPr, List.of(), "prompts/reconcile.md");
        }

        // A delivery failure takes priority over PR feedback/wait classification.
        // Otherwise the next tick erases the blocker and repeats the same work.
        if (DeliveryRecovery.failure(state) != null) {
            return recoverDelivery(current, currentPr);
        }
        if (state.get("held_failure") instanceof Map<?, ?> held) {
            String head = currentPr == null ? "" : Engine.asString(currentPr.get("headRefOid"));
            String evidence = git().recoveryFingerprint();
            if (head.equals(Engine.asString(held.get("head")))
                    && evidence.equals(Engine.asString(held.get("evidence")))
                    && Engine.asLongOrDefault(automation.get("recovery_epoch"), 0) <= Engine.asLongOrDefault(held.get("recovery_epoch"), 0)) {
                return 1;
            }
            state.remove("held_failure");
            state.put("recovery_next_prompt", "prompts/reconcile.md");
        }
        String selectedRecovery = Engine.asString(state.remove("recovery_next_prompt"));
        if (!selectedRecovery.isEmpty()) {
            save();
            return runWorkerPhase(current, currentPr, ReviewFindingQueue.activeBatch(state), selectedRecovery);
        }

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
        if (dryRun) {
            state.put("next_action", "DRY_RUN_OBSERVATION_ONLY");
            save();
            return 0;
        }

        // Generic deviation supervisor (Requirement 2): detect any deviation/
        // stall and either recover deterministically (returning an exit code) or
        // route to the reasoning reconcile prompt.
        DeviationResult deviationResult = runDeviationSupervisor(current, currentPr, forcedPrompt);
        if (deviationResult.exitCode() != null) {
            return deviationResult.exitCode();
        }
        forcedPrompt = deviationResult.forcedPrompt();
        String strategy = Engine.asString(state.get("recovery_strategy"));
        if (feedbackToResolve.isEmpty() && (forcedPrompt == null || forcedPrompt.isBlank())
                && ("TEST_ALTERNATIVE_HYPOTHESIS".equals(strategy) || "REPLAN_OR_ESCALATE".equals(strategy))) {
            forcedPrompt = "REPLAN_OR_ESCALATE".equals(strategy) && currentPr == null
                    && Engine.asBool(automation.get("enable_planning"), false)
                    ? "prompts/plan.md" : "prompts/reconcile.md";
            state.put("recovery_routing_reason", strategy);
            state.remove("recovery_strategy"); // each adaptive decision is consumed once
            save();
        }
        return runWorkerPhase(current, currentPr, feedbackToResolve, forcedPrompt);
    }

    private int recoverDelivery(Integer current, Map<String, Object> currentPr) {
        if (dryRun || !Engine.asBool(workflow.get("allow_push"), false)) {
            return 1;
        }
        if (currentPr != null) {
            try {
                currentPr = github.pr(Engine.asInt(currentPr.get("number")));
            } catch (GitHubError exc) {
                return blockedExternal("Unable to inspect PR during delivery recovery: " + exc.getMessage());
            }
            if (!"OPEN".equals(currentPr.get("state"))) {
                state.remove(DeliveryRecovery.KEY);
                save();
                return 0;
            }
        }
        GitOperations git = git();
        Map<String, Object> failure = DeliveryRecovery.failure(state);
        String evidence = git.recoveryFingerprint() + ":"
                + (currentPr == null ? "" : Engine.asString(currentPr.get("headRefOid")));
        // A controller update can resolve a path false positive without changing
        // the story worktree. Re-check the actual guard as part of the evidence.
        if ("PROTECTED_PATH".equals(failure.get("code"))) {
            try {
                git.validateStagingPaths();
                evidence += ":policy-clear";
            } catch (RuntimeException exc) {
                evidence += ":policy-blocked";
            }
        }
        int cap = (int) Engine.asLongOrDefault(automation.get("max_delivery_recovery_attempts"), 4);
        String prompt = DeliveryRecovery.nextPrompt(failure, evidence, cap);
        if (prompt == null) {
            state.put("phase", "PROTECTED_PATH".equals(failure.get("code"))
                    ? "BLOCKED_REPOSITORY" : "BLOCKED_EXTERNAL");
            state.put("error", failure.get("detail"));
            state.put("next_action", "WAIT_FOR_DELIVERY_EVIDENCE");
            save();
            logStableState("DELIVERY_HELD|" + current + "|" + evidence,
                    "DELIVERY HELD: story #{} recovery requires changed evidence; {}",
                    current, failure.get("detail"));
            return 1;
        }
        state.put("deviation_code", "DELIVERY_BLOCKED");
        state.put("deviation_detail", failure.get("detail"));
        save();
        log.info("DELIVERY RECOVERY: story #{} attempt={} prompt={}", current, failure.get("attempts"), prompt);
        int result = runWorkerPhase(current, currentPr, ReviewFindingQueue.activeBatch(state), prompt);
        // A blocked/failed repair must return to diagnosis, not run the same
        // implementation prompt on every tick. The budget survives both stages.
        failure = DeliveryRecovery.failure(state);
        if (failure != null && prompt.endsWith("delivery-repair.md")) {
            failure.put("stage", "DIAGNOSE");
            save();
        }
        return result;
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
            currentPr.put("requiredChecks", workflow.getOrDefault("required_checks", List.of()));
            currentPr.put("allowNoChecks", workflow.getOrDefault("allow_no_checks", false));
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
            if (!dryRun && Engine.asBool(workflow.get("allow_thread_resolution"), false)
                    && "OPEN".equals(Engine.asString(currentPr.get("state")).toUpperCase())) {
                for (Object threadObj : Engine.asList(currentPr.get("reviewThreads"))) {
                    if (!(threadObj instanceof Map<?, ?> t)) {
                        continue;
                    }
                    Map<String, Object> thread = castMap(t);
                    String head = Engine.asString(currentPr.get("headRefOid"));
                    if (!Engine.asBool(thread.get("isResolved"), true)
                            && ReviewCoordinator.fixReplyHeads(thread).contains(head)
                            && Engine.asList(state.get("thread_updates")).stream().anyMatch(update ->
                                update instanceof Map<?, ?> u && "REPLIED".equals(u.get("action"))
                                && head.equals(u.get("head")) && thread.get("id").equals(u.get("thread_id")))) {
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
            // Findings remain actionable until a verified fix is delivered.
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
                if (!dryRun) {
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

            boolean reviewTriggerMissing = false;
            if (ReviewCoordinator.reviewGateOpen(currentPr)) {
                // Only trust the wait when the @codex review trigger genuinely
                // exists on the live PR. A false WAITING_FOR_CODEX_REVIEW (no
                // trigger present — never posted, stripped, or lost on refresh)
                // can never resolve on its own; instead of returning to wait
                // forever, fall through to the deviation supervisor so the
                // WorkerSupervisor (reconcile) re-checks PR status and recovers.
                // Fully autonomous: no human, never a dead-end wait.
                if (Engine.hasCodexReviewTrigger(currentPr)) {
                    state.put("phase", "WAITING_FOR_CODEX_REVIEW");
                    state.put("review_gate_head", currentPr.get("headRefOid"));
                    state.put("next_action", "WAIT_FOR_CODEX_REVIEW");
                    save();
                    logStableState("WAIT_CODEX|" + current + "|" + shortSha(currentPr.get("headRefOid")),
                            "WAITING: story #{}, phase=WAITING_FOR_CODEX_REVIEW", current);
                    return PrScopedResult.exit(observeWait(current, currentPr, "WAITING_FOR_CODEX_REVIEW"));
                }
                // No trigger present: mark the phase and fall through to the
                // generic deviation supervisor (STALLED_REVIEW_TRIGGER) below,
                // which routes to reconcile for autonomous recovery.
                log.info("REVIEW TRIGGER MISSING: story #{} PR #{} head {} is in "
                        + "WAITING_FOR_CODEX_REVIEW but no @codex review trigger is present; "
                        + "escalating to the supervisor for recovery",
                        current, currentPr.get("number"), shortSha(currentPr.get("headRefOid")));
                state.put("phase", "WAITING_FOR_CODEX_REVIEW");
                save();
                reviewTriggerMissing = true;
            } else {
                // The gate has closed for this head; record the connector review.
                if (Engine.asString(state.get("review_gate_head"))
                        .equals(Engine.asString(currentPr.get("headRefOid")))
                        && !Engine.asString(currentPr.get("headRefOid")).isEmpty()) {
                    state.put("last_connector_review_commit", currentPr.get("headRefOid"));
                    state.remove("review_gate_head");
                }
            }

            String phase = Engine.asString(state.get("phase"));
            // Skip the wait gate for a false WAITING_FOR_CODEX_REVIEW (no trigger):
            // the wait gate would return 0 and end the run, waiting forever. Let it
            // fall through to the deviation supervisor for autonomous recovery.
            if (!reviewTriggerMissing
                    && Set.of("WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW", "WAITING_FOR_HUMAN_REVIEW",
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
            if (unrecoverable && ParkPolicy.mayAdvance(workflow, state) && current != null) {
                // Park-and-advance (Requirement 5): instead of holding this story
                // forever, record it as parked on the current head, notify once,
                // reset its story-scoped state, and go IDLE so the next tick
                // selects a different eligible story. It auto-unparks when the
                // head advances (handled at PR reconciliation).
                String parkHead = Engine.asString(state.get("stuck_head"));
                boolean newlyParked = ParkPolicy.park(state, current, parkHead,
                        "UNRECOVERABLE", Controller.now());
                ParkPolicy.parkWithCheckpoint(state, current, parkHead, "UNRECOVERABLE", Controller.now());
                try {
                    preserveBeforeParking(current);
                } catch (RuntimeException exc) {
                    state.put("phase", "BLOCKED_REPOSITORY");
                    state.put("error", "Cannot advance safely after parking: " + exc.getMessage());
                    save();
                    return DeviationResult.exit(diagnosePreflight(git(), current, currentPr));
                }
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
                    GitOperations probe = git();
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

            // A commit alone does not prove validation. A fresh verification
            // worker must authorize delivery of committed work without a PR.
            if ("COMMITTED_NO_PR".equals(deviation.code())) {
                state.put("recovery_action", "Verify the preserved implementation and deliver only with complete evidence");
                return DeviationResult.proceed("prompts/verify.md");
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
        // Recovery can dispatch before PR-scoped reconciliation. Never send a
        // list-only snapshot (missing threads or head) to the supervisor/worker.
        if (currentPr != null) {
            try {
                currentPr = github.pr(Engine.asInt(currentPr.get("number")));
                if (currentPr == null || Engine.asString(currentPr.get("headRefOid")).isBlank()) {
                    return blockedExternal("PR detail is missing its authoritative head");
                }
                state.put("current_pr_head", currentPr.get("headRefOid"));
                state.put("branch", currentPr.get("headRefName"));
                if (Boolean.FALSE.equals(currentPr.get("reviewThreadsAvailable"))) {
                    return blockedExternal("Authoritative review threads unavailable before worker");
                }
                ReviewFindingQueue.synchronize(state, Engine.actionableFeedback(currentPr),
                        (int) Engine.asLongOrDefault(automation.get("max_feedback_batch_size"), 3));
                feedbackToResolve = ReviewFindingQueue.activeBatch(state);
            } catch (GitHubError exc) {
                return blockedExternal("Unable to refresh PR before worker: " + exc.getMessage());
            }
        }
        // Phase dispatch: render prompt and run the bounded worker.
        String phasePrompt = forcedPrompt != null ? forcedPrompt
                : Controller.phasePromptFor(Engine.asString(state.get("phase")));
        Path prompt = root.resolve(phasePrompt).toAbsolutePath().normalize();
        Path promptRoot = root.resolve("prompts").toAbsolutePath().normalize();
        if (!prompt.startsWith(promptRoot) || Files.isSymbolicLink(prompt)
                || !Files.isRegularFile(prompt)) {
            state.put("phase", "BLOCKED_CONFIGURATION");
            state.put("error", "Prompt not found: " + prompt);
            save();
            log.error(String.valueOf(state.get("error")));
            return diagnosePreflight(git(), current, currentPr);
        }

        GitOperations git = git();

        // Reconcile the worktree (recover/checkout the story branch, classify the
        // tree, enforce the allow_push gate, and cut a fresh branch when needed)
        // before dispatching the worker. Returns a non-null exit code when the
        // run must end here (blocked/reconcile), or null to proceed.
        Integer prep;
        try {
            prep = prepareWorktreeForPhase(git, current, currentPr);
        } catch (RuntimeException exc) {
            state.put("phase", "BLOCKED_REPOSITORY");
            state.put("error", "Worktree preparation failed: " + exc.getMessage());
            save();
            prep = 1;
        }
        if (prep != null) {
            return diagnosePreflight(git, current, currentPr);
        }
        state.remove("preflight_failure");
        if (Engine.asBool(automation.get("enable_planning"), false)
                && !TaskPlan.bound(repo, state, config) && !phasePrompt.endsWith("plan.md")) {
            state.put("phase", "PLANNING");
            save();
            return runWorkerPhase(current, currentPr, feedbackToResolve, "prompts/plan.md");
        }

        Map<String, Object> activeTask = null;
        if (Engine.asBool(automation.get("enable_planning"), false) && !phasePrompt.endsWith("plan.md")) {
            TaskLedger.bind(state, TaskPlan.validate(repo, state, config));
            if (TaskLedger.invalidateStale(state, git.recoveryFingerprint(),
                    GoalContract.hashData(Config.section(config, "verification")))) save();
            if (!phasePrompt.endsWith("reconcile.md")) {
                activeTask = TaskLedger.next(state);
                if (activeTask != null) {
                    if ("AWAITING_VERIFICATION".equals(activeTask.get("status"))) {
                        return verifyActiveTask(git, activeTask);
                    }
                    try { TaskLedger.start(activeTask, (int) Engine.asLongOrDefault(automation.get("max_task_attempts"), 3)); }
                    catch (ExecutionBudget.Exhausted exc) {
                        if (TaskLedger.requestReplan(state, activeTask,
                                (int) Engine.asLongOrDefault(automation.get("max_plan_revisions"), 2))) {
                            state.put("phase", "PLANNING");
                            save();
                            return runWorkerPhase(current, currentPr, feedbackToResolve, "prompts/plan.md");
                        }
                        return holdOrPark(current, currentPr, exc.getMessage(), "PROCESS");
                    }
                    phasePrompt = "prompts/implement.md";
                    prompt = root.resolve(phasePrompt);
                    save();
                } else if (!TaskLedger.complete(state)) {
                    return holdOrPark(current, currentPr, "Task graph has no runnable task", "PROCESS");
                }
            }
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("epic", epic);
        context.put("current_story", current);
        context.put("story_issue", state.get("current_story_issue"));
        context.put("goal_contract", state.get("goal_contract"));
        context.put("active_task", activeTask);
        context.put("current_pr", currentPr);
        context.put("state", state);
        context.put("recovery_observations_untrusted", RecoveryMemory.relevant(state));
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
        int totalSeconds = phaseBudget != null ? phaseBudget
                : (int) Engine.asLongOrDefault(Config.section(config, "codex").get("execution_timeout_seconds"), 3600);
        phaseDeadlineNanos = System.nanoTime() + totalSeconds * 1_000_000_000L;
        Runnable heartbeat = () -> {
            actionPolicy.checkActive();
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
        CodexRunner.CodexResult result;
        try {
            result = runMeteredWorker(executionPhase, renderedPrompt, heartbeat);
        } catch (ActionPolicy.Cancelled cancelled) {
            state.put("worker_status", "CANCELLED");
            state.put("last_worker_outcome", "CANCELLED");
            state.put("next_action", "RESUME_PRESERVED_WORK_AFTER_ENABLE");
            state.put("error", cancelled.getMessage());
            if (state.get("goal_contract") instanceof Map<?, ?> raw) {
                Map<String, Object> goal = new LinkedHashMap<>(castMap(raw));
                goal.put("status", actionPolicy.explicitlyCancelled() ? "CANCELLED" : "INTERRUPTED");
                state.put("goal_contract", goal);
            }
            store.finishJob(jobId, "CANCELLED", 130, cancelled.getMessage());
            save();
            cleanup(renderedPrompt);
            return 130;
        } catch (ExecutionBudget.Exhausted exhausted) {
            store.finishJob(jobId, "BLOCKED", 1, exhausted.getMessage());
            cleanup(renderedPrompt);
            return holdOrPark(current, currentPr, exhausted.getMessage(), "PROCESS");
        }
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

        if ("PLANNING".equals(executionPhase)) {
            Map<String, String> fields = StateRecovery.parseResult(result.output());
            boolean accepted = result.exitCode() == 0 && "SUCCESS".equals(fields.get("result_status"))
                    && "COMPLETE".equals(fields.get("result_evidence"))
                    && "NONE".equals(fields.get("result_blocker"))
                    && "IMPLEMENTING".equals(fields.get("result_next_phase"));
            if (accepted) {
                try {
                    TaskPlan.Validated plan = TaskPlan.validate(repo, state, config);
                    if (state.get("replan_request") instanceof Map<?, ?> request
                            && plan.hash().equals(request.get("previous_plan_hash"))) {
                        throw new IllegalArgumentException("Replan must change the task strategy");
                    }
                    state.put("plan_binding", Map.of("artifact_hash", plan.hash(),
                            "requirements_hash", GoalContract.requirementsHash(state), "tasks", plan.tasks(),
                            "accepted_at", Controller.now()));
                    TaskLedger.bind(state, plan);
                    state.remove("replan_request");
                    state.remove("requirements_changed");
                } catch (IllegalArgumentException exc) {
                    accepted = false;
                    state.put("error", exc.getMessage());
                }
            }
            state.put("phase", accepted ? "IMPLEMENTING" : "BLOCKED_REQUIREMENTS");
            state.put("result_status", accepted ? "PLAN_ACCEPTED" : "BLOCKED");
            state.put("result_evidence", accepted ? "PLAN_VALIDATED" : "BLOCKED");
            if (!accepted) state.putIfAbsent("error", "Planning did not produce a valid plan and result");
            store.finishJob(jobId, accepted ? "SUCCEEDED" : "BLOCKED", result.exitCode(), "Planning outcome");
            save();
            cleanup(renderedPrompt);
            return accepted ? 0 : 1;
        }

        state.putAll(Engine.applyCodexResult(state, result.exitCode(), result.output(),
                (int) Engine.asLongOrDefault(automation.get("quota_retry_seconds"), 1800)));

        if (activeTask != null) {
            boolean taskSucceeded = result.exitCode() == 0 && Engine.deliveryAccepted(state);
            TaskLedger.result(activeTask, taskSucceeded);
            if (!taskSucceeded && TaskLedger.observeFailure(activeTask, taskFailureSignature(activeTask))) {
                activeTask.put("status", "BLOCKED");
                activeTask.put("stalled_at", Controller.now());
                state.put("task_stall", Map.of("task", activeTask.get("id"),
                        "attempts", activeTask.get("attempts"), "failure_signature", activeTask.get("failure_signature")));
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("next_action", "ESCALATE_UNMET_CRITERIA");
                save();
                store.finishJob(jobId, "BLOCKED", result.exitCode(), "Repeated task failure without criterion progress");
                cleanup(renderedPrompt);
                return 1;
            }
            save();
            if (taskSucceeded) {
                store.finishJob(jobId, "SUCCEEDED", 0, "Task worker finished; executable verification required");
                cleanup(renderedPrompt);
                return verifyActiveTask(git, activeTask);
            }
        }

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

        if (result.exitCode() != 0 && state.get("last_tool_result") instanceof Map<?, ?> toolResult
                && "PROTECTED_PATH".equals(toolResult.get("failure_class"))) {
            state.put("phase", "BLOCKED_REPOSITORY");
            state.put("blocker_class", "PROTECTED_PATH");
            state.put("next_action", "WAIT_FOR_PATH_POLICY_REPAIR");
            state.put("error", codexRunner.redactSensitive(Engine.asString(toolResult.get("detail"))));
            save();
            cleanup(renderedPrompt);
            return 1;
        }

        // A structured BLOCKED result is a worker/business verdict (for example,
        // an unresolved review finding), not a failed delivery operation. Sending
        // it through delivery recovery skips the feedback retry and escalation
        // guards, so only process failures enter delivery recovery here.
        if (!prompt.getFileName().toString().equals("reconcile.md")
                && result.exitCode() != 0) {
            DeliveryRecovery.recordWorkerFailure(state, executionPhase, result.exitCode(),
                    codexRunner.redactSensitive(tail(result.output(), 12000)),
                    codexRunner.redactSensitive(tail(result.diagnostics(), 8000)));
            save();
        }

        // Reconciliation phase applies a bounded decision, not completion.
        if (prompt.getFileName().toString().equals("reconcile.md")) {
            if (DeliveryRecovery.failure(state) != null) {
                boolean accepted = StateRecovery.applyReconciliationDecision(
                        state, result.exitCode(), result.output()).accepted();
                DeliveryRecovery.applyDecision(state, accepted, git.recoveryFingerprint());
                state.put("result_status", accepted ? "RECONCILIATION_ACCEPTED" : "BLOCKED");
                state.put("result_evidence", accepted ? "DECISION_COMPLETE" : "BLOCKED");
                state.put("run_finished_at", Controller.now());
                save();
                cleanup(renderedPrompt);
                return accepted ? 0 : 1;
            }
            return handleReconcileDecision(git, current, currentPr, result, renderedPrompt,
                    feedbackToResolve);
        }

        if (result.exitCode() != 0) {
            state.put("raw_error", codexRunner.redactSensitive(tail(result.output(), 2000)));
        }

        // A zero exit only means the CLI completed; the structured worker
        // result controls whether mutations are allowed. Non-SUCCESS fails closed.
        if (result.exitCode() == 0 && Engine.deliveryAccepted(state)) {
            if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)) {
                try {
                    git.validateStagingPaths();
                } catch (RuntimeException exc) {
                    recordDeliveryFailure(exc, "STAGING_PREFLIGHT");
                    cleanup(renderedPrompt);
                    return 1;
                }
            }
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
                        || "FIXING_FEEDBACK".equals(executionPhase)
                        || "VERIFYING".equals(executionPhase) && TaskLedger.complete(state))) {
                if (!runInternalReviewGate(git, current, context, heartbeat)) {
                    cleanup(renderedPrompt);
                    DeliveryRecovery.recordWorkerFailure(state, executionPhase, 1,
                            "Internal review did not pass. Inspect internal_review_findings and last_worker_evidence.",
                            String.valueOf(state.get("internal_review_findings")));
                    state.put("blocker_class", "VALIDATION");
                    state.put("error", "Internal review could not verify or clear all findings");
                    save();
                    log.warn("INTERNAL REVIEW RECOVERY: story #{} PR #{} retained for supervisor diagnosis",
                            current, state.get("current_pr"));
                    return 1;
                }
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

    private int verifyActiveTask(GitOperations git, Map<String, Object> task) {
        state.put("phase", "VERIFYING");
        save();
        boolean verified = verifier.verifyTask(repo, git, state, config, task,
                () -> { actionPolicy.checkActive(); lock.refresh(); }, this::save);
        state.remove("validation_checkpoint"); // Task evidence alone cannot authorize delivery.
        boolean stalled = !verified && TaskLedger.observeFailure(task, taskFailureSignature(task));
        if (!verified) task.put("status", "BLOCKED");
        if (stalled) {
            task.put("stalled_at", Controller.now());
            state.put("task_stall", Map.of("task", task.get("id"), "attempts", task.get("attempts"),
                    "failure_signature", task.get("failure_signature")));
            state.put("phase", "BLOCKED_REQUIREMENTS");
            state.put("next_action", "ESCALATE_UNMET_CRITERIA");
            save();
            return 1;
        }
        state.put("phase", verified && TaskLedger.complete(state) ? "VERIFYING" : "IMPLEMENTING");
        state.put("recovery_next_prompt", verified && TaskLedger.complete(state) ? "prompts/verify.md" : "prompts/implement.md");
        state.put("next_action", verified ? "CONTINUE_VERIFIED_TASK_PLAN" : "REPAIR_TASK_FROM_CHECK_EVIDENCE");
        save();
        return verified ? 0 : 1;
    }

    private String taskFailureSignature(Map<String, Object> task) {
        Map<String, Object> tool = state.get("last_tool_result") instanceof Map<?, ?> raw ? castMap(raw) : Map.of();
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Object raw : Engine.asList(state.get("verification_evidence"))) {
            if (raw instanceof Map<?, ?> row) checks.add(Map.of(
                    "id", Engine.asString(row.get("id")),
                    "exit_code", row.containsKey("exit_code") ? row.get("exit_code") : -1,
                    "output", Engine.asString(row.get("output"))));
        }
        return GoalContract.hashData(Map.of("task", Engine.asString(task.get("id")),
                "criteria", Engine.asList(task.get("criteria")),
                "verification_failure", Engine.asString(state.get("verification_failure")),
                "verification_evidence", checks,
                "worker_status", Engine.asString(state.get("result_status")),
                "tool_failure", Engine.asString(tool.get("failure_class")),
                "tool_detail", Engine.asString(tool.get("detail"))));
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
        if (current != null && state.get("current_story_issue") instanceof Map<?, ?> observed) {
            Map<String, Object> live;
            try { live = castMap(github.issue(current)); }
            catch (GitHubError exc) { return blockedExternal("Cannot refresh story before delivery: " + exc.getMessage()); }
            if (!java.util.Objects.equals(live.get("title"), observed.get("title"))
                    || !java.util.Objects.equals(live.get("body"), observed.get("body"))) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Story requirements changed during worker execution; recapture intent before delivery");
                state.remove("validation_checkpoint");
                save();
                return 1;
            }
        }
        if (Engine.asBool(automation.get("enable_planning"), false)
                && TaskLedger.invalidateStale(state, git.recoveryFingerprint(),
                        GoalContract.hashData(Config.section(config, "verification")))) save();
        if (Engine.asBool(automation.get("enable_planning"), false) && !TaskLedger.complete(state)) {
            return holdOrPark(current, currentPr, "Delivery requires every planned task to be verified", "VALIDATION");
        }
        if (!Engine.deliveryAccepted(state)) {
            return holdOrPark(current, currentPr, "Delivery rejected: validation evidence is incomplete", "VALIDATION");
        }
        if (!verifier.verify(repo, git, state, config,
                () -> { actionPolicy.checkActive(); lock.refresh(); }, this::save)) {
            DeliveryRecovery.recordWorkerFailure(state, "VERIFYING", 1,
                    Engine.asString(state.get("verification_failure")), "Controller executable verification failed");
            state.put("phase", "BLOCKED_REQUIREMENTS");
            state.put("blocker_class", "VALIDATION");
            save();
            return 1;
        }
        if ("EXECUTABLE".equals(state.get("verification_mode"))) {
            RecoveryMemory.observe(state, "VERIFYING", "CHECKS_PASSED", git.recoveryFingerprint(), true);
        }
        return deliverVerifiedWork(git, current, currentPr, output, feedbackToResolve);
    }

    @SuppressWarnings("unchecked")
    private int deliverVerifiedWork(GitOperations git, Integer current, Map<String, Object> currentPr,
                                    String output, List<Map<String, Object>> feedbackToResolve) {
        boolean deliveryVerified = false;
        String deliveredCommit = null;
        Set<String> declaredFixedIds = ReviewCoordinator.fixedThreadIds(output);
        Set<String> declaredSupersededIds = ReviewCoordinator.supersededThreadIds(output);
        declaredFixedIds.addAll(declaredSupersededIds);
        String deliveryOperation = "COMMIT_PUSH";
        if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)) {
            try {
                if (state.get("validation_checkpoint") instanceof Map<?, ?> proof
                        && !git.recoveryFingerprint().equals(proof.get("snapshot"))) {
                    throw new IllegalStateException("Source changed after verification");
                }
                String branch = git.gitBranch();
                String committed = git.commitAndPush(branch, current);
                if (committed == null && DeliveryRecovery.failure(state) != null) {
                    // A previous commit may have succeeded while its push failed.
                    // A clean tree is not proof that origin contains local HEAD.
                    git.pushBranch(branch);
                }
                deliveredCommit = committed;
                if (committed != null) {
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
                    deliveryOperation = "VERIFY_HEAD";
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
                    if (!deliveredCommit.equals(state.get("last_verified_delivery"))) {
                        state.put("phase_attempt", 0);
                        state.put("last_verified_delivery", deliveredCommit);
                    }
                    recordDeliveredValidation(deliveredCommit);
                    deliveryOperation = "REVIEW_TRIGGER";
                    review.ensureCodexReviewTrigger(github, currentPr, state, workflow, dryRun, this::save);
                }
            } catch (RuntimeException exc) {
                recordDeliveryFailure(exc, deliveryOperation);
                return 1;
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
        // which holds the work for new evidence rather than looping or claiming
        // an unverified fix.
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
        boolean mayResolveThreads = deliveryVerified && deliveredCommit != null && currentPr != null
                && !mapping.canonical().isEmpty();
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
                recordDeliveryFailure(exc, "RESOLVE_FEEDBACK");
                return 1;
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
                    recordDeliveredValidation(Engine.asString(created.get("headRefOid")));
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
                    recordDeliveryFailure(exc, "CREATE_PR");
                    return 1;
                }
            }
        }
        if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)
                && !"BLOCKED_EXTERNAL".equals(state.get("phase"))) {
            state.remove(DeliveryRecovery.KEY);
            state.remove("error");
            state.put("blocker_class", "NONE");
            if ("DELIVERY_BLOCKED".equals(state.get("deviation_code"))) {
                state.remove("deviation_code");
                state.remove("deviation_detail");
            }
            if (Set.of("RECOVER_DELIVERY", "WAIT_FOR_DELIVERY_EVIDENCE")
                    .contains(Engine.asString(state.get("next_action")))) {
                state.remove("next_action");
            }
            save();
        }
        return Integer.MIN_VALUE;
    }

    @SuppressWarnings("unchecked")
    private void recordDeliveredValidation(String head) {
        if (head != null && !head.isBlank() && state.get("validation_checkpoint") instanceof Map<?, ?> checkpoint) {
            Map<String, Object> proof = new LinkedHashMap<>((Map<String, Object>) checkpoint);
            proof.put("delivered_head", head);
            state.put("validation_checkpoint", proof);
            save();
        }
    }

    private boolean mergeProofMatches(String head) {
        return state.get("validation_checkpoint") instanceof Map<?, ?> proof
                && !head.isBlank() && head.equals(proof.get("delivered_head"))
                && GoalContract.requirementsHash(state).equals(proof.get("requirements_hash"))
                && GoalContract.hashData(Config.section(config, "verification")).equals(proof.get("policy_hash"));
    }

    private void recordDeliveryFailure(RuntimeException exc, String operation) {
        String message = codexRunner.redactSensitive("Unable to deliver Codex changes ("
                + operation + "): " + exc.getMessage());
        DeliveryRecovery.record(state, exc, operation, message);
        if (exc instanceof GitOperations.ProtectedPathsException || message.contains("Source changed after verification")) {
            state.put("autonomy_demoted", true);
        }
        save();
        log.error("DELIVERY FAILED: {}", message);
    }

    /** Stop spending on unfinished work without claiming completion or closing findings. */
    private int holdOrPark(Integer current, Map<String, Object> currentPr, String reason, String blockerClass) {
        String head = currentPr == null ? "" : Engine.asString(currentPr.get("headRefOid"));
        if (head.isEmpty() && state.get("current_pr") != null) {
            head = Engine.asString(state.get("current_pr_head"));
        }
        state.put("held_failure", Map.of("head", head, "evidence", git().recoveryFingerprint(),
                "reason", reason, "blocker_class", blockerClass,
                "recovery_epoch", Engine.asLongOrDefault(automation.get("recovery_epoch"), 0)));
        state.put("error", reason);
        state.put("blocker_class", blockerClass);
        state.put("result_status", "BLOCKED");
        state.put("result_evidence", "BLOCKED");
        state.put("phase", "BLOCKED_REQUIREMENTS");
        state.put("exit_code", 1);
        state.put("run_finished_at", Controller.now());
        if (current != null && ParkPolicy.mayAdvance(workflow, state)) {
            ParkPolicy.parkWithCheckpoint(state, current, head, reason, Controller.now());
            try {
                preserveBeforeParking(current);
            } catch (RuntimeException exc) {
                state.put("error", "Cannot advance safely after parking: " + exc.getMessage());
                save();
                return diagnosePreflight(git(), current, currentPr);
            }
            clearActiveStory();
            state.put("phase", "IDLE");
            log.warn("PARKED UNFINISHED: story #{} — {}", current, reason);
        } else {
            log.warn("HELD UNFINISHED: story #{} — {}", current, reason);
        }
        save();
        return 1;
    }

    private void preserveBeforeParking(int story) {
        if (dryRun) return;
        GitOperations git = git();
        if (git.classifyWorktree(state) != GitOperations.WorktreeState.CLEAN) {
            WorktreeRecovery.preserve(state, git, Engine.asString(state.get("branch")), story, this::save);
        }
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
        if (ParkPolicy.finishOpenPrFirst(workflow)) {
            List<Integer> openStories = Engine.linkedStoryNumbers(epic).stream()
                    .filter(story -> !openPrsForStory(prs, story).isEmpty()).toList();
            state.put("open_story_prs", prs.stream()
                    .filter(p -> "OPEN".equals(p.get("state")))
                    .map(p -> Map.of("number", p.get("number"), "branch", Engine.asString(p.get("headRefName")),
                            "head", Engine.asString(p.get("headRefOid")), "url", Engine.asString(p.get("url"))))
                    .toList());
            if (current != null && state.get("current_pr") == null
                    && !openStories.isEmpty() && !openStories.contains(current)) {
                if (openStories.size() != 1) return SelectionResult.exit(conflict("Multiple open story PRs"));
                if (dryRun) return SelectionResult.exit(conflict("Open PR must finish before switching stories (dry-run)"));
                try {
                    StoryHandoff.suspend(state, git(), this::save);
                    log.info("STORY SUSPENDED: #{} preserved; prioritizing open PR for story #{}", current, openStories.getFirst());
                    current = null;
                } catch (RuntimeException exc) {
                    state.put("phase", "BLOCKED_REPOSITORY");
                    state.put("error", "Unable to suspend work for open PR: " + exc.getMessage());
                    save();
                    return SelectionResult.exit(1);
                }
            }
        }
        // Story selection when none is active.
        if (current == null) {
            List<Object[]> candidates = new ArrayList<>();
            for (int story : Engine.linkedStoryNumbers(epic)) {
                List<Map<String, Object>> matches = Engine.prForStory(prs, story);
                List<Map<String, Object>> open = new ArrayList<>();
                for (Map<String, Object> p : matches) {
                    if ("OPEN".equals(p.get("state"))) {
                        if (ParkPolicy.enabled(workflow) && !ParkPolicy.finishOpenPrFirst(workflow)
                                && ParkPolicy.parkedStoryNumbers(state).contains(story)
                                && ParkPolicy.parkedHead(state, story).equals(Engine.asString(p.get("headRefOid")))) {
                            continue;
                        }
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
                if (ParkPolicy.finishOpenPrFirst(workflow)) ParkPolicy.resumeForOpenPr(state, current);
                else ParkPolicy.restoreCheckpoint(state, current, Engine.asString(currentPr.get("headRefOid")));
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
                    boolean unfinished = !ParkPolicy.parkedStoryNumbers(state).isEmpty()
                            || stories.stream().anyMatch(s -> "OPEN".equals(s.get("state"))
                                    && !completedStories().contains(Engine.asInt(s.get("number"))));
                    state.put("phase", unfinished ? "BLOCKED_REQUIREMENTS" : "EPIC_COMPLETE");
                    state.put("message", unfinished ? "Unfinished stories remain parked or ineligible"
                            : "No eligible incomplete stories remain");
                    save();
                    log.info("STORY SELECTION: {}", state.get("message"));
                    return SelectionResult.exit(unfinished ? 1 : 0);
                }
                current = Engine.asInt(selected.get("number"));
                boolean resumed = StoryHandoff.resume(state, current);
                state.put("current_story", current);
                state.put("current_story_url", selected.get("url"));
                state.put("current_story_title", selected.getOrDefault("title", ""));
                // PLANNING gate (Requirement 9, config-gated): when planning is
                // enabled and no valid committed plan exists yet, run PLANNING
                // first; otherwise (default, or a plan already present on resume)
                // go straight to IMPLEMENTING — preserving the current path.
                boolean planningEnabled = Engine.asBool(automation.get("enable_planning"), false);
                if (resumed) {
                    log.info("STORY RESUMED: #{} restored from suspended checkpoint", current);
                } else if (planningEnabled) {
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
            state.put("current_story_issue", currentIssue);
            try {
                Map<String, Object> goal = GoalContract.capture(currentIssue, epic, config);
                String sourceHash = Engine.asString(goal.get("requirements_hash"));
                if (!Engine.asList(goal.get("questions")).isEmpty()) {
                    Map<String, Object> reply = Map.of();
                    if (state.get("clarification_answer") instanceof Map<?, ?> saved
                            && sourceHash.equals(saved.get("source_hash")) && saved.get("reply") instanceof Map<?, ?> existing) {
                        reply = castMap(existing);
                    } else if (state.get("clarification_delivery") instanceof Map<?, ?> pending
                            && sourceHash.equals(pending.get("requirements_hash"))) {
                        String requester = currentIssue.get("author") instanceof Map<?, ?> author
                                ? Engine.asString(author.get("login")) : "";
                        java.util.Set<String> delegated = new java.util.HashSet<>(Engine.asList(
                                Config.section(config, "intent").get("delegated_responders"))
                                .stream().map(String::valueOf).toList());
                        reply = ClarificationReply.latest(github.issueComments(current), sourceHash, requester, delegated);
                    }
                    if (!reply.isEmpty()) {
                        goal = GoalContract.capture(currentIssue, epic, config, reply);
                        state.put("clarification_answer", Map.of("source_hash", sourceHash, "reply", reply));
                    }
                }
                boolean changed = GoalContract.observe(state, goal);
                if (Set.of("CANCELLED", "SUPERSEDED", "ABANDONED", "FAILED", "VERIFIED")
                        .contains(Engine.asString(goal.get("status")))) {
                    state.put("phase", "BLOCKED_REQUIREMENTS");
                    state.put("error", "Story intent is terminal: " + goal.get("status"));
                    state.put("next_action", "CREATE_REVISED_AUTHORIZED_REQUEST");
                    save();
                    return SelectionResult.exit(1);
                }
                if (!Engine.asList(goal.get("questions")).isEmpty()) {
                    state.put("phase", "BLOCKED_REQUIREMENTS");
                    state.put("next_action", "WAIT_FOR_CLARIFICATION");
                    state.put("error", "Story needs clarification: " + goal.get("questions"));
                    deliverClarification(current, goal);
                    save();
                    return SelectionResult.exit(1);
                }
                if (changed) {
                    state.put("recovery_next_prompt", Engine.asBool(automation.get("enable_planning"), false)
                            ? "prompts/plan.md" : "prompts/verify.md");
                }
            } catch (IllegalArgumentException exc) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", exc.getMessage());
                save();
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

    private void deliverClarification(int story, Map<String, Object> goal) {
        String hash = Engine.asString(goal.get("requirements_hash"));
        if (!Engine.asBool(workflow.get("allow_comments"), false) || dryRun) {
            state.put("clarification_delivery", Map.of("requirements_hash", hash,
                    "status", "DISABLED_BY_POLICY", "updated_at", Controller.now()));
            return;
        }
        String marker = "<!-- jcoding-clarification:" + story + ":" + hash.substring(0, Math.min(16, hash.length())) + " -->";
        try {
            Object raw = github.issueComments(story);
            List<?> pages = raw instanceof List<?> list ? list : List.of();
            boolean found = pages.stream().flatMap(page -> page instanceof List<?> list
                    ? list.stream() : java.util.stream.Stream.of(page)).anyMatch(item -> item instanceof Map<?, ?> c
                    && Engine.asString(c.get("body")).contains(marker));
            if (found) {
                state.put("clarification_delivery", Map.of("requirements_hash", hash,
                        "status", "ALREADY_DELIVERED", "marker", marker, "updated_at", Controller.now()));
                return;
            }
            List<String> questions = Engine.asList(goal.get("questions")).stream().map(String::valueOf).toList();
            String body = marker + "\n\nAutomation is waiting for clarification before making changes:\n"
                    + questions.stream().map(q -> "- " + q).collect(java.util.stream.Collectors.joining("\n"))
                    + "\n\nUpdate the issue description, or reply with a full ```jcoding-intent JSON block and "
                    + "<!-- jcoding-answer:" + hash.substring(0, Math.min(16, hash.length())) + " -->. "
                    + "Only repository owners, members or collaborators may answer by comment.";
            github.commentIssue(story, body);
            state.put("clarification_delivery", Map.of("requirements_hash", hash,
                    "status", "DELIVERED", "marker", marker, "updated_at", Controller.now()));
        } catch (RuntimeException failure) {
            state.put("clarification_delivery", Map.of("requirements_hash", hash,
                    "status", "DELIVERY_FAILED", "error", failure.getMessage() == null ? "unknown" : failure.getMessage(),
                    "updated_at", Controller.now()));
            log.warn("CLARIFICATION DELIVERY FAILED for story #{}; workflow remains blocked", story, failure);
        }
    }

    /**
     * Handle the terminal wait / merge-gate phases for the current PR. Returns
     * a non-null exit code when the run should end here, or {@code null} to fall
     * through to the deviation supervisor. Behavior-identical to the former
     * inline block (extracted for Requirement 2).
     */
    private int observeWait(Integer current, Map<String, Object> pr, String phase) {
        WaitPolicy.Decision decision = WaitPolicy.observe(state, phase, pr, automation, java.time.Instant.now());
        save();
        if (decision == WaitPolicy.Decision.WAIT) return 0;
        if (decision == WaitPolicy.Decision.HOLD) {
            state.put("phase", "BLOCKED_EXTERNAL");
            state.put("next_action", "WAIT_FOR_EXTERNAL_EVIDENCE");
            state.put("error", "Passive wait exceeded its diagnosis budget; external evidence must change");
            save();
            return 1;
        }
        state.put("deviation_code", "WAIT_DEADLINE_EXCEEDED");
        state.put("deviation_detail", "Wait timed out: " + phase + "; inspect external evidence before retrying");
        save();
        return runWorkerPhase(current, pr, List.of(), "prompts/reconcile.md");
    }

    private Integer handleWaitAndMergeGate(Integer current, Map<String, Object> currentPr, String phase) {
        if ("READY_TO_MERGE".equals(phase) && currentPr != null) {
            DeploymentPolicy.recordCanary(config, state, currentPr);
            save();
        }

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
            if ("BEHIND".equals(mergeStateStatus)) {
                try {
                    GitOperations git = git();
                    git.checkoutStoryBranch(Engine.asString(currentPr.get("headRefName")));
                    git.updateFromBase(Engine.asString(Config.section(config, "repository").getOrDefault("base_branch", "main")));
                    state.remove("validation_checkpoint");
                    state.put("recovery_next_prompt", "prompts/verify.md");
                    state.put("phase", "VERIFYING");
                } catch (RuntimeException exc) {
                    state.put("error", "Base update requires reconciliation: " + exc.getMessage());
                    state.put("recovery_next_prompt", "prompts/reconcile.md");
                    state.put("phase", "BLOCKED_REPOSITORY");
                }
                save();
                return 0;
            }
            if ("UNKNOWN".equals(mergeable)
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
            if (!mergeProofMatches(Engine.asString(currentPr.get("headRefOid")))) {
                state.put("phase", "VERIFYING");
                state.put("recovery_next_prompt", "prompts/verify.md");
                save();
                return 0;
            }
            try {
                String mergeMethod = Engine.asString(
                        workflow.getOrDefault("merge_method", "squash"));
                int prNumber = Engine.asInt(currentPr.get("number"));
                String expectedHead = Engine.asString(currentPr.get("headRefOid"));
                github.mergePr(prNumber, mergeMethod, expectedHead);
                Map<String, Object> mergedPr = github.pr(prNumber);
                if (!isMerged(mergedPr)) {
                    state.put("phase", "WAITING_FOR_CI");
                    state.put("error", "Merge command returned without GitHub confirming PR #"
                            + prNumber + " as merged");
                    save();
                    log.warn("AUTO-MERGE UNCONFIRMED: PR #{} remains {}; retaining story state",
                            prNumber, mergedPr.get("state"));
                    return 0;
                }
                String mergedHead = Engine.asString(mergedPr.get("headRefOid"));
                if (!expectedHead.isEmpty() && !mergedHead.isEmpty() && !expectedHead.equals(mergedHead)) {
                    state.put("phase", "BLOCKED_EXTERNAL");
                    state.put("error", "PR head changed while merge was being confirmed; refusing to "
                            + "advance the story");
                    save();
                    return 1;
                }
                log.info("AUTO-MERGE: merged PR #{} for story #{} (head {}) via {}",
                        prNumber, current,
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
        return observeWait(current, currentPr, Engine.asString(state.get("phase")));
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
        return PrScopedResult.exit(holdOrPark(current, currentPr,
                "Feedback exhausted its retry budget without a verified resolution", "REVIEW_FEEDBACK"));
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
                if (ParkPolicy.mayAdvance(workflow, state) && parkStory != null) {
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
                newFix = git().classifyWorktree(state)
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
                StateRecovery.applyReconciliationDecision(state, result.exitCode(), result.output());
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

        if (forceDelivery) {
            cleanup(renderedPrompt);
            return holdOrPark(current, currentPr,
                    "Reconciliation exhausted its no-progress budget; work remains unverified", "PROCESS");
        }
        if (resumeAction) {
            String target = Engine.asString(state.get("phase"));
            // Keep the chosen recovery direction ahead of PR feedback routing.
            state.put("recovery_next_prompt", currentPr == null && isPrDependentPhase(target)
                    ? "prompts/verify.md" : Controller.phasePromptFor(target));
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

    private CodexRunner.CodexResult runMeteredWorker(String phase, Path prompt, Runnable heartbeat) {
        return runMeteredWorker(phase, prompt, heartbeat, false);
    }

    private CodexRunner.CodexResult runMeteredWorker(String phase, Path prompt, Runnable heartbeat,
                                                    boolean readOnly) {
        int remaining = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, (phaseDeadlineNanos - System.nanoTime()) / 1_000_000_000L));
        validateTransition();
        actionPolicy.checkActive();
        int timeout = ExecutionBudget.reserve(state, automation, remaining);
        save();
        String callId = Engine.asString(state.get("execution_id")) + ":call:" + System.nanoTime() + ":" + (++callSequence);
        String model = Engine.asString(Config.section(configForPhase(phase), "codex").get("model"));
        long started = System.nanoTime();
        long bytes;
        try { bytes = Files.size(prompt); } catch (java.io.IOException exc) { bytes = 0; }
        Map<String, Object> workerConfig = configForPhase(phase);
        boolean structuredTools = !dryRun && !readOnly && !"RECONCILING".equals(phase);
        WorkerToolBoundary.Request toolRequest = null;
        if (structuredTools) {
            List<String> criterionIds = Engine.asList(state.get("goal_contract") instanceof Map<?, ?> goal
                    ? goal.get("criteria") : List.of()).stream()
                    .filter(Map.class::isInstance).map(value -> Engine.asString(((Map<?, ?>) value).get("id")))
                    .filter(id -> !id.isBlank()).toList();
            List<String> reviewThreadIds = ReviewFindingQueue.activeBatch(state).stream()
                    .map(finding -> Engine.asString(finding.get("thread_id")))
                    .filter(id -> !id.isBlank()).distinct().toList();
            toolRequest = new WorkerToolBoundary.Request(callId, GoalContract.requirementsHash(state),
                    git().recoveryFingerprint(), phase, repo, timeout, criterionIds, reviewThreadIds);
            try { Files.writeString(prompt, WorkerToolBoundary.instruction(toolRequest),
                    java.nio.file.StandardOpenOption.APPEND); }
            catch (java.io.IOException exc) { throw new IllegalStateException("Cannot write controller tool protocol", exc); }
        }
        if (readOnly || structuredTools) {
            workerConfig = new LinkedHashMap<>(workerConfig);
            Map<String, Object> codex = new LinkedHashMap<>(Config.section(workerConfig, "codex"));
            codex.put("diagnostic_read_only", true);
            workerConfig.put("codex", codex);
        }
        CodexRunner.CodexResult result;
        try {
            result = codexRunner.run(workerConfig, prompt, repo, dryRun, heartbeat, timeout);
        } catch (ActionPolicy.Cancelled cancelled) {
            ExecutionBudget.finish(state, Math.max(0, (System.nanoTime() - started) / 1_000_000L), Map.of());
            state.put("last_worker_outcome", "CANCELLED");
            save();
            throw cancelled;
        }
        if (structuredTools && result.exitCode() == 0) {
            try {
                WorkerToolBoundary.Proposal proposal = WorkerToolBoundary.parse(toolRequest, result.output());
                state.put("tool_effect_pending", Map.of("execution_id", callId,
                        "requirements_hash", toolRequest.requirementsHash(),
                        "source", toolRequest.sourceFingerprint(), "phase", phase,
                        "proposal_hash", GoalContract.hash(result.output())));
                save();
                GitOperations toolGit = git();
                List<String> proposedPaths = new ArrayList<>();
                for (WorkerToolBoundary.Change change : proposal.changes()) {
                    proposedPaths.add(change.path());
                    if ("rename".equals(change.operation())) proposedPaths.add(change.from());
                }
                toolGit.authorizeToolPaths(proposedPaths);
                WorkerToolBoundary.Result applied = WorkerToolBoundary.apply(toolRequest, proposal, toolGit, config,
                        actionPolicy::checkActive);
                if ("APPLIED".equals(applied.status())) {
                    Set<String> all = new java.util.TreeSet<>();
                    if (Engine.asString(state.get("worker_owned_story")).equals(Engine.asString(state.get("current_story")))
                            && Engine.asString(state.get("worker_owned_branch")).equals(Engine.asString(state.get("branch")))) {
                        for (Object path : Engine.asList(state.get("worker_owned_paths"))) all.add(String.valueOf(path));
                    }
                    all.addAll(proposedPaths);
                    state.put("worker_owned_paths", new ArrayList<>(all));
                    state.put("worker_owned_story", state.get("current_story"));
                    state.put("worker_owned_branch", state.get("branch"));
                    Map<String, Object> attribution = state.get("change_requirement_attribution") instanceof Map<?, ?> old
                            ? new LinkedHashMap<>(castMap(old)) : new LinkedHashMap<>();
                    for (WorkerToolBoundary.Change change : proposal.changes()) {
                        Map<String, Object> proof = Map.of("criteria", change.criterionIds(),
                                "requirements_hash", toolRequest.requirementsHash(),
                                "execution_id", callId, "source_before", toolRequest.sourceFingerprint(),
                                "source_after", applied.sourceAfter());
                        attribution.put(change.path(), proof);
                        if ("rename".equals(change.operation())) attribution.put(change.from(), proof);
                    }
                    state.put("change_requirement_attribution", attribution);
                }
                state.put("last_tool_result", Map.of("execution_id", callId, "status", applied.status(),
                        "failure_class", applied.failureClass(), "detail", applied.detail(),
                        "paths", applied.paths(), "source_after", applied.sourceAfter()));
                state.remove("tool_effect_pending");
                save();
                if (!"APPLIED".equals(applied.status())) {
                    result = new CodexRunner.CodexResult(1, "", result.usage(), applied.failureClass() + ": " + applied.detail());
                } else {
                    String next = "PLANNING".equals(phase) ? "IMPLEMENTING" : "VERIFYING";
                    StringBuilder footer = new StringBuilder("RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: ")
                            .append(next).append("\nRESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
                    proposal.fixedThreadIds().forEach(id -> footer.append("FIXED_THREAD: ").append(id).append('\n'));
                    proposal.supersededThreadIds().forEach(id -> footer.append("SUPERSEDED_THREAD: ").append(id).append('\n'));
                    result = new CodexRunner.CodexResult(0, footer.toString(), result.usage(), result.diagnostics());
                }
            } catch (ActionPolicy.Cancelled cancelled) {
                throw cancelled;
            } catch (RuntimeException exc) {
                state.remove("tool_effect_pending");
                String failureClass = exc instanceof GitOperations.ProtectedPathsException
                        ? "PROTECTED_PATH" : "INVALID_WORKER_RESULT";
                state.put("last_tool_result", Map.of("execution_id", callId,
                        "status", "REJECTED", "failure_class", failureClass,
                        "detail", codexRunner.redactSensitive(Engine.asString(exc.getMessage()))));
                save();
                result = new CodexRunner.CodexResult(1, "", result.usage(),
                        codexRunner.redactSensitive(Engine.asString(exc.getMessage())));
            }
        }
        long elapsed = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        Map<String, Long> usage = new LinkedHashMap<>();
        if (result.usage().inputTokens() != null) usage.put("input_tokens", result.usage().inputTokens());
        if (result.usage().cachedInputTokens() != null) usage.put("cached_input_tokens", result.usage().cachedInputTokens());
        if (result.usage().outputTokens() != null) usage.put("output_tokens", result.usage().outputTokens());
        ExecutionBudget.finish(state, elapsed, usage);
        String output = codexRunner.redactSensitive(result.output());
        String diagnostics = codexRunner.redactSensitive(result.diagnostics());
        if (result.exitCode() != 0 || !"SUCCESS".equals(StateRecovery.parseResult(output).get("result_status"))) {
            RecoveryMemory.observe(state, phase, Engine.asString(state.get("decision_action")),
                    diagnostics.isBlank() ? output : diagnostics, false);
        } else {
            state.remove("recovery_strategy");
        }
        state.put("last_worker_evidence", Map.of("job_id", callId, "phase", phase,
                "exit_code", result.exitCode(), "output", tail(output, 12000), "failed_commands", diagnostics,
                "policy_hash", GoalContract.hashData(workflow), "requirements_hash", GoalContract.requirementsHash(state)));
        store.recordWorkerUsage(callId, Engine.asInt(state.get("current_story")), phase, model,
                elapsed, bytes, result.exitCode(), result.usage().inputTokens(),
                result.usage().cachedInputTokens(), result.usage().outputTokens(), output, diagnostics,
                promptHash(prompt), GoalContract.hashData(Config.section(workerConfig, "codex")),
                GoalContract.hashData(Map.of("workflow", workflow, "verification", Config.section(config, "verification"),
                        "isolation", Config.section(config, "codex").getOrDefault("isolation", "native"))));
        save();
        return result;
    }

    private static String promptHash(Path prompt) {
        try { return GoalContract.hash(java.nio.file.Files.readString(prompt)); }
        catch (java.io.IOException exc) { return "unavailable"; }
    }

    /** Review the exact local diff; any missing or failed evidence stops delivery. */
    @SuppressWarnings("unchecked")
    private boolean runInternalReviewGate(GitOperations git, Integer current,
                                          Map<String, Object> context, Runnable heartbeat) {
        int maxFix = (int) Engine.asLongOrDefault(
                automation.get("internal_review_max_fix_attempts"), 2);
        List<String> changedPaths = git.changedSourcePaths();
        if (changedPaths.isEmpty()) {
            log.info("INTERNAL REVIEW: no local diff to review; skipping gate");
            return true;
        }
        InternalReviewGate gate = new InternalReviewGate(
                InternalReviewGate.DEFAULT_PANEL, maxFix);
        Path rendered = root.resolve("state").resolve("prompt-" + epicNumber + ".md");
        ReviewContext reviewContext = new ReviewContext() {
            @Override public String snapshot() { return git.recoveryFingerprint(); }
            @Override public List<String> changedPaths() { return git.changedSourcePaths(); }
            @Override public String signature(InternalReviewGate.Specialist specialist) {
                Path reviewPrompt = root.resolve(specialist.reviewPrompt());
                Path fixPrompt = root.resolve(specialist.fixPrompt());
                Path commonPrompt = root.resolve("prompts/common.md");
                if (!Files.isRegularFile(reviewPrompt) || !Files.isRegularFile(fixPrompt)
                        || !Files.isRegularFile(commonPrompt)) return "";
                try {
                    String effective = Config.section(configForPhase("REVIEWING_CODE"), "codex").toString();
                    byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
                            (Files.readString(commonPrompt) + "\n" + Files.readString(reviewPrompt)
                                    + "\n" + Files.readString(fixPrompt) + "\n" + effective + "\n"
                                    + Engine.asString(state.get("current_story_title")))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return java.util.HexFormat.of().formatHex(bytes);
                } catch (Exception exc) {
                    return "";
                }
            }
            @Override public Map<String, Object> cache() {
                Object existing = state.get("review_cache");
                return existing instanceof Map<?, ?> ? (Map<String, Object>) existing : new LinkedHashMap<>();
            }
            @Override public List<InternalReviewGate.Finding> unresolved() {
                List<InternalReviewGate.Finding> findings = new ArrayList<>();
                Object existing = state.get("internal_review_findings");
                if (existing instanceof List<?> list) for (Object item : list) {
                    if (!(item instanceof Map<?, ?> finding)) continue;
                    Object evidence = finding.get("evidence");
                    if (evidence instanceof Map<?, ?> e) findings.add(new InternalReviewGate.Finding(
                            Engine.asString(finding.get("specialist")), Engine.asString(finding.get("snapshot")),
                            new ReviewEvidence(Engine.asInt(e.get("exit_code")), Engine.asString(e.get("output"))),
                            Engine.asString(finding.get("reason"))));
                }
                return findings;
            }
            @Override public void persist(InternalReviewGate.Report report) {
                Map<String, Object> cache = cache();
                // Cache is useful only for a bounded recent history of snapshots.
                while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
                state.put("review_cache", cache);
                List<Object> findings = new ArrayList<>();
                for (InternalReviewGate.Finding f : report.unresolved()) findings.add(Map.of(
                        "specialist", f.specialist(), "snapshot", f.snapshot(), "reason", f.reason(),
                        "evidence", Map.of("exit_code", f.evidence().exitCode(), "output", f.evidence().output())));
                state.put("internal_review_findings", findings);
                state.put("internal_review_snapshot", report.snapshot());
                state.put("internal_review_passed", report.passed());
                save();
            }
        };
        InternalReviewGate.Report report = gate.run(reviewContext, request -> {
            String promptFile = request.promptFile();
            Path promptPath = root.resolve(promptFile);
            if (!Files.isRegularFile(promptPath)) {
                return new ReviewEvidence(-1, "review prompt missing: " + promptFile);
            }
            try {
                Map<String, Object> scoped = new LinkedHashMap<>();
                scoped.put("epic", epic);
                scoped.put("current_story", current);
                scoped.put("current_story_title", state.getOrDefault("current_story_title", ""));
                scoped.put("current_pr", context.get("current_pr"));
                scoped.put("diff_fingerprint", reviewContext.snapshot());
                scoped.put("changed_paths", reviewContext.changedPaths());
                scoped.put("review_specialist", request.specialist().name());
                if (request.findings() != null) scoped.put("review_findings", request.findings().output());
                Files.writeString(rendered, PromptRenderer.render(root, promptFile, scoped));
            } catch (Exception exc) {
                return new ReviewEvidence(-1, "unable to render " + promptFile + ": " + exc.getMessage());
            }
            try {
                CodexRunner.CodexResult result = runMeteredWorker("REVIEWING_CODE", rendered, heartbeat, !request.fix());
                return new ReviewEvidence(result.exitCode(), result.output());
            } catch (RuntimeException exc) {
                return new ReviewEvidence(-1, "review worker failed: " + exc.getMessage());
            }
        });
        if (!report.passed()) {
            log.warn("INTERNAL REVIEW: blocking delivery with {} unresolved finding(s)", report.unresolved().size());
        }
        return report.passed();
    }

    private Integer prepareWorktreeForPhase(GitOperations git, Integer current,
                                            Map<String, Object> currentPr) {
        if (!dryRun) WorktreeRecovery.isolateParkedWork(state, git, this::save);
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
                if (!dryRun) WorktreeRecovery.restoreActiveWork(state, git, this::save);
            } catch (RuntimeException exc) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Unable to check out story branch " + activeBranch
                        + ": " + exc.getMessage());
                save();
                log.debug("RECONCILE: " + state.get("error"));
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
                    log.debug("RECONCILE: Unsafe working tree: " + exc.getMessage());
                    return 1;
                }
            }
            case DIRTY_DEFAULT_BRANCH -> {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Working tree has uncommitted changes on the default branch; "
                        + "reconcile before proceeding. Commit or relocate these changes onto a "
                        + "story branch, or remove them, then rerun.");
                save();
                log.debug("RECONCILE: uncommitted changes on the default branch require reconciliation");
                return 1;
            }
            case DIRTY_UNKNOWN_BRANCH -> {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Working tree has changes on a branch that is not the active "
                        + "story branch; reconcile before proceeding.");
                save();
                log.debug("RECONCILE: changes outside the active story branch require reconciliation");
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
                log.debug("BLOCKED: " + state.get("error"));
                return 1;
            }
        }
        return null;
    }

    /** Diagnosis bypasses preparation, never its safety gates or delivery checks. */
    private int diagnosePreflight(GitOperations git, Integer current, Map<String, Object> currentPr) {
        String detail = Engine.asString(state.get("error"));
        String branch;
        try { branch = git.currentBranchName(); } catch (RuntimeException exc) { branch = "unavailable"; }
        String evidence = current + ":" + branch + ":" + Engine.asString(state.get("branch"))
                + ":" + git.recoveryFingerprint() + ":" + detail + ":" + config.hashCode();
        Map<String, Object> failure = PreflightRecovery.observe(state, evidence, detail);
        failure.put("actual_branch", branch);
        failure.put("expected_branch", Engine.asString(state.get("branch")));
        state.put("blocker_class", "PROCESS");
        state.put("next_action", "DIAGNOSE_PREFLIGHT");
        int cap = (int) Engine.asLongOrDefault(automation.get("max_preflight_diagnoses"), 2);
        if (!PreflightRecovery.reserve(failure, cap)) {
            state.put("next_action", "WAIT_FOR_PREFLIGHT_EVIDENCE");
            save();
            logStableState("PREFLIGHT_HELD|" + evidence,
                    "PREFLIGHT HELD: {}. See preflight_failure for supervisor diagnosis; "
                            + "retry when repository evidence or configuration changes", detail);
            return 1;
        }
        save(); // Count even a crashed invocation; no infinite restart loop.
        Path prompt = root.resolve("state/preflight-" + epicNumber + ".md");
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("epic", epic);
            context.put("current_story", current);
            context.put("current_pr", currentPr);
            context.put("state", state);
            String instructions = """
                    PREFLIGHT SUPERVISOR: read-only diagnosis of a controller blocker.
                    Inspect current evidence and identify root cause, ownership, missing condition,
                    and exactly one safe next action. Do not edit, stash, checkout, commit, push,
                    call mutating external tools, or bypass a guard. Do not change another story.
                    A decision is not proof of recovery. Repeated identical advice is not progress.
                    Distinguish controller defects, repository repairs, requirements, access,
                    conflicts, and external outages. Identify the exact file/rule or prerequisite.
                    Report DECISION_REASON, BLOCKER_CLASS, RECOVERY_ACTION, and the evidence
                    that will demonstrate recovery. The controller will recheck preflight before
                    any implementation or delivery and hold unchanged failures after its budget.
                    """;
            // The emergency path remains usable when a normal phase prompt is missing.
            Files.writeString(prompt, instructions + "\nCONTEXT:\n"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(context));
            int seconds = (int) Engine.asLongOrDefault(automation.get("preflight_diagnosis_seconds"), 120);
            phaseDeadlineNanos = System.nanoTime() + Math.max(1, seconds) * 1_000_000_000L;
            state.put("worker_status", "RUNNING");
            save();
            log.info("PREFLIGHT SUPERVISOR START: story=#{} attempt={} read-only=true", current, failure.get("attempts"));
            CodexRunner.CodexResult result = runMeteredWorker("RECONCILING", prompt, lock::refresh, true);
            String output = codexRunner.redactSensitive(tail(result.output(), 12000));
            failure.put("diagnosis", output);
            failure.put("exit_code", result.exitCode());
            Map<String, String> decision = StateRecovery.parseDiagnosis(output);
            failure.put("decision", decision);
            failure.put("stage", "DIAGNOSED");
            log.info("PREFLIGHT SUPERVISOR EXIT: exit_code={} recovery={}", result.exitCode(),
                    decision.getOrDefault("RECOVERY_ACTION", "Inspect preflight_failure.diagnosis"));
        } catch (java.io.IOException | RuntimeException exc) {
            failure.put("diagnosis_error", codexRunner.redactSensitive(exc.getMessage()));
            failure.put("stage", "FAILED");
        } finally {
            state.put("worker_status", "IDLE");
            state.put("exit_code", 1);
            state.put("run_finished_at", Controller.now());
            state.put("error", detail);
            save();
            cleanup(prompt);
        }
        return 1;
    }

    private void advanceStory(int current) {
        List<Object> completed = new ArrayList<>();
        Object existing = state.get("completed_stories");
        if (existing instanceof List<?> list) {
            completed.addAll(list);
        }
        completed.add(current);
        state.put("completed_stories", completed);
        clearActiveStory();
    }

    private static boolean isMerged(Map<String, Object> pr) {
        return pr != null && (!Engine.asString(pr.get("mergedAt")).isBlank()
                || "MERGED".equalsIgnoreCase(Engine.asString(pr.get("state"))));
    }

    private void clearActiveStory() {
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
