package com.rahmatwahab.codexautomation;

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
        store.save(state);
    }

    int reconcileAndExecute(List<Map<String, Object>> prs, String forcedPrompt) {
        Object currentObj = state.get("current_story");
        Integer current = currentObj == null ? null : Engine.asInt(currentObj);
        Map<String, Object> currentPr = null;

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
                    return conflict("Multiple open PRs for story #" + story);
                }
                if (!open.isEmpty()) {
                    candidates.add(new Object[] {story, open.get(0)});
                }
            }
            if (candidates.size() > 1) {
                return conflict("Multiple open story PRs");
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
                    return blockedExternal("Unable to inspect linked stories: " + exc.getMessage());
                }
                Map<String, Object> selected = Engine.selectStory(stories, completedStories());
                if (selected == null) {
                    state.put("phase", "EPIC_COMPLETE");
                    state.put("message", "No eligible incomplete stories remain");
                    save();
                    log.info("EPIC COMPLETE");
                    return 0;
                }
                current = Engine.asInt(selected.get("number"));
                state.put("current_story", current);
                state.put("current_story_url", selected.get("url"));
                state.put("current_story_title", selected.getOrDefault("title", ""));
                state.put("phase", "IMPLEMENTING");
                save();
            }
        }

        // Adopt an unambiguous open PR for the active story if not recorded.
        currentPr = findRecordedPr(prs, currentPr);
        if (current != null && currentPr == null) {
            List<Map<String, Object>> storyPrs = openPrsForStory(prs, current);
            if (storyPrs.size() > 1) {
                return conflict("Multiple open PRs for story #" + current);
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
                return blockedExternal("Unable to verify current story #" + current + ": " + exc.getMessage());
            }
            boolean linked = Engine.linkedStoryNumbers(epic).contains(current);
            boolean mergedCurrentPr = currentPr != null && currentPr.get("mergedAt") != null;
            if (!linked || (!"OPEN".equals(Engine.asString(currentIssue.get("state")).toUpperCase()) && !mergedCurrentPr)) {
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Current story #" + current + " is no longer an eligible linked open story");
                save();
                log.error("BLOCKED: " + state.get("error"));
                return 1;
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
                    return blockedExternal("Unable to inspect dependency #" + dependency + ": " + exc.getMessage());
                }
                if (!"CLOSED".equals(Engine.asString(dependencyIssue.get("state")).toUpperCase())) {
                    state.put("phase", "BLOCKED_REQUIREMENTS");
                    state.put("error", "Story #" + current + " dependency #" + dependency + " is not complete");
                    save();
                    log.error("BLOCKED: " + state.get("error"));
                    return 1;
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

        List<Map<String, Object>> feedbackToResolve = new ArrayList<>();
        // PR-scoped reconciliation and wait/merge/blocked short-circuits.
        if (currentPr != null) {
            try {
                log.info("PHASE: GitHub PR detail #" + currentPr.get("number"));
                currentPr = github.pr(Engine.asInt(currentPr.get("number")));
            } catch (GitHubError exc) {
                return blockedExternal("Unable to inspect PR: " + exc.getMessage());
            }
            state.put("current_pr_head", currentPr.get("headRefOid"));
            state.put("pr_state", currentPr.get("state"));
            state.put("pr_review_decision", currentPr.get("reviewDecision"));
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
                            return blockedExternal("Unable to resolve delivered review feedback: " + exc.getMessage());
                        }
                    }
                }
            }

            List<Map<String, Object>> feedback = Engine.actionableFeedback(
                    currentPr, Engine.asString(state.get("last_feedback_id")).isEmpty()
                            ? null : Engine.asString(state.get("last_feedback_id")));
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

                // Bounded loop guard: if the same PR head has been in
                // FIXING_FEEDBACK for the same feedback repeatedly without the
                // head advancing, stop and require human review instead of
                // looping (and burning quota) forever.
                String lastGuardHead = Engine.asString(state.get("feedback_guard_head"));
                int feedbackAttempts;
                if (head.equals(lastGuardHead)) {
                    feedbackAttempts = (int) Engine.asLongOrDefault(
                            state.get("feedback_attempts"), 0) + 1;
                } else {
                    feedbackAttempts = 1;
                    state.put("feedback_guard_head", head);
                }
                state.put("feedback_attempts", feedbackAttempts);
                int maxAttempts = (int) Engine.asLongOrDefault(
                        automation.get("max_feedback_attempts"), 3);
                if (feedbackAttempts > maxAttempts) {
                    state.put("phase", "BLOCKED_EXTERNAL");
                    state.put("error", "Feedback for PR #" + currentPr.get("number")
                            + " head " + shortSha(head) + " was reprocessed " + feedbackAttempts
                            + " times without resolution; manual review required");
                    save();
                    log.error("BLOCKED: " + state.get("error"));
                    return 1;
                }
            } else {
                // No actionable feedback on this head: reset the guard.
                state.remove("feedback_guard_head");
                state.remove("feedback_attempts");
            }
            feedbackToResolve = new ArrayList<>(feedback);
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
                return blockedExternal("Unable to post Codex review trigger: " + exc.getMessage());
            }

            if (ReviewCoordinator.reviewGateOpen(currentPr)) {
                state.put("phase", "WAITING_FOR_CODEX_REVIEW");
                state.put("review_gate_head", currentPr.get("headRefOid"));
                state.put("next_action", "WAIT_FOR_CODEX_REVIEW");
                save();
                log.info("WAITING: story #" + current + ", phase=WAITING_FOR_CODEX_REVIEW");
                return 0;
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
                if (phase.equals("READY_TO_MERGE") && !dryRun
                        && Engine.asBool(workflow.get("allow_comments"), false)) {
                    if (!github.hasComment(currentPr, "READY TO MERGE")) {
                        github.comment(Engine.asInt(currentPr.get("number")), "READY TO MERGE");
                    }
                    state.put("phase", "WAITING_FOR_MANUAL_MERGE");
                    state.put("last_processed_commit", currentPr.get("headRefOid"));
                }
                save();
                log.info("WAITING: story #{} phase={} reason={} reviewDecision={} ciChecks={} head={}",
                        current, state.get("phase"), waitReason(state.get("phase"), currentPr),
                        Engine.asString(currentPr.get("reviewDecision")),
                        Engine.asList(currentPr.get("statusCheckRollup")).size(),
                        shortSha(currentPr.get("headRefOid")));
                return 0;
            }
            if (phase.equals("MERGED")) {
                advanceStory(current);
                save();
                log.info("MERGED: story #" + current + "; next run may select another story");
                return 0;
            }
            if (Set.of("BLOCKED_EXTERNAL", "CONFLICT_DETECTED").contains(phase)) {
                save();
                log.info("STOPPED: story #" + current + ", phase=" + state.get("phase"));
                return 1;
            }
        }

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

        // Full-loop fix: before validating the worktree, actively check out the
        // active story branch. Previously the controller validated the worktree
        // while the repo was still on the default branch, so a dirty default
        // branch threw an unrecoverable IllegalStateException that pinned
        // BLOCKED_REPOSITORY and looped forever without ever committing.
        String activeBranch = Engine.asString(state.get("branch"));
        if (!activeBranch.isEmpty()) {
            try {
                git.checkoutStoryBranch(activeBranch);
            } catch (RuntimeException exc) {
                // A failed checkout is a genuine repository problem; route it to
                // bounded reconciliation rather than a permanent hard block.
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("error", "Unable to check out story branch " + activeBranch
                        + ": " + exc.getMessage());
                save();
                log.error("RECONCILE: " + state.get("error"));
                return 1;
            }
        }

        // Classify the worktree without throwing so a dirty default branch or a
        // stray-changes situation is routed to bounded reconciliation (which can
        // resume, isolate, or block with a clear operator message) instead of an
        // infinite BLOCKED_REPOSITORY loop.
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
        };
        log.info("PHASE ENTER: {} story=#{} pr=#{} attempt={} budget={}s prompt={}",
                executionPhase, current,
                currentPr == null ? "none" : currentPr.get("number"),
                state.get("phase_attempt"),
                phaseBudget == null ? "default" : phaseBudget,
                prompt.getFileName());
        long phaseStartNanos = System.nanoTime();
        CodexRunner.CodexResult result = codexRunner.run(config, renderedPrompt, repo, dryRun,
                heartbeat, phaseBudget);
        long phaseElapsedMs = (System.nanoTime() - phaseStartNanos) / 1_000_000L;
        log.info("PHASE EXIT: {} exit_code={} elapsed={}ms", executionPhase,
                result.exitCode(), phaseElapsedMs);
        lock.refresh();

        String workerStatus = result.exitCode() == 0 ? "SUCCEEDED"
                : (result.exitCode() == 124 ? "TIMED_OUT" : "FAILED");
        store.finishJob(jobId, workerStatus, result.exitCode(),
                codexRunner.redactSensitive(tail(result.output(), 1000)));

        // Defensive post-Codex PR adoption: a worker or an interrupted
        // controller may have created a PR without persisting its number.
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

        state.putAll(Engine.applyCodexResult(state, result.exitCode(), result.output(),
                (int) Engine.asLongOrDefault(automation.get("quota_retry_seconds"), 1800)));

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
            StateRecovery.ReconciliationOutcome outcome =
                    StateRecovery.applyReconciliationDecision(state, result.output());
            if (!outcome.accepted()) {
                int attempts = (int) Engine.asLongOrDefault(state.get("reconciliation_attempts"), 0) + 1;
                state.put("phase", "BLOCKED_REQUIREMENTS");
                state.put("reconciliation_attempts", attempts);
                state.put("error", outcome.detail());
                state.put("decision_rejection", outcome.detail());
                if (attempts >= 3) {
                    state.put("phase", "BLOCKED_EXTERNAL");
                    state.put("error", "reconciliation failed three consecutive times; manual intervention required");
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

            // Deterministic anti-loop backstop: reconciliation that repeatedly
            // resumes work (RESUME_* / PROCESS_FEEDBACK) on the SAME PR head
            // without the head ever advancing means the resumed phase keeps
            // failing to complete and nothing is being committed. Rather than
            // loop (and burn quota) forever, escalate to human review after a
            // bounded number of consecutive same-head resume decisions. This is
            // independent of whatever action the worker chose, so a poor model
            // decision cannot cause an infinite reconcile->resume->reconcile
            // cycle. A head that advances (a commit landed) resets the guard.
            String action = Engine.asString(state.get("decision_action"));
            boolean resumeAction = action.startsWith("RESUME") || action.equals("PROCESS_FEEDBACK");
            String reconcileHead = currentPr == null
                    ? "" : Engine.asString(currentPr.get("headRefOid"));
            if (resumeAction) {
                String guardHead = Engine.asString(state.get("resume_guard_head"));
                int resumeAttempts;
                if (!reconcileHead.isEmpty() && reconcileHead.equals(guardHead)) {
                    resumeAttempts = (int) Engine.asLongOrDefault(state.get("resume_attempts"), 0) + 1;
                } else {
                    resumeAttempts = 1;
                    state.put("resume_guard_head", reconcileHead);
                }
                state.put("resume_attempts", resumeAttempts);
                int maxResume = (int) Engine.asLongOrDefault(
                        automation.get("max_resume_attempts"), 3);
                if (resumeAttempts > maxResume) {
                    state.put("phase", "BLOCKED_EXTERNAL");
                    state.put("error", "Reconciliation resumed work on PR head "
                            + shortSha(reconcileHead) + " " + resumeAttempts
                            + " times without the head advancing (nothing committed); "
                            + "manual review required. If the fix is complete but a heavy "
                            + "validation lane cannot finish in-budget, the worker should "
                            + "return RESULT_EVIDENCE: DEFERRED_TO_CI with "
                            + "RESULT_NEXT_PHASE: WAITING_FOR_CI so the controller can commit "
                            + "and let CI validate.");
                    save();
                    log.error("BLOCKED: " + state.get("error"));
                    return 1;
                }
            } else {
                state.remove("resume_guard_head");
                state.remove("resume_attempts");
            }

            save();
            log.info("RECONCILIATION ACCEPTED: " + state.get("decision_action") + " — " + outcome.detail());
            return 0;
        }

        if (result.exitCode() != 0) {
            state.put("raw_error", codexRunner.redactSensitive(tail(result.output(), 2000)));
        }

        // A zero exit only means the CLI completed; the structured worker
        // result controls whether mutations are allowed. Non-SUCCESS fails closed.
        if (result.exitCode() == 0 && "SUCCESS".equals(state.get("result_status"))) {
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
        boolean deliveryVerified = false;
        String deliveredCommit = null;
        Set<String> declaredFixedIds = ReviewCoordinator.fixedThreadIds(output);

        if (!dryRun && Engine.asBool(workflow.get("allow_push"), false)) {
            try {
                String branch = git.gitBranch();
                String committed = git.commitAndPush(branch, current);
                deliveredCommit = committed;
                if (committed != null) {
                    state.put("branch", branch);
                    state.put("last_processed_commit", committed);
                    // A real head advance means progress was made; clear the
                    // deterministic resume-loop guard so future reconciliation
                    // starts fresh rather than counting pre-commit attempts.
                    state.remove("resume_guard_head");
                    state.remove("resume_attempts");
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
            }
        }

        // Map declared fix ids to canonical thread ids; unknown ids fail closed.
        ReviewCoordinator.ThreadIdMapping mapping = ReviewCoordinator.canonicalFixedThreadIds(
                currentPr != null ? currentPr : new LinkedHashMap<>(), declaredFixedIds);
        if (!mapping.unknown().isEmpty()) {
            List<String> sorted = new ArrayList<>(mapping.unknown());
            sorted.sort(String::compareTo);
            state.put("phase", "BLOCKED_EXTERNAL");
            state.put("error", "Codex declared review IDs that are not present in the "
                    + "authoritative PR thread snapshot: " + String.join(", ", sorted));
            save();
            log.error("BLOCKED: " + state.get("error"));
            return 1;
        }

        // Reply to and resolve only verified, delivered, explicitly-fixed threads.
        if (deliveryVerified && deliveredCommit != null && currentPr != null
                && !mapping.canonical().isEmpty()
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

        // Create a PR for a ready story that has none yet.
        if (!dryRun && "READY_TO_MERGE".equals(state.get("phase")) && currentPr == null) {
            if (!Engine.asBool(workflow.get("allow_push"), false)
                    || !Engine.asBool(workflow.get("allow_pr_creation"), false)) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", "PR creation is disabled by configuration");
            } else {
                try {
                    Map<String, Object> created = castMap(
                            review.createStoryPr(git, github, config, current, epicNumber));
                    state.put("current_pr", created.get("number"));
                    state.put("current_pr_url", created.get("url"));
                    state.put("branch", created.get("headRefName"));
                    state.put("phase", "WAITING_FOR_CI");
                    Map<String, Object> createdDetail = github.pr(Engine.asInt(created.get("number")));
                    review.ensureCodexReviewTrigger(github, createdDetail, state, workflow, dryRun, this::save);
                } catch (RuntimeException exc) {
                    state.put("phase", "BLOCKED_EXTERNAL");
                    state.put("error", "Unable to create PR: " + exc.getMessage());
                }
            }
        }
        return Integer.MIN_VALUE;
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
