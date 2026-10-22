package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoding.github.GitHub;
import com.jcoding.state.StateStore;
import com.jcoding.state.WorkflowLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real GitHub orchestrator worker path with only the worker process stubbed. */
class GitHubWorkerOrchestrationMockTest {
    @TempDir Path root;

    @Test void verifiedStoryIsPushedToDisposableRemoteAndDeliveredThroughFakePrReadback() throws Exception {
        Path origin = Files.createDirectories(root.resolve("origin.git"));
        git(origin, "init", "--bare", "-q", "-b", "main");
        Path repo = Files.createDirectories(root.resolve("delivery-repo"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("seed.txt"), "seed");
        git(repo, "add", "seed.txt");
        git(repo, "commit", "-q", "-m", "seed");
        String base = gitOutput(repo, "rev-parse", "HEAD");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "-q", "-u", "origin", "main");
        git(repo, "switch", "-q", "-c", "story/2-example");
        Files.writeString(repo.resolve("result.txt"), "verified artifact");
        String remoteOrigin = gitOutput(repo, "remote", "get-url", "origin");
        assertThat(Path.of(remoteOrigin)).isEqualTo(origin);

        Path stateDir = Files.createDirectories(root.resolve("delivery-state"));
        Path prompts = Files.createDirectories(root.resolve("prompts"));
        Files.writeString(prompts.resolve("implement.md"), "Implement story");
        Map<String, Object> issue = Map.of("number", 2, "title", "Story 2", "body", "Create verified result",
                "url", "http://mock/issues/2");
        Map<String, Object> config = Map.of(
                "repository", Map.of("local_path", repo.toString(), "owner", "local", "name", "fixture",
                        "base_branch", "main"),
                "github", Map.of(),
                "automation", Map.of("dry_run", false, "max_story_active_seconds", 60,
                        "delivery_verification_attempts", 1, "delivery_verification_delay_seconds", 0),
                "budgets", Map.of(), "workflow", Map.of("allow_push", true, "allow_pr_creation", true),
                "codex", Map.of("executable", "unused", "model", "mock", "isolation", "none"),
                "verification", Map.of("required", true, "allowed_paths", List.of("**"),
                        "protected_paths", List.of(), "checks", List.of(Map.of("id", "artifact-check",
                                "command", List.of("mock-check"), "criteria", List.of("AC-1")))));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "WAITING_FOR_CI");
        state.put("execution_id", "mock-delivery");
        state.put("current_story", 2);
        state.put("current_story_issue", issue);
        state.put("branch", "story/2-example");
        state.put("base_commit", base);
        state.put("worker_owned_story", 2);
        state.put("worker_owned_branch", "story/2-example");
        state.put("worker_owned_paths", List.of("result.txt"));
        state.put("goal_contract", Map.of("requirements_hash", "requirements-v1", "criteria",
                List.of(Map.of("id", "AC-1", "text", "result.txt contains verified artifact"))));
        state.put("exit_code", 0);
        state.put("result_status", "SUCCESS");
        state.put("result_evidence", "COMPLETE");
        state.put("blockers", List.of());
        StateStore store = new StateStore(stateDir.resolve("epic-1.json"));
        store.save(state);
        WorkflowLock lock = WorkflowLock.acquire(stateDir.resolve("epic-1.lock"), 1, 3, 10);
        Map<String, Object> fakePr = new LinkedHashMap<>();
        GitHub github = new GitHub("local", "fixture", "GH_TOKEN", "gh", 2,
                (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), key -> null) {
            @Override public Object issue(int number) { return issue; }
            @Override public Object createPr(String title, String body, String branchBase, String head) {
                String remoteHead = gitOutput(repo, "--git-dir", origin.toString(), "rev-parse", "refs/heads/" + head);
                fakePr.putAll(Map.of("number", 77, "url", "http://mock/pull/77", "state", "OPEN",
                        "mergedAt", "", "headRefName", head, "headRefOid", remoteHead));
                return new LinkedHashMap<>(fakePr);
            }
            @Override public Map<String, Object> pr(int number) {
                assertThat(number).isEqualTo(77);
                return new LinkedHashMap<>(fakePr);
            }
        };
        RuntimeVerifier verifier = new RuntimeVerifier((command, checkout, timeout, heartbeat) -> {
            try { return new BoundedProcess.Result(Files.readString(checkout.resolve("result.txt"))
                    .equals("verified artifact") ? 0 : 1, "mock acceptance passed", false); }
            catch (Exception exc) { return new BoundedProcess.Result(1, "artifact missing", false); }
        });
        GitOperations.GitRunner localGit = (directory, args) -> runLocalGit(directory, args);
        Controller.GitOperationsFactory factory = new Controller.GitOperationsFactory(repo) {
            @Override public GitOperations create() { return new GitOperations(repo, localGit); }
        };
        try {
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(config, state, store, github,
                    repo, root, Map.of("number", 1), 1, false, factory,
                    new CodexRunner(Map.of(), System::nanoTime), new ReviewCoordinator(), lock, verifier);
            var gitMethod = WorkflowOrchestrator.class.getDeclaredMethod("git");
            gitMethod.setAccessible(true);
            GitOperations deliveryGit = (GitOperations) gitMethod.invoke(orchestrator);
            var invoke = WorkflowOrchestrator.class.getDeclaredMethod("runDeliveryPipeline",
                    GitOperations.class, Integer.class, Map.class, String.class, List.class);
            invoke.setAccessible(true);
            int result = (int) invoke.invoke(orchestrator, deliveryGit, 2, null,
                    "RESULT_STATUS: SUCCESS\\n", List.of());
            String localHead = gitOutput(repo, "rev-parse", "HEAD");
            String remoteHead = gitOutput(repo, "--git-dir", origin.toString(), "rev-parse", "refs/heads/story/2-example");
            assertThat(result).isEqualTo(Integer.MIN_VALUE);
            assertThat(localHead).isEqualTo(remoteHead);
            assertThat(state).containsEntry("current_pr", 77).containsEntry("current_pr_url", "http://mock/pull/77");
            assertThat(fakePr.get("headRefOid")).isEqualTo(localHead);
            assertThat(((Map<?, ?>) state.get("validation_checkpoint")).get("delivered_head")).isEqualTo(localHead);
        } finally { lock.release(); }
    }

    @Test void githubTaskRepairStopsWhenTheSameCriterionFailureRepeats() throws Exception {
        Path repo = Files.createDirectories(root.resolve("stalled-repo"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("seed.txt"), "seed");
        git(repo, "add", "seed.txt");
        git(repo, "commit", "-q", "-m", "seed");
        Path stateDir = Files.createDirectories(root.resolve("stalled-state"));
        Map<String, Object> config = Map.of(
                "repository", Map.of("local_path", repo.toString(), "owner", "local", "name", "fixture"),
                "github", Map.of(), "automation", Map.of("dry_run", false, "max_story_active_seconds", 60),
                "budgets", Map.of(), "workflow", Map.of(),
                "codex", Map.of("executable", "unused", "model", "mock", "isolation", "none"),
                "verification", Map.of("required", true, "allowed_paths", List.of("**"),
                        "protected_paths", List.of(), "checks", List.of(Map.of("id", "check", "command", List.of("mock"),
                                "criteria", List.of("AC-1")))));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "IMPLEMENTING");
        state.put("execution_id", "mock-stall");
        state.put("current_story", 2);
        state.put("base_commit", gitOutput(repo, "rev-parse", "HEAD"));
        state.put("goal_contract", Map.of("requirements_hash", "requirements-v1", "criteria",
                List.of(Map.of("id", "AC-1", "text", "result exists"))));
        StateStore store = new StateStore(stateDir.resolve("epic-1.json"));
        store.save(state);
        WorkflowLock lock = WorkflowLock.acquire(stateDir.resolve("epic-1.lock"), 1, 3, 10);
        Map<String, Object> task = new LinkedHashMap<>(Map.of("id", "T1", "criteria", List.of("AC-1"),
                "checks", List.of("check"), "attempts", 1, "status", "AWAITING_VERIFICATION"));
        RuntimeVerifier failing = new RuntimeVerifier((command, checkout, timeout, heartbeat) ->
                new BoundedProcess.Result(1, "same criterion failure", false));
        try {
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(config, state, store,
                    new GitHub("local", "fixture", "GH_TOKEN", "gh", 2,
                            (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), key -> null),
                    repo, root, Map.of("number", 1), 1, false,
                    new Controller.GitOperationsFactory(repo), new CodexRunner(Map.of(), System::nanoTime),
                    new ReviewCoordinator(), lock, failing);
            var verify = WorkflowOrchestrator.class.getDeclaredMethod("verifyActiveTask", GitOperations.class, Map.class);
            verify.setAccessible(true);
            int first = (int) verify.invoke(orchestrator, GitOperations.forRepo(repo), task);
            assertThat(first).isEqualTo(1);
            assertThat(state.get("phase")).isEqualTo("IMPLEMENTING");
            int second = (int) verify.invoke(orchestrator, GitOperations.forRepo(repo), task);
            assertThat(second).isEqualTo(1);
            assertThat(state.get("phase")).isEqualTo("BLOCKED_REQUIREMENTS");
            assertThat(state.get("next_action")).isEqualTo("ESCALATE_UNMET_CRITERIA");
            assertThat(state.get("task_stall")).isInstanceOf(Map.class);
        } finally { lock.release(); }
    }

    @Test void githubWorkerCallUsesStructuredBoundaryAndRecordsCriterionAttribution() throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("seed.txt"), "seed");
        git(repo, "add", "seed.txt");
        git(repo, "commit", "-q", "-m", "seed");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path prompts = Files.createDirectories(root.resolve("prompts"));
        Path prompt = prompts.resolve("implement.md");
        Files.writeString(prompt, "Implement only the accepted story criteria.\n");
        String originalRoot = System.getProperty("codex.automation.root");
        System.setProperty("codex.automation.root", root.toString());
        Map<String, Object> config = Map.of(
                "repository", Map.of("local_path", repo.toString(), "owner", "local", "name", "fixture"),
                "github", Map.of(),
                "automation", Map.of("dry_run", false, "max_story_worker_calls", 4,
                        "max_story_active_seconds", 60, "max_task_attempts", 2),
                "budgets", Map.of(), "workflow", Map.of(),
                "codex", Map.of("executable", "unused", "model", "mock", "isolation", "none"),
                "verification", Map.of("allowed_paths", List.of("**"), "protected_paths", List.of()));
        StateStore store = new StateStore(stateDir.resolve("epic-1.json"));
        Map<String, Object> state = store.load();
        state.put("phase", "IMPLEMENTING");
        state.put("execution_id", "mock-execution");
        state.put("current_story", 2);
        state.put("branch", "story/2-example");
        state.put("goal_contract", Map.of("requirements_hash", "requirements-v1", "criteria",
                List.of(Map.of("id", "AC-1", "text", "Create result.txt"))));
        state.put("active_review_findings", List.of(Map.of("thread_id", "PRRT_1", "status", "IN_PROGRESS")));
        store.save(state);
        WorkflowLock lock = WorkflowLock.acquire(stateDir.resolve("epic-1.lock"), 1, 3, 10);
        CodexRunner worker = new CodexRunner(Map.of(), System::nanoTime) {
            @Override public CodexResult run(Map<String, Object> ignoredConfig, Path workerPrompt, Path checkout,
                                            boolean dryRun, Runnable heartbeat, Integer timeout) {
                try {
                    String protocol = Files.readString(workerPrompt);
                    String requestId = field(protocol, "request_id");
                    String requirementsHash = field(protocol, "requirements_hash");
                    String source = field(protocol, "source_fingerprint");
                    String permitted = protocol.substring(protocol.lastIndexOf("allowed acceptance criteria JSON: ")
                            + "allowed acceptance criteria JSON: ".length());
                    permitted = permitted.substring(0, permitted.indexOf(')'));
                    List<String> criteria = new ObjectMapper().readValue(permitted,
                            new com.fasterxml.jackson.core.type.TypeReference<>() { });
                    Map<String, Object> change = Map.of("operation", "write", "path", "result.txt",
                            "expected_sha256", "ABSENT", "content", "verified artifact", "criterion_ids", criteria);
                    String output = new ObjectMapper().writeValueAsString(Map.of("schema_version", 1,
                            "request_id", requestId, "requirements_hash", requirementsHash,
                            "source_fingerprint", source, "status", "SUCCESS", "reason", "",
                            "changes", List.of(change), "fixed_thread_ids", List.of("PRRT_1"),
                            "superseded_thread_ids", List.of()));
                    return new CodexResult(0, output);
                } catch (Exception exc) { throw new IllegalStateException(exc); }
            }
        };
        try {
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(config, state, store,
                    new GitHub("local", "fixture", "GH_TOKEN", "gh", 2,
                            (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), key -> null),
                    repo, root, Map.of("number", 1), 1, false,
                    new Controller.GitOperationsFactory(repo), worker, new ReviewCoordinator(), lock);
            var deadline = WorkflowOrchestrator.class.getDeclaredField("phaseDeadlineNanos");
            deadline.setAccessible(true);
            deadline.setLong(orchestrator, System.nanoTime() + 60_000_000_000L);
            var invoke = WorkflowOrchestrator.class.getDeclaredMethod("runMeteredWorker",
                    String.class, Path.class, Runnable.class, boolean.class);
            invoke.setAccessible(true);
            CodexRunner.CodexResult result = (CodexRunner.CodexResult) invoke.invoke(orchestrator,
                    "IMPLEMENTING", prompt, (Runnable) () -> { }, false);

            assertThat(result.exitCode()).isZero();
            assertThat(result.output()).contains("RESULT_STATUS: SUCCESS");
            assertThat(result.output()).contains("FIXED_THREAD: PRRT_1");
            assertThat(Files.readString(repo.resolve("result.txt"))).isEqualTo("verified artifact");
            assertThat(((Map<?, ?>) state.get("last_tool_result")).get("status")).isEqualTo("APPLIED");
            Map<?, ?> attribution = (Map<?, ?>) ((Map<?, ?>) state.get("change_requirement_attribution")).get("result.txt");
            assertThat(attribution.get("criteria")).isEqualTo(List.of("AC-1"));
            assertThat(attribution.get("requirements_hash")).isEqualTo("requirements-v1");
        } finally {
            lock.release();
            if (originalRoot == null) System.clearProperty("codex.automation.root");
            else System.setProperty("codex.automation.root", originalRoot);
        }
    }

    private static String field(String prompt, String name) {
        Matcher matcher = Pattern.compile(name + "=\\\"([^\\\"]+)\\\"").matcher(prompt);
        if (!matcher.find()) throw new IllegalArgumentException("Missing worker protocol field " + name);
        return matcher.group(1);
    }

    private static void git(Path repo, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("Disposable Git command failed: " + command);
    }

    private static String gitOutput(Path repo, String... args) {
        GitOperations.GitResult result = runLocalGit(repo, List.of(args));
        if (result.exitCode() != 0) throw new IllegalStateException(result.stderr());
        return result.stdout().strip();
    }

    private static GitOperations.GitResult runLocalGit(Path repo, List<String> args) {
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git"));
            command.addAll(args);
            Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return new GitOperations.GitResult(process.waitFor(), output, output);
        } catch (Exception exc) { return new GitOperations.GitResult(127, "", exc.getMessage()); }
    }
}
