package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import com.jcoding.github.GitHub;
import com.jcoding.state.StateStore;
import com.jcoding.state.WorkflowLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real branch/stash/commit/push operations; GitHub and model calls are isolated fakes. */
class OpenPrRecoveryE2ETest {
    @TempDir Path root;

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        // These fixtures isolate orchestration; RuntimeVerifierTest covers executable proof.
        if (result.get("repository") instanceof Map<?, ?> rawRepository) {
            Map<String, Object> repository = new LinkedHashMap<>();
            rawRepository.forEach((key, value) -> repository.put(String.valueOf(key), value));
            repository.putIfAbsent("owner", "owner");
            repository.putIfAbsent("name", "repo");
            result.put("repository", repository);
            result.putIfAbsent("verification", Map.of("required", true,
                    "checks", List.of(Map.of("id", "fixture-acceptance", "command", List.of("mock-check"),
                            "criteria", List.of("AC-1")))));
        }
        return result;
    }

    private static String git(Path repo, String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(args));
            Process p = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertThat(p.waitFor()).as(out).isZero();
            return out.strip();
        } catch (Exception exc) { throw new IllegalStateException(exc); }
    }

    private static String field(String prompt, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(name + "=\\\"([^\\\"]+)\\\"")
                .matcher(prompt);
        if (!matcher.find()) throw new IllegalArgumentException("Missing worker protocol field " + name);
        return matcher.group(1);
    }

    @Disabled("Excluded from the business-value mock suite pending contract triage")
    @Test
    void parkedOpenPrResumesWithItsOwnWorkAndCannotResolveBeforeVerifiedDelivery() throws Exception {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Path origin = Files.createDirectories(root.resolve("origin.git"));
        git(origin, "init", "--bare", "-b", "main");
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("allocator.txt"), "base");
        git(repo, "add", "."); git(repo, "commit", "-m", "base");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "origin", "main");
        git(repo, "switch", "-c", "story/82-allocator");
        git(repo, "push", "-u", "origin", "story/82-allocator");
        String originalHead = git(repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("allocator.txt"), "preserved story 82 work");
        Files.createDirectories(root.resolve("state"));
        Path prompts = Files.createDirectories(root.resolve("prompts"));
        for (String name : List.of("common", "reconcile", "feedback", "implement", "verify", "review",
                "review-general", "review-general-fix", "review-test", "review-test-fix")) {
            Files.writeString(prompts.resolve(name + ".md"), "PROMPT " + name);
        }
        Map<String, Object> state = map("phase", "BLOCKED_REQUIREMENTS", "current_story", 82,
                "current_pr", 219, "current_pr_head", originalHead, "branch", "story/82-allocator");
        // This edit is a previously persisted worker effect. Its ownership
        // record is required before controller delivery may stage it.
        state.put("worker_owned_story", 82);
        state.put("worker_owned_branch", "story/82-allocator");
        state.put("worker_owned_paths", List.of("allocator.txt"));
        // Reproduce the legacy bad park record with an empty head.
        ParkPolicy.parkWithCheckpoint(state, 82, "", "internal review failed", "now");
        StateStore store = new StateStore(root.resolve("state/epic-27.json"));
        WorktreeRecovery.preserve(state, GitOperations.forRepo(repo), "story/82-allocator", 82, () -> store.save(state));
        git(repo, "switch", "-c", "story/83-lifecycle", "main");
        Files.writeString(repo.resolve("lifecycle.txt"), "unfinished story 83");
        state.put("current_story", 83); state.put("current_pr", null);
        state.put("branch", "story/83-lifecycle"); state.put("phase", "IMPLEMENTING");
        Map<String, Object> config = map("repository", map("base_branch", "main"),
                "codex", map("model", "test"),
                "automation", map("max_feedback_attempts", 20, "internal_review_max_fix_attempts", 0,
                        "delivery_verification_attempts", 1, "delivery_verification_delay_seconds", 0),
                "workflow", map("terminal_policy", "park_and_advance", "finish_open_pr_before_next_story", true,
                        "allow_push", true, "allow_comments", true, "allow_thread_resolution", true, "internal_review", true),
                "budgets", map());
        Map<String, Object> epic = map("body", "Stories: #82 #83", "number", 27);
        List<String> resolved = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        List<Map<String, Object>> comments = new ArrayList<>();
        GitHub github = new GitHub("owner", "repo", "TOKEN", "gh", 30,
                (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), key -> "test") {
            @Override public Object issue(int n) {
                return map("number", n, "state", "OPEN", "title", "Story " + n,
                        "body", "```jcoding-intent\n{\"intent\":\"implement_story\",\"criteria\":["
                                + "{\"id\":\"AC-1\",\"text\":\"Repair the allocator and verify its result\"}]}\n```");
            }
            @Override public Map<String, Object> pr(int n) {
                assertThat(n).isEqualTo(219);
                String head = git(origin, "rev-parse", "story/82-allocator");
                List<Object> threads = new ArrayList<>();
                for (String id : List.of("PRRT_FIX", "PRRT_KEEP")) threads.add(map("id", id,
                        "isResolved", resolved.contains(id), "comments", map("nodes", List.of(map(
                                "id", "PRRC_" + id, "body", "Repair " + id, "createdAt", "2026-01-01",
                                "author", map("login", "reviewer"))))));
                return map("number", 219, "state", "OPEN", "headRefName", "story/82-allocator",
                        "headRefOid", head, "url", "http://test/219", "body", "Closes #82",
                        "reviewThreadsAvailable", true, "reviewThreads", threads, "comments", comments,
                        "statusCheckRollup", List.of(), "reviews", List.of(map("author",
                                map("login", "chatgpt-codex-connector"), "submittedAt", "2026-02-01",
                                "commit", map("oid", originalHead))));
            }
            @Override public void comment(int n, String body) {
                comments.add(map("body", body, "createdAt", "9999-12-31T23:59:59Z"));
            }
            @Override public void replyThread(String id, String body) {
                assertThat(git(origin, "rev-parse", "story/82-allocator")).isNotEqualTo(originalHead);
                replies.add(id);
            }
            @Override public void resolveThread(String id) {
                assertThat(replies).contains(id);
                resolved.add(id);
            }
        };
        int[] workerCalls = {0};
        boolean[] reviewsPass = {false};
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime) {
            @Override public CodexResult run(Map<String, Object> cfg, Path prompt, Path cwd,
                                              boolean dry, Runnable hb, Integer timeout) {
                workerCalls[0]++;
                try {
                    String text = Files.readString(prompt);
                    assertThat(git(repo, "branch", "--show-current")).isEqualTo("story/82-allocator");
                    assertThat(repo.resolve("lifecycle.txt")).doesNotExist();
                    if (text.startsWith("PROMPT reconcile")) {
                        assertThat(text).contains("PRRT_FIX", "PRRT_KEEP", originalHead);
                        return new CodexResult(0, "DECISION_ACTION: PROCESS_FEEDBACK\nDECISION_REASON: repair existing PR\n"
                                + "BLOCKER_CLASS: REVIEW_FEEDBACK\nRECOVERY_ACTION: fix allocator\n"
                                + "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: COMPLETE\nRESULT_NEXT_PHASE: FIXING_FEEDBACK\n");
                    }
                    if (text.startsWith("PROMPT review-")) return new CodexResult(0, reviewsPass[0]
                            ? "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: COMPLETE\nRESULT_BLOCKER: NONE\n"
                            : "RESULT_STATUS: BLOCKED\nRESULT_EVIDENCE: BLOCKED\nRESULT_BLOCKER: review still fails\n");
                    String requestId = field(text, "request_id");
                    String requirements = field(text, "requirements_hash");
                    String source = field(text, "source_fingerprint");
                    String old = WorkerToolBoundary.sha256(Files.readAllBytes(repo.resolve("allocator.txt")));
                    String response = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                            "schema_version", 1, "request_id", requestId, "requirements_hash", requirements,
                            "source_fingerprint", source, "status", "SUCCESS", "reason", "",
                            "changes", List.of(Map.of("operation", "write", "path", "allocator.txt",
                                    "expected_sha256", old, "content", "verified allocator repair",
                                    "criterion_ids", List.of("AC-1"))),
                            "fixed_thread_ids", List.of("PRRT_FIX"), "superseded_thread_ids", List.of()));
                    return new CodexResult(0, response);
                } catch (Exception exc) { throw new IllegalStateException(exc); }
            }
        };
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state/epic-27.lock"), 900, 3, 10);
        try {
            WorkflowOrchestrator controller = new WorkflowOrchestrator(config, state, store, github, repo, root,
                    epic, 27, false, new Controller.GitOperationsFactory(repo), runner, new ReviewCoordinator(), lock,
                    new RuntimeVerifier((command, checkout, timeout, heartbeat) ->
                            new BoundedProcess.Result(0, "fixture acceptance passed", false)));
            assertThat(controller.reconcileAndExecute(List.of(github.pr(219)), null))
                    .as("first recovery tick state: %s", state).isZero();
            assertThat(state).containsEntry("current_story", 82).containsEntry("current_pr", 219)
                    .containsEntry("current_pr_head", originalHead).containsEntry("worker_status", "IDLE");
            assertThat(repo.resolve("allocator.txt")).hasContent("preserved story 82 work");
            assertThat(state.get("suspended_stories").toString()).contains("83");
            assertThat(controller.reconcileAndExecute(List.of(github.pr(219)), null)).isEqualTo(1);
            assertThat(state).containsEntry("current_pr", 219).containsEntry("worker_status", "IDLE");
            assertThat(DeliveryRecovery.failure(state)).isNotNull();
            assertThat(ParkPolicy.parkedStoryNumbers(state)).doesNotContain(82);
            assertThat(git(origin, "rev-parse", "story/82-allocator")).isEqualTo(originalHead);
            assertThat(resolved).isEmpty();
            reviewsPass[0] = true;
            controller.reconcileAndExecute(List.of(github.pr(219)), null); // supervisor
            controller.reconcileAndExecute(List.of(github.pr(219)), null); // reviewed repair + delivery
            assertThat(git(origin, "rev-parse", "story/82-allocator")).isEqualTo(git(repo, "rev-parse", "HEAD"))
                    .isNotEqualTo(originalHead);
            assertThat(resolved).containsExactly("PRRT_FIX");
            assertThat(git(repo, "show", "HEAD:allocator.txt")).isEqualTo("verified allocator repair");
            assertThat(state.get("completed_stories")).isNull();
            assertThat(state.get("worker_status")).isEqualTo("IDLE");
            int beforeWait = workerCalls[0];
            controller.reconcileAndExecute(List.of(github.pr(219)), null);
            assertThat(workerCalls[0]).isEqualTo(beforeWait); // Await review on the delivered head.
            assertThat(resolved).doesNotContain("PRRT_KEEP");
        } finally { lock.release(); }
    }
}
