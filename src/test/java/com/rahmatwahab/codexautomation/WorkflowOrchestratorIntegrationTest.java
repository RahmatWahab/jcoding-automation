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
                "workflow", map("require_manual_merge", true, "allow_push", false));

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

    private static void runGit(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(repo.toFile()).inheritIO().start();
        p.waitFor();
    }

    /** Fake GitHub serving an existing PR and recording thread mutations. */
    private static final class DeliveryGitHub extends GitHub {
        final List<String> resolvedThreads = new ArrayList<>();
        final List<String[]> repliedThreads = new ArrayList<>();
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
                "workflow", map("require_manual_merge", true, "allow_push", true,
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
                "workflow", map("require_manual_merge", true));

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "WAITING_FOR_MANUAL_MERGE");
        state.put("current_story", 45);
        state.put("current_pr", 177);
        state.put("branch", "story/45-x");

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
        lock.release();
    }
}
