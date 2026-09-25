package com.jcoding.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline end-to-end harness (Requirement 7): drives many full controller ticks
 * against scripted fakes (no real gh, git, or codex) and asserts full-loop
 * safety properties that unit tests cannot — specifically the PR #187 field
 * regression, where a PR that legitimately advances its head and resolves a
 * thread every round must NEVER be falsely declared terminal/blocked.
 */
class WorkflowE2EHarnessTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Mutable fake whose PR head advances and a thread resolves each tick. */
    private static class EvolvingGitHub extends GitHub {
        int round = 0;
        final List<String> resolved = new ArrayList<>();

        EvolvingGitHub() {
            super("owner", "repo", "GH_TOKEN", "gh", 30,
                    (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t");
        }

        @Override
        public Object issue(int number) {
            if (number == 27) {
                return map("number", 27, "title", "EPIC", "state", "OPEN",
                        "body", "Stories: #52", "url", "http://x/27");
            }
            return map("number", number, "title", "Story " + number, "state", "OPEN",
                    "body", "Acceptance criteria", "url", "http://x/" + number);
        }

        private Map<String, Object> snapshot() {
            String head = "head" + round;
            // Each round: a fresh head and one more resolved thread => real
            // progress. One unresolved thread remains so the loop keeps working.
            return map(
                    "number", 187, "state", "OPEN", "mergedAt", null,
                    "headRefOid", head, "headRefName", "story/52-x", "url", "http://x/pr/187",
                    "reviewDecision", "CHANGES_REQUESTED",
                    "statusCheckRollup", new ArrayList<>(),
                    "reviewThreadsAvailable", true, "comments", new ArrayList<>(),
                    "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                            "submittedAt", "2026-02-01", "commit", map("oid", head))),
                    "reviewThreads", List.of(map("id", "PRRT_" + round, "isResolved", false,
                            "comments", map("nodes", List.of(map("id", "PRRC_" + round,
                                    "body", "P1 finding", "createdAt", "2026-01-0" + (round % 9),
                                    "author", map("login", "reviewer")))))));
        }

        @Override public Object prs() {
            return new ArrayList<>(List.of(snapshot()));
        }

        @Override public Map<String, Object> pr(int number) {
            return snapshot();
        }

        @Override public void resolveThread(String threadId) {
            resolved.add(threadId);
        }

        @Override public void replyThread(String threadId, String body) { }

        @Override public void comment(int number, String body) { }
    }

    @Test
    void advancingPrAcrossManyTicksNeverFalselyBlocks(@TempDir Path root) throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("feedback.md"), "{{COMMON}}\nFix.");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "max_feedback_attempts", 3,
                        "max_resume_attempts", 3),
                "budgets", map(),
                "workflow", map("allow_push", true, "allow_comments", true,
                        "allow_thread_resolution", true));

        EvolvingGitHub github = new EvolvingGitHub();

        // Worker always resolves the round's thread and returns SUCCESS: healthy
        // progress every tick (head advances via the fake, a thread resolves).
        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0,
                        "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: FIXING_FEEDBACK\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };

        GitOperations.GitRunner gitRunner = (r, args) -> {
            String key = String.join(" ", args);
            if (key.startsWith("branch --show-current") || key.startsWith("branch --list")) {
                return new GitOperations.GitResult(0, "story/52-x\n", "");
            }
            if (key.startsWith("rev-parse HEAD")) {
                return new GitOperations.GitResult(0, "head" + github.round + "\n", "");
            }
            if (key.startsWith("ls-remote origin")) {
                return new GitOperations.GitResult(0,
                        "head" + github.round + "\trefs/heads/story/52-x\n", "");
            }
            return new GitOperations.GitResult(0, "", "");
        };
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };

        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #52", "url", "http://x/27");

        // Drive many ticks; each tick a new StateStore/orchestrator instance
        // reads the persisted durable state, mirroring the real cron model.
        for (int tick = 0; tick < 30; tick++) {
            github.round = tick;
            StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
            Map<String, Object> state = store.load();
            if (tick == 0) {
                state.put("current_story", 52);
                state.put("current_pr", 187);
                state.put("branch", "story/52-x");
                state.put("phase", "FIXING_FEEDBACK");
            }
            WorkflowLock lock = WorkflowLock.acquire(
                    root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, 27, false,
                    gitFactory, codex, new ReviewCoordinator(), lock);
            orchestrator.reconcileAndExecute(new ArrayList<>(List.of(github.snapshot())), null);
            lock.release();

            // Full-loop safety property (PR #187): a genuinely advancing PR must
            // never be parked in a terminal unrecoverable/blocked state.
            Map<String, Object> after = store.load();
            assertThat(Engine.asString(after.get("stuck_head_reason")))
                    .as("tick %d must not mark the advancing head UNRECOVERABLE", tick)
                    .isNotEqualTo("UNRECOVERABLE");
            assertThat(Engine.asString(after.get("phase")))
                    .as("tick %d must not dead-end in a hard block", tick)
                    .isNotIn("BLOCKED_EXTERNAL", "CONFLICT_DETECTED");
        }
    }

    @Test
    void terminalStoryIsParkedAndControllerAdvances(@TempDir Path root) throws Exception {
        // R7 stall/terminal/park scenario: a story already marked terminally
        // UNRECOVERABLE on its head, with park-and-advance enabled, must be
        // parked (recorded with head/reason) and the controller must advance to
        // IDLE so the next tick selects a different story — never dead-end.
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        Path prompts = root.resolve("prompts");
        Files.createDirectories(prompts);
        Files.writeString(prompts.resolve("common.md"), "COMMON");
        Files.writeString(prompts.resolve("feedback.md"), "{{COMMON}}\nFix.");
        Files.writeString(prompts.resolve("reconcile.md"), "{{COMMON}}\nReconcile.");
        Files.createDirectories(root.resolve("state"));

        Map<String, Object> config = map(
                "repository", map("local_path", repo.toString(), "owner", "owner",
                        "name", "repo", "base_branch", "main"),
                "epic", map("number", 27),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "automation", map("dry_run", false, "max_feedback_attempts", 3),
                "budgets", map(),
                "workflow", map("allow_push", true, "allow_comments", false,
                        "terminal_policy", "park_and_advance"));

        EvolvingGitHub github = new EvolvingGitHub() {
            @Override public Object prs() {
                return new ArrayList<>(); // no PRs: skip PR-scoped + wait-gate
            }
        };
        github.round = 5;
        CodexRunner codex = new CodexRunner(Map.of(), System::nanoTime) {
            @Override
            public CodexResult run(Map<String, Object> cfg, Path prompt, Path r, boolean dry,
                                   Runnable hb, Integer timeout) {
                return new CodexResult(0, "RESULT_STATUS: SUCCESS\nRESULT_NEXT_PHASE: FIXING_FEEDBACK\n"
                        + "RESULT_BLOCKER: NONE\nRESULT_EVIDENCE: COMPLETE\n");
            }
        };
        GitOperations.GitRunner gitRunner = (r, args) -> new GitOperations.GitResult(0, "", "");
        Controller.GitOperationsFactory gitFactory = new Controller.GitOperationsFactory(repo) {
            @Override public GitOperations create() {
                return new GitOperations(repo, gitRunner);
            }
        };
        Map<String, Object> epic = map("number", 27, "title", "EPIC", "state", "OPEN",
                "body", "Stories: #52", "url", "http://x/27");

        StateStore store = new StateStore(root.resolve("state").resolve("epic-27.json"));
        Map<String, Object> state = store.load();
        state.put("current_story", 52);
        state.put("branch", "story/52-x");
        state.put("phase", "FIXING_FEEDBACK");
        // Head already determined terminally unrecoverable.
        state.put("stuck_head", "head5");
        state.put("stuck_head_reason", "UNRECOVERABLE");

        WorkflowLock lock = WorkflowLock.acquire(
                root.resolve("state").resolve("epic-27.lock"), 900, 3, 10);
        WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                config, state, store, github, repo, root, epic, 27, false,
                gitFactory, codex, new ReviewCoordinator(), lock);
        orchestrator.reconcileAndExecute(new ArrayList<>(), null);
        lock.release();

        Map<String, Object> after = store.load();
        // Story 52 is parked on head5, and the controller advanced to IDLE.
        assertThat(ParkPolicy.parkedStoryNumbers(after)).contains(52);
        assertThat(ParkPolicy.parkedHead(after, 52)).isEqualTo("head5");
        assertThat(Engine.asString(after.get("phase"))).isEqualTo("IDLE");
        assertThat(after.get("current_story")).isNull();
    }
}
