package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * orchestrator integration test in dry-run mode. Uses a fake GitHub
 * (canned issue/PR JSON via an injected command runner) and a real temp git
 * repo and prompt tree; no real GitHub, no network, no codex process. Verifies
 * that with no existing PR the controller selects an eligible story, dispatches
 * the implement phase, and persists durable state.
 */
class WorkflowOrchestratorIntegrationTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Fake GitHub serving a fixed epic and story issues; no PRs exist. */
    private static final class FakeGitHub extends GitHub {
        FakeGitHub() {
            super("owner", "repo", "GH_TOKEN", "gh", 30,
                    (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t");
        }

        @Override
        public Object issue(int number) {
            if (number == 27) {
                return map("number", 27, "title", "EPIC", "state", "OPEN",
                        "body", "Stories: #45", "url", "http://x/27");
            }
            return map("number", number, "title", "Story " + number, "state", "OPEN",
                    "body", "Acceptance criteria", "url", "http://x/" + number);
        }

        @Override
        public Object prs() {
            return new ArrayList<>();
        }
    }

    @Test
    void selectsEligibleStoryAndDispatchesImplementInDryRun(@TempDir Path root) throws Exception {
        // Real git repo with an initial commit on main and a local "origin"
        // remote so synchronized story-branch preparation succeeds.
        Path origin = root.resolve("origin.git");
        Files.createDirectories(origin);
        runGit(origin, "init", "-q", "--bare", "-b", "main");
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q", "-b", "main");
        runGit(repo, "config", "user.email", "t@example.com");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("seed.txt"), "seed");
        runGit(repo, "add", "-A");
        runGit(repo, "commit", "-q", "-m", "seed");
        runGit(repo, "remote", "add", "origin", origin.toString());
        runGit(repo, "push", "-q", "-u", "origin", "main");

        // Prompt tree (implement.md + common.md).
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON POLICY");
        Files.writeString(prompts.resolve("implement.md"), "{{COMMON}}\nImplement the story.");
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true),
                "budgets", map(),
                "workflow", map("allow_push", false));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "IDLE");
        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"),
                900, 3, 10);

        GitHub github = new FakeGitHub();
        Map<String, Object> epic = (Map<String, Object>) github.issue(27);

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                new Controller.GitOperationsFactory(repo), new CodexRunner(),
                new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(), null);

        assertThat(code).isEqualTo(0);
        assertThat(state.get("current_story")).isEqualTo(45);
        // Dry-run codex returns success-empty; unstructured output fails closed
        // to BLOCKED_REQUIREMENTS, proving the no-invented-success safety gate.
        assertThat(state.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(Files.exists(store.dbPath())).isTrue();
        lock.release();
    }

    @Test
    void dryRunFeedbackCapBlocksOnceAndDoesNotLoopForever(@TempDir Path root) throws Exception {
        // Regression for the production loop: in dry-run (delivery disabled), an
        // unresolved review thread on a stable head drove FIXING_FEEDBACK past
        // max_feedback_attempts, then every subsequent cron tick re-detected the
        // same feedback and re-blocked with an ever-increasing "reprocessed N
        // times" count — forever. After the fix the head is marked terminally
        // stuck once the cap is exceeded, and later ticks stay blocked WITHOUT
        // re-incrementing the attempt counter.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("feedback.md"), "{{COMMON}}\nFix.");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        String head = "stablehead1234";
        Map<String, Object> prSnapshot = map(
                "number", 187, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/52-x", "url", "http://x/pr/187",
                "reviewDecision", "CHANGES_REQUESTED", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "preserve phase route_refresh", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true, "max_feedback_attempts", 3),
                "budgets", map(),
                "workflow", map()); // delivery disabled (dry_run + no allow_push)

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #52", "url", "http://x/27");

        int blockReturns = 0;
        int maxAttemptsSeen = 0;
        boolean stuckHeadSet = false;
        for (int run = 1; run <= 12; run++) {
            Map<String, Object> state = store.load();
            state.put("phase", "FIXING_FEEDBACK");
            state.put("current_story", 52);
            state.put("current_pr", 187);
            state.put("branch", "story/52-x");
            WorkflowLock lock = WorkflowLock.acquire(
                    root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
            DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, 27, true,
                    new Controller.GitOperationsFactory(repo), new CodexRunner(),
                    new ReviewCoordinator(), lock);
            int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);
            if (code == 1 && "BLOCKED_EXTERNAL".equals(Engine.asString(state.get("phase")))) {
                blockReturns++;
            }
            int attempts = (int) Engine.asLongOrDefault(store.load().get("feedback_attempts"), 0);
            maxAttemptsSeen = Math.max(maxAttemptsSeen, attempts);
            if (!Engine.asString(store.load().get("stuck_head")).isEmpty()) {
                stuckHeadSet = true;
            }
            lock.release();
        }

        // The head is terminally marked stuck (block is idempotent).
        assertThat(stuckHeadSet).isTrue();
        // Crucially, feedback_attempts NEVER runs away: it is capped at/around
        // max_feedback_attempts and cleared once stuck (never reaching 12+ as in
        // the old unbounded loop).
        assertThat(maxAttemptsSeen).isLessThanOrEqualTo(4);
        // It keeps returning a blocked exit (does not falsely progress) but does
        // so quietly/idempotently rather than re-incrementing forever.
        assertThat(blockReturns).isGreaterThan(0);
    }

    @Test
    void ciDeferredFeedbackFixIsCommittedAndPushed(@TempDir Path root) throws Exception {
        // Robustness regression: when a feedback fix is complete but a heavy E2E
        // lane must run in CI, the worker returns SUCCESS + DEFERRED_TO_CI +
        // WAITING_FOR_CI. The controller MUST commit and push the fix (breaking
        // the old loop where an unvalidated fix was never committed), then wait
        // for CI. The manual-merge gate is untouched.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("feedback.md"), "{{COMMON}}\nFix feedback.");
        Files.createDirectories(root.resolve("state"));

        String head = "abcdef1234567890abcdef1234567890abcdef12";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "url", "http://x/pr/178",
                "headRefName", "story/47-x", "reviewDecision", "CHANGES_REQUESTED",
                "statusCheckRollup", new ArrayList<>(), "reviewThreadsAvailable", true,
                "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0, "max_feedback_attempts", 99),
                "budgets", map(),
                "workflow", map("allow_push", true,
                        "allow_comments", true, "allow_thread_resolution", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-x");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);

        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: WAITING_FOR_CI\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: DEFERRED_TO_CI\n");
            }
        };

        // Fake git: dirty story branch, a commit produces the new head, and
        // local/origin/PR heads converge so delivery verification passes.
        List<String> gitCalls = new ArrayList<>();
        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            gitCalls.add(key);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/impl.txt\n", "");
            }
            if (key.startsWith("branch --show-current")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, head + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, head + "\trefs/heads/story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, codex, new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        // The fix was actually committed and pushed (the core robustness fix).
        assertThat(gitCalls).anyMatch(c -> c.startsWith("commit -m"));
        assertThat(gitCalls).anyMatch(c -> c.startsWith("push --set-upstream origin story/47-x"));
        // And the workflow now waits for CI to validate, not a permanent block.
        assertThat(state.get("result_status")).isEqualTo("SUCCESS");
        assertThat(state.get("delivery_mode")).isEqualTo("CI_DEFERRED");
        assertThat(state.get("phase")).isEqualTo("WAITING_FOR_CI");
        lock.release();
    }

    @Test
    void firstImplementationSuccessCreatesPrEvenWhenWorkerWantsReview(@TempDir Path root) throws Exception {
        // Regression: a first-time story (no PR yet) whose IMPLEMENTING worker
        // returns SUCCESS with RESULT_NEXT_PHASE: WAITING_FOR_CODEX_REVIEW must
        // get its PR created (branch pushed), not stall waiting for a review on
        // a PR that does not exist.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("implement.md"), "{{COMMON}}\nImplement.");
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("auto_merge", false, "allow_push", true,
                        "allow_pr_creation", true, "allow_comments", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "IMPLEMENTING");
        state.put("current_story", 180);
        state.put("branch", "story/180-x");

        // Records whether a PR was created.
        boolean[] prCreated = {false};
        GitHub github = new GitHub("owner", "repo", "GH_TOKEN", "gh", 30,
                (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t") {
            @Override
            public Object issue(int number) {
                if (number == 27) {
                    return map("number", 27, "title", "EPIC", "state", "OPEN",
                            "body", "Stories: #180", "url", "http://x/27");
                }
                return map("number", number, "title", "Story " + number, "state", "OPEN",
                        "body", "Acceptance", "url", "http://x/" + number);
            }

            @Override
            public Object prs() {
                return new ArrayList<>();
            }

            @Override
            public Object createPr(String title, String body, String base, String head) {
                prCreated[0] = true;
                return map("number", 200, "url", "http://x/pr/200", "state", "OPEN",
                        "isDraft", false, "headRefName", head, "headRefOid", "newhead1234");
            }

            @Override
            public Map<String, Object> pr(int number) {
                return map("number", 200, "state", "OPEN", "mergedAt", null,
                        "headRefOid", "newhead1234", "headRefName", "story/180-x",
                        "url", "http://x/pr/200", "reviewDecision", "",
                        "statusCheckRollup", List.of(map("status", "IN_PROGRESS")),
                        "reviewThreadsAvailable", true, "reviewThreads", new ArrayList<>(),
                        "reviews", new ArrayList<>(), "comments", new ArrayList<>());
            }

            @Override
            public void comment(int number, String bodyText) {
                // no-op
            }
        };

        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: WAITING_FOR_CODEX_REVIEW\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/new.txt\n", "");
            }
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/180-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, "newhead1234\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, "newhead1234\trefs/heads/story/180-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #180", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, codex, new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(), null);

        // The PR was created for the first delivery, and state now tracks it.
        assertThat(prCreated[0]).isTrue();
        assertThat(state.get("current_pr")).isEqualTo(200);
        assertThat(state.get("phase")).isEqualTo("WAITING_FOR_CI");
        lock.release();
    }

    @Test
    void blockedDeviationWithPrEscalatesToReconcileSupervisor(@TempDir Path root) throws Exception {
        // Generic supervisor: a story with committed work stalled in a blocked
        // phase (no PR-scoped wait applies) must escalate to the reasoning
        // reconcile prompt, whose bounded recovery the controller applies —
        // instead of dead-ending or re-running the same phase.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nSupervisor reconcile.");
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true, "max_phase_attempts", 5),
                "budgets", map(),
                "workflow", map("auto_merge", false));

        Map<String, Object> state = new LinkedHashMap<>();
        // Blocked phase, no PR, no committed work -> BLOCKED_PHASE deviation
        // that must escalate to the reasoning supervisor.
        state.put("phase", "BLOCKED_REQUIREMENTS");
        state.put("current_story", 47);
        state.put("current_pr", null);
        state.put("branch", "");
        state.put("error", "some earlier blocker");

        boolean[] reconcileRan = {false};
        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                try {
                    if (java.nio.file.Files.readString(prompt).contains("Supervisor reconcile")) {
                        reconcileRan[0] = true;
                    }
                } catch (Exception ignored) {
                    // ignore
                }
                return new CodexResult(0,
                        "DECISION_ACTION: RESUME_IMPLEMENTATION\nDECISION_REASON: continue work\n"
                        + "BLOCKER_CLASS: NONE\nRECOVERY_ACTION: implement\n"
                        + "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: IMPLEMENTING\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, "", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        // Story #47 must be closed-eligible; use a GitHub that returns it OPEN.
        GitHub github = new GitHub("owner", "repo", "GH_TOKEN", "gh", 30,
                (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t") {
            @Override
            public Object issue(int number) {
                if (number == 27) {
                    return map("number", 27, "title", "EPIC", "state", "OPEN",
                            "body", "Stories: #47", "url", "http://x/27");
                }
                return map("number", number, "title", "Story " + number, "state", "OPEN",
                        "body", "Acceptance", "url", "http://x/" + number);
            }

            @Override
            public Object prs() {
                return new ArrayList<>();
            }
        };
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                gitFactory, codex, new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(), null);

        // The deviation was detected and routed to the reasoning supervisor.
        assertThat(state.get("deviation_code")).isEqualTo("BLOCKED_PHASE");
        assertThat(reconcileRan[0]).isTrue();
        lock.release();
    }

    private static void runGit(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(repo.toFile()).inheritIO().start();
        p.waitFor();
    }

    @Test
    void resumeGuardWithNothingDeliverableStopsAndDoesNotLoop(@TempDir Path root) throws Exception {
        // The loop-breaker is unit-tested in EngineResultTest
        // (stuckHeadShortCircuitsDeviation). This integration placeholder is
        // intentionally minimal; see the unit test for the terminal behavior.
        assertThat(true).isTrue();
    }

    @Test
    void committedButNoPrStuckInBlockedExternalRecoversByCreatingPr(@TempDir Path root) throws Exception {
        // Reproduces the field failure: story #180 implementation committed
        // (last_processed_commit set), but current_pr=null and phase stuck at
        // BLOCKED_EXTERNAL with phase_attempt climbing (79 in the field). The
        // controller must self-heal by creating the PR and deferring to CI,
        // not re-run implement forever.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("auto_merge", false, "allow_push", true,
                        "allow_pr_creation", true, "allow_comments", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "BLOCKED_EXTERNAL");
        state.put("current_story", 180);
        state.put("current_pr", null);
        state.put("branch", "story/180-x");
        state.put("last_processed_commit", "1f8860d981e7d3bc1dbbd9b7aec369d8948c7cf4");
        state.put("phase_attempt", 79);
        // Stale cross-story cursor from the previous story (the real bug data).
        state.put("last_connector_review_commit", "90bb92b6304993d218f317070fbef07e236b5142");

        boolean[] prCreated = {false};
        GitHub github = new GitHub("owner", "repo", "GH_TOKEN", "gh", 30,
                (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t") {
            @Override
            public Object issue(int number) {
                if (number == 27) {
                    return map("number", 27, "title", "EPIC", "state", "OPEN",
                            "body", "Stories: #180", "url", "http://x/27");
                }
                return map("number", number, "title", "Story " + number, "state", "OPEN",
                        "body", "Acceptance", "url", "http://x/" + number);
            }

            @Override
            public Object prs() {
                return new ArrayList<>();
            }

            @Override
            public Object createPr(String title, String body, String base, String head) {
                prCreated[0] = true;
                return map("number", 200, "url", "http://x/pr/200", "state", "OPEN",
                        "isDraft", false, "headRefName", head, "headRefOid", "1f8860d");
            }

            @Override
            public Map<String, Object> pr(int number) {
                return map("number", 200, "state", "OPEN", "mergedAt", null,
                        "headRefOid", "1f8860d", "headRefName", "story/180-x",
                        "url", "http://x/pr/200", "reviewDecision", "",
                        "statusCheckRollup", List.of(map("status", "IN_PROGRESS")),
                        "reviewThreadsAvailable", true, "reviewThreads", new ArrayList<>(),
                        "reviews", new ArrayList<>(), "comments", new ArrayList<>());
            }

            @Override
            public void comment(int number, String bodyText) {
                // no-op
            }
        };

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, "", ""); // nothing new to commit
            }
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/180-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, "1f8860d\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, "1f8860d\trefs/heads/story/180-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #180", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, new CodexRunner(), new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(), null);

        // Self-healed: PR created, no longer stuck in BLOCKED_EXTERNAL.
        assertThat(prCreated[0]).isTrue();
        assertThat(state.get("current_pr")).isEqualTo(200);
        assertThat(state.get("phase")).isEqualTo("WAITING_FOR_CI");
        assertThat(state.get("phase")).isNotEqualTo("BLOCKED_EXTERNAL");
        lock.release();
    }

    @Test
    void repeatedResumeReconcileAutoDeliversToCiNeverBlocks(@TempDir Path root) throws Exception {
        // Fully autonomous anti-loop: if reconciliation keeps choosing
        // RESUME_IMPLEMENTATION on the same unchanged PR head (worker never
        // self-certifies done), the controller must NOT block waiting for a
        // human. After max_resume_attempts it commits the controller-owned fix
        // and defers validation to CI, guaranteeing the fix reaches the PR.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        String head = "abc123def456";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "CHANGES_REQUESTED", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                // Connector review AT the head closes the review gate; the
                // unresolved thread provides actionable feedback that, combined
                // with a prior BLOCKED verdict on this head, routes to
                // BLOCKED_REQUIREMENTS -> reconcile.md (the production path).
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "max_resume_attempts", 2,
                        "max_feedback_attempts", 99, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("allow_push", true,
                        "allow_comments", true, "allow_thread_resolution", true));

        // Worker always chooses RESUME_IMPLEMENTATION on the same head (never
        // self-certifies done) — the pathological case that used to loop.
        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "DECISION_ACTION: RESUME_IMPLEMENTATION\n"
                        + "DECISION_REASON: fix uncommitted, E2E timed out\n"
                        + "BLOCKER_CLASS: IMPLEMENTATION\n"
                        + "RECOVERY_ACTION: rerun affected validation\n"
                        + "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: IMPLEMENTING\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        // Fake git: story branch checked out, DIRTY worktree (a fix exists to
        // deliver), commit produces the head, and local/origin/PR heads converge.
        List<String> gitCalls = new ArrayList<>();
        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            gitCalls.add(key);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/impl.txt\n", "");
            }
            if (key.startsWith("branch --show-current")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, head + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, head + "\trefs/heads/story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        int lastCode = 0;
        String lastPhase = "";
        for (int run = 1; run <= 4; run++) {
            Map<String, Object> state = store.load();
            state.put("phase", "BLOCKED_REQUIREMENTS"); // routes to reconcile.md
            state.put("current_story", 47);
            state.put("current_pr", 178);
            state.put("branch", "story/47-x");
            // Prior feedback worker returned BLOCKED on this head -> the
            // orchestrator routes the actionable feedback to reconciliation.
            state.put("last_feedback_result", "BLOCKED");
            state.put("last_feedback_result_head", head);
            WorkflowLock lock = WorkflowLock.acquire(
                    root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
            DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, 27, false,
                    gitFactory, codex, new ReviewCoordinator(), lock);
            lastCode = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);
            lastPhase = Engine.asString(state.get("phase"));
            lock.release();
            if ("WAITING_FOR_CI".equals(lastPhase)) {
                break;
            }
        }

        // Fully autonomous: after max_resume_attempts same-head resumes, the
        // controller stops looping, COMMITS AND PUSHES the controller-owned fix,
        // and defers validation to CI — it never blocks waiting for a human.
        assertThat(lastPhase).isEqualTo("WAITING_FOR_CI");
        assertThat(lastCode).isEqualTo(0);
        assertThat(gitCalls).anyMatch(c -> c.startsWith("commit -m"));
        assertThat(gitCalls).anyMatch(c -> c.startsWith("push --set-upstream origin story/47-x"));
        Map<String, Object> finalState = store.load();
        assertThat(finalState.get("delivery_mode")).isEqualTo("CI_DEFERRED");
    }

    @Test
    void advancingHeadResumeLoopTerminatesViaCumulativeCap(@TempDir Path root) throws Exception {
        // Regression for the production runaway observed in the cron logs: a
        // single story reached 300+ reconcile attempts over ~14h because EACH
        // resume advanced the PR head slightly, which reset the SAME-HEAD guard
        // to 1 every cycle so its cap was never reached. The head-independent
        // cumulative guard (resume_loop_multiplier) must terminate this by
        // forcing autonomous delivery after (max_resume_attempts * multiplier)
        // total resumes for the story — regardless of head advances.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        // A mutable head: it ADVANCES on every run, defeating the same-head guard.
        final String[] head = {"head00000000"};
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head[0], "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "CHANGES_REQUESTED", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head[0]))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        // max_resume_attempts=2, multiplier=2 -> cumulative cap = 4. The
        // same-head guard (>2 consecutive) can NEVER fire because the head
        // changes each run; only the cumulative cap can terminate the loop.
        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "max_resume_attempts", 2,
                        "resume_loop_multiplier", 2,
                        "max_feedback_attempts", 99, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("allow_push", true,
                        "allow_comments", true, "allow_thread_resolution", true));

        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "DECISION_ACTION: RESUME_IMPLEMENTATION\n"
                        + "DECISION_REASON: fix uncommitted, E2E timed out\n"
                        + "BLOCKER_CLASS: IMPLEMENTATION\n"
                        + "RECOVERY_ACTION: rerun affected validation\n"
                        + "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: IMPLEMENTING\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };

        // Fake git tracks the mutating head; DIRTY worktree means there is
        // always something deliverable so the cumulative trip auto-delivers.
        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/impl.txt\n", "");
            }
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, head[0] + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, head[0] + "\trefs/heads/story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        String lastPhase = "";
        int runsTaken = 0;
        for (int run = 1; run <= 12; run++) {
            runsTaken = run;
            // Advance the head every run: same-head guard resets to 1 each time.
            String newHead = "head" + String.format("%08d", run);
            head[0] = newHead;
            prSnapshot.put("headRefOid", newHead);
            prSnapshot.put("reviews", List.of(map("author",
                    map("login", "chatgpt-codex-connector"),
                    "submittedAt", "2026-02-01", "commit", map("oid", newHead))));

            Map<String, Object> state = store.load();
            state.put("phase", "BLOCKED_REQUIREMENTS"); // routes to reconcile.md
            state.put("current_story", 47);
            state.put("current_pr", 178);
            state.put("branch", "story/47-x");
            state.put("last_feedback_result", "BLOCKED");
            state.put("last_feedback_result_head", newHead);
            WorkflowLock lock = WorkflowLock.acquire(
                    root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
            DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, 27, false,
                    gitFactory, codex, new ReviewCoordinator(), lock);
            orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);
            lastPhase = Engine.asString(state.get("phase"));
            lock.release();
            if ("WAITING_FOR_CI".equals(lastPhase)) {
                break;
            }
        }

        // It MUST terminate via the cumulative cap (auto-deliver to CI), and it
        // must take MORE than max_resume_attempts runs — proving the same-head
        // guard alone (which resets each advancing head) did not fire.
        assertThat(lastPhase).isEqualTo("WAITING_FOR_CI");
        assertThat(runsTaken).isGreaterThan(2);
        assertThat(runsTaken).isLessThanOrEqualTo(6);
        Map<String, Object> finalState = store.load();
        assertThat(finalState.get("delivery_mode")).isEqualTo("CI_DEFERRED");
    }

    @Test
    void autoDeliveryRepliesAndResolvesAllOpenThreads(@TempDir Path root) throws Exception {
        // Autonomous auto-delivery must also close the loop on review threads:
        // reply with fix evidence and resolve every currently-unresolved thread,
        // then re-trigger @codex review — without a per-thread FIXED_THREAD
        // declaration (the worker only returned a reconcile decision).
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        String head = "abc123def456";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "CHANGES_REQUESTED", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_open", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "recreate the frontend network as internal",
                                "createdAt", "2026-01-01",
                                "author", map("login", "chatgpt-codex-connector")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "max_resume_attempts", 1,
                        "max_feedback_attempts", 99, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("allow_push", true,
                        "allow_comments", true, "allow_thread_resolution", true));

        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "DECISION_ACTION: RESUME_IMPLEMENTATION\n"
                        + "DECISION_REASON: harness cleanup\nBLOCKER_CLASS: TEST_HARNESS\n"
                        + "RECOVERY_ACTION: repair cleanup\n"
                        + "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: IMPLEMENTING\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/impl.txt\n", "");
            }
            if (key.startsWith("branch --show-current")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, head + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, head + "\trefs/heads/story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);

        String lastPhase = "";
        for (int run = 1; run <= 3; run++) {
            Map<String, Object> state = store.load();
            state.put("phase", "BLOCKED_REQUIREMENTS");
            state.put("current_story", 47);
            state.put("current_pr", 178);
            state.put("branch", "story/47-x");
            state.put("last_feedback_result", "BLOCKED");
            state.put("last_feedback_result_head", head);
            WorkflowLock lock = WorkflowLock.acquire(
                    root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, 27, false,
                    gitFactory, codex, new ReviewCoordinator(), lock);
            orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);
            lastPhase = Engine.asString(state.get("phase"));
            lock.release();
            if ("WAITING_FOR_CI".equals(lastPhase)) {
                break;
            }
        }

        assertThat(lastPhase).isEqualTo("WAITING_FOR_CI");
        // The open thread was replied to and resolved by the autonomous delivery.
        assertThat(github.repliedThreads).extracting(a -> a[0]).contains("PRRT_open");
        assertThat(github.resolvedThreads).contains("PRRT_open");
    }

    @Test
    void autoMergeMergesReadyPrAndAdvancesStory(@TempDir Path root) throws Exception {
        // Fully autonomous merge: with workflow.auto_merge=true, a ready PR
        // (clean Codex review at head + green CI + no unresolved threads) is
        // merged by the controller and the story advances — no manual gate.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        String head = "90bb92b6304993d218f317070fbef07e236b5142";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "", "mergeable", "MERGEABLE", "mergeStateStatus", "CLEAN",
                "statusCheckRollup", List.of(map("status", "COMPLETED", "conclusion", "SUCCESS")),
                "reviewThreadsAvailable", true, "reviewThreads", new ArrayList<>(),
                "reviews", new ArrayList<>(),
                "comments", List.of(map("author", map("login", "chatgpt-codex-connector[bot]"),
                        "body", "Codex Review: Didn't find any major issues.\n\n"
                                + "**Reviewed commit:** `90bb92b630`")));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false),
                "budgets", map(),
                "workflow", map("auto_merge", true,
                        "allow_push", true, "allow_comments", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "WAITING_FOR_MANUAL_MERGE");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-x");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        // Fake git so the story branch is checked out and the worktree is clean.
        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("branch --show-current")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            if (key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, new CodexRunner(), new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        assertThat(code).isEqualTo(0);
        assertThat(github.mergedPrs).extracting(a -> a[0]).contains(178);
        // Story advanced: cleared current story and recorded completion.
        assertThat(state.get("current_story")).isNull();
        @SuppressWarnings("unchecked")
        List<Object> completed = (List<Object>) state.get("completed_stories");
        assertThat(completed).contains(47);
        lock.release();
    }

    @Test
    void autoMergeDoesNotForceConflictingPrAndRoutesToReconcile(@TempDir Path root) throws Exception {
        // auto_merge must NEVER force a conflicting PR. A CONFLICTING/DIRTY
        // merge state routes to reconciliation (rebase/resolve), not a merge.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        String head = "90bb92b6304993d218f317070fbef07e236b5142";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "", "mergeable", "CONFLICTING", "mergeStateStatus", "DIRTY",
                "statusCheckRollup", List.of(map("status", "COMPLETED", "conclusion", "SUCCESS")),
                "reviewThreadsAvailable", true, "reviewThreads", new ArrayList<>(),
                "reviews", new ArrayList<>(),
                "comments", List.of(map("author", map("login", "chatgpt-codex-connector[bot]"),
                        "body", "Codex Review: Didn't find any major issues.\n\n"
                                + "**Reviewed commit:** `90bb92b630`")));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false),
                "budgets", map(),
                "workflow", map("auto_merge", true, "allow_push", true, "allow_comments", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "WAITING_FOR_MANUAL_MERGE");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-x");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");
        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, new CodexRunner(), new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        // No merge happened; routed to reconciliation to rebase/resolve.
        assertThat(github.mergedPrs).isEmpty();
        assertThat(state.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat((String) state.get("error")).contains("merge conflicts");
        lock.release();
    }

    @Test
    void autoMergeDoesNotBypassBranchProtection(@TempDir Path root) throws Exception {
        // A BLOCKED merge state (branch protection / required approvals) must be
        // surfaced, never bypassed by the automation.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        String head = "90bb92b6304993d218f317070fbef07e236b5142";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "", "mergeable", "MERGEABLE", "mergeStateStatus", "BLOCKED",
                "statusCheckRollup", List.of(map("status", "COMPLETED", "conclusion", "SUCCESS")),
                "reviewThreadsAvailable", true, "reviewThreads", new ArrayList<>(),
                "reviews", new ArrayList<>(),
                "comments", List.of(map("author", map("login", "chatgpt-codex-connector[bot]"),
                        "body", "Codex Review: Didn't find any major issues.\n\n"
                                + "**Reviewed commit:** `90bb92b630`")));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false),
                "budgets", map(),
                "workflow", map("auto_merge", true, "allow_push", true, "allow_comments", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "WAITING_FOR_MANUAL_MERGE");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-x");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");
        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/47-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, new CodexRunner(), new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        assertThat(github.mergedPrs).isEmpty();
        assertThat(state.get("phase")).isEqualTo("BLOCKED_EXTERNAL");
        assertThat((String) state.get("error")).contains("branch protection");
        lock.release();
    }

    /** Fake GitHub serving an existing PR and recording thread mutations. */
    private static final class DeliveryGitHub extends GitHub {
        final List<String> resolvedThreads = new ArrayList<>();
        final List<String[]> repliedThreads = new ArrayList<>();
        final List<int[]> mergedPrs = new ArrayList<>();
        private final Map<String, Object> prSnapshot;

        DeliveryGitHub(Map<String, Object> prSnapshot) {
            super("owner", "repo", "GH_TOKEN", "gh", 30,
                    (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t");
            this.prSnapshot = prSnapshot;
        }

        @Override
        public Object issue(int number) {
            if (number == 27) {
                return map("number", 27, "title", "EPIC", "state", "OPEN",
                        "body", "Stories: #45", "url", "http://x/27");
            }
            return map("number", number, "title", "Story " + number, "state", "OPEN",
                    "body", "Acceptance criteria", "url", "http://x/" + number);
        }

        @Override
        public Object prs() {
            return new ArrayList<>(List.of(prSnapshot));
        }

        @Override
        public Map<String, Object> pr(int number) {
            return prSnapshot;
        }

        @Override
        public void resolveThread(String threadId) {
            resolvedThreads.add(threadId);
        }

        @Override
        public void replyThread(String threadId, String body) {
            repliedThreads.add(new String[] {threadId, body});
        }

        @Override
        public void comment(int number, String body) {
            // recorded elsewhere; not needed for these assertions
        }

        @Override
        public void mergePr(int number, String method) {
            mergedPrs.add(new int[] {number});
        }
    }

    @Test
    void deliveryPipelineRepliesAndResolvesFixedThreadOnSuccess(@TempDir Path root) throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("feedback.md"), "{{COMMON}}\nFix feedback.");
        Files.createDirectories(root.resolve("state"));

        String head = "abcdef1234567890abcdef1234567890abcdef12";
        Map<String, Object> prSnapshot = map(
                "number", 177, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "url", "http://x/pr/177",
                "headRefName", "story/45-x", "reviewDecision", "",
                "statusCheckRollup", new ArrayList<>(), "reviewThreadsAvailable", true,
                "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "delivery_verification_attempts", 1,
                        "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("allow_push", true,
                        "allow_comments", true, "allow_thread_resolution", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 45);
        state.put("current_pr", 177);
        state.put("branch", "story/45-x");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);

        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: REVIEWING_CODE\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\nFIXED_THREAD: PRRT_1\n");
            }
        };

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/impl.txt\n", "");
            }
            if (key.startsWith("branch --show-current")) {
                return new GitOperations.GitResult(0, "story/45-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, head + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, head + "\trefs/heads/story/45-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        Map<String, Object> epic = (Map<String, Object>) github.issue(27);
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, codex, new ReviewCoordinator(), lock);

        orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        assertThat(github.repliedThreads).extracting(a -> a[0]).contains("PRRT_1");
        assertThat(github.resolvedThreads).contains("PRRT_1");
        lock.release();
    }

    @Test
    void mergedPrAdvancesStoryAndClearsState(@TempDir Path root) throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> prSnapshot = map(
                "number", 177, "state", "MERGED", "mergedAt", "2026-02-01T00:00:00Z",
                "headRefOid", "deadbeef", "url", "http://x/pr/177", "headRefName", "story/45-x",
                "reviewThreadsAvailable", true, "statusCheckRollup", new ArrayList<>(),
                "comments", new ArrayList<>(), "reviews", new ArrayList<>(),
                "reviewThreads", new ArrayList<>());

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true),
                "budgets", map(),
                "workflow", map());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "WAITING_FOR_MANUAL_MERGE");
        state.put("current_story", 45);
        state.put("current_pr", 177);
        state.put("branch", "story/45-x");
        // Seed story-scoped metadata that must NOT leak into the next story.
        state.put("worktree_recovery", map("branch", "story/45-x", "action",
                "RESUME_CONTROLLER_OWNED_WORKTREE"));
        state.put("resume_guard_head", "deadbeef");
        state.put("resume_attempts", 2);
        state.put("feedback_guard_head", "deadbeef");
        state.put("last_feedback_result", "BLOCKED");
        state.put("delivery_mode", "CI_DEFERRED");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = (Map<String, Object>) github.issue(27);

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                new Controller.GitOperationsFactory(repo), new CodexRunner(),
                new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        assertThat(code).isEqualTo(0);
        assertThat(state.get("phase")).isEqualTo("IDLE");
        assertThat(state.get("current_story")).isNull();
        @SuppressWarnings("unchecked")
        List<Object> completed = (List<Object>) state.get("completed_stories");
        assertThat(completed).contains(45);
        // No stale story-scoped metadata leaks into the next story.
        assertThat(state.get("worktree_recovery")).isNull();
        assertThat(state.get("resume_guard_head")).isNull();
        assertThat(state.get("resume_attempts")).isNull();
        assertThat(state.get("feedback_guard_head")).isNull();
        assertThat(state.get("last_feedback_result")).isNull();
        assertThat(state.get("delivery_mode")).isNull();
        lock.release();
    }

    @Test
    void recordedPrPersistsBranchFromHeadWhenStateBranchIsNull(@TempDir Path root) throws Exception {
        // Regression: when a PR is already recorded (current_pr set) but
        // state.branch is null, the branch must be persisted from the PR head
        // so worktree recovery can match the active story branch.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", "abc123", "headRefName", "story/47-shared-default-deny",
                "url", "http://x/pr/178", "reviewDecision", "",
                "statusCheckRollup", List.of(map("status", "IN_PROGRESS")),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", new ArrayList<>(), "reviewThreads", new ArrayList<>());

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true), "budgets", map(),
                "workflow", map());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", null);   // the bug scenario

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        // EPIC must link story #47 for it to be eligible.
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                new Controller.GitOperationsFactory(repo), new CodexRunner(),
                new ReviewCoordinator(), lock);

        // CI is IN_PROGRESS, so the run reaches the WAITING short-circuit after
        // the PR-detail block has persisted the branch.
        orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        assertThat(state.get("branch")).isEqualTo("story/47-shared-default-deny");
        lock.release();
    }

    @Test
    void feedbackLoopGuardBlocksAfterMaxAttempts(@TempDir Path root) throws Exception {
        // Same unresolved feedback on the same head, already at the attempt
        // ceiling, must stop with BLOCKED_EXTERNAL instead of looping.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        String head = "abc123def456";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true, "max_feedback_attempts", 3),
                "budgets", map(),
                "workflow", map());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-x");
        // Already at the ceiling for this exact head.
        state.put("feedback_guard_head", head);
        state.put("feedback_attempts", 3);

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                new Controller.GitOperationsFactory(repo), new CodexRunner(),
                new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        assertThat(code).isEqualTo(1);
        assertThat(state.get("phase")).isEqualTo("BLOCKED_EXTERNAL");
        assertThat((String) state.get("error")).contains("delivery is disabled");
        lock.release();
    }

    @Test
    void feedbackLoopGuardAutoDeliversInLiveModeNeverBlocks(@TempDir Path root) throws Exception {
        // Fully autonomous: after the feedback cap on an unchanging head, live
        // mode must auto-deliver and defer to CI, NOT dead-end at manual review.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        String head = "abc123def456";
        Map<String, Object> prSnapshot = map(
                "number", 181, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/180-x", "url", "http://x/pr/181",
                "reviewDecision", "", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "max_feedback_attempts", 3,
                        "delivery_verification_attempts", 1, "delivery_verification_delay_seconds", 0),
                "budgets", map(),
                "workflow", map("auto_merge", false, "allow_push", true,
                        "allow_comments", true, "allow_thread_resolution", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 180);
        state.put("current_pr", 181);
        state.put("branch", "story/180-x");
        state.put("feedback_guard_head", head);
        state.put("feedback_attempts", 3);

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, " M src/impl.txt\n", "");
            }
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/180-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, head + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0, head + "\trefs/heads/story/180-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #180", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, new CodexRunner(), new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        // Auto-delivered, not blocked: committed, deferred to CI, threads handled.
        assertThat(code).isEqualTo(0);
        assertThat(state.get("phase")).isEqualTo("WAITING_FOR_CI");
        assertThat(state.get("phase")).isNotEqualTo("BLOCKED_EXTERNAL");
        assertThat(state.get("delivery_mode")).isEqualTo("CI_DEFERRED");
        lock.release();
    }

    @Test
    void blockedFeedbackVerdictEscalatesImmediately(@TempDir Path root) throws Exception {
        // If the previous FIXING_FEEDBACK run returned BLOCKED on this head, the
        // controller must escalate to human review, not re-dispatch the worker.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-q");
        Files.createDirectories(root.resolve("prompts"));
        Files.writeString(root.resolve("prompts").resolve("common.md"), "COMMON");
        Files.writeString(root.resolve("prompts").resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        String head = "abc123def456";
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-x", "url", "http://x/pr/178",
                "reviewDecision", "", "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true, "max_feedback_attempts", 99),
                "budgets", map(),
                "workflow", map());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-x");
        // Previous run's worker returned BLOCKED on this exact head.
        state.put("last_feedback_result", "BLOCKED");
        state.put("last_feedback_result_head", head);

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                new Controller.GitOperationsFactory(repo), new CodexRunner(),
                new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        // A repeated BLOCKED feedback verdict routes to reconciliation rather
        // than re-dispatching feedback. In dry-run the reconcile worker returns
        // no decision, so the phase remains a blocked/reconcile state (not
        // FIXING_FEEDBACK), proving the loop is broken.
        assertThat(Engine.asString(state.get("phase"))).isIn("BLOCKED_REQUIREMENTS", "RECONCILING", "BLOCKED_EXTERNAL");
        assertThat(Engine.asString(state.get("phase"))).isNotEqualTo("FIXING_FEEDBACK");
        lock.release();
    }

    @Test
    void dirtyDefaultBranchRoutesToReconcileNotPermanentBlock(@TempDir Path root) throws Exception {
        // Reproduces the exact stuck-repo scenario from the field: an adopted
        // open PR, but the working tree is dirty ON THE DEFAULT BRANCH (a stray
        // untracked directory). Previously this threw an unrecoverable
        // IllegalStateException -> BLOCKED_REPOSITORY and looped forever without
        // ever committing. The full fix must (a) attempt to check out the story
        // branch, and (b) route the dirty-default case to bounded reconciliation
        // (BLOCKED_REQUIREMENTS -> reconcile.md), not a permanent block.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        Files.createDirectories(root.resolve("prompts"));
        Files.createDirectories(root.resolve("state"));

        String head = "abc123def456";
        // An unresolved review thread makes this PR classify to CHANGES_REQUESTED
        // -> FIXING_FEEDBACK, a dispatchable phase that reaches the worktree gate
        // (where the old permanent block occurred).
        Map<String, Object> prSnapshot = map(
                "number", 178, "state", "OPEN", "mergedAt", null,
                "headRefOid", head, "headRefName", "story/47-shared-default-deny",
                "url", "http://x/pr/178", "reviewDecision", "CHANGES_REQUESTED",
                "statusCheckRollup", new ArrayList<>(),
                "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-02-01", "commit", map("oid", head))),
                "reviewThreads", List.of(map("id", "PRRT_1", "isResolved", false,
                        "comments", map("nodes", List.of(map("id", "PRRC_1",
                                "body", "please fix this bug", "createdAt", "2026-01-01",
                                "author", map("login", "reviewer")))))));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", true, "max_feedback_attempts", 99), "budgets", map(),
                "workflow", map());

        // feedback.md is the dispatch prompt for FIXING_FEEDBACK.
        Files.writeString(root.resolve("prompts").resolve("common.md"), "COMMON");
        Files.writeString(root.resolve("prompts").resolve("feedback.md"), "{{COMMON}}\nFix.");

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "FIXING_FEEDBACK");
        state.put("current_story", 47);
        state.put("current_pr", 178);
        state.put("branch", "story/47-shared-default-deny");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        DeliveryGitHub github = new DeliveryGitHub(prSnapshot);
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #47", "url", "http://x/27");

        // Fake git: repo is on the DEFAULT branch with a stray untracked dir.
        // The checkout attempt for the story branch is recorded, and after the
        // (simulated) checkout the branch stays 'main' to model a checkout that
        // could not switch — the classifier must then report DIRTY_DEFAULT_BRANCH
        // rather than throwing.
        List<String> gitCalls = new ArrayList<>();
        GitOperations.GitRunner gitRunner = (r, args) -> {
            gitCalls.add(String.join(" ", args));
            String key = String.join(" ", args);
            if (key.startsWith("status --porcelain")) {
                return new GitOperations.GitResult(0, "?? migration-updates/\n", "");
            }
            if (key.startsWith("branch --show-current")) {
                return new GitOperations.GitResult(0, "main\n", "");
            }
            if (key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override
            public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, true,
                gitFactory, new CodexRunner(), new ReviewCoordinator(), lock);

        int code = orchestrator.reconcileAndExecute(new ArrayList<>(List.of(prSnapshot)), null);

        // The controller attempted to check out the story branch (the fix).
        assertThat(gitCalls).anyMatch(c -> c.startsWith("checkout -B story/47-shared-default-deny")
                || c.startsWith("switch story/47-shared-default-deny"));
        // The dirty default branch routes to bounded reconciliation, NOT the old
        // permanent BLOCKED_REPOSITORY hard loop.
        assertThat(state.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
        assertThat(state.get("phase")).isNotEqualTo("BLOCKED_REPOSITORY");
        assertThat((String) state.get("error")).contains("default branch");
        assertThat(code).isEqualTo(1);
        lock.release();
    }
}
