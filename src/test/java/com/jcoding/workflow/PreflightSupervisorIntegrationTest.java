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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class PreflightSupervisorIntegrationTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsafePreflightInvokesReadOnlySupervisorThenHoldsAcrossRestarts(boolean missingPrompt) throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        Files.createDirectories(root.resolve("state"));
        Files.createDirectories(root.resolve("prompts"));
        if (!missingPrompt) Files.writeString(root.resolve("prompts/reconcile.md"), "Reconcile");
        Map<String, Object> config = new LinkedHashMap<>(Map.of(
                "repository", Map.of("local_path", repo.toString(), "base_branch", "main",
                        "owner", "owner", "name", "repo"),
                "codex", Map.of("model", "test", "dangerously_bypass_sandbox", true),
                "workflow", Map.of("allow_push", true),
                "automation", Map.of("max_preflight_diagnoses", 2), "budgets", Map.of()));
        Map<String, Object> epic = Map.of("number", 27, "body", "Stories: #83", "state", "OPEN");
        GitHub github = new GitHub("owner", "repo", "GH_TOKEN", "gh", 30,
                (cmd, timeout) -> new GitHub.CommandResult(0, "{}", ""), key -> "test") {
            @Override public Object issue(int number) {
                return number == 27 ? epic : Map.of("number", number, "title", "Story", "state", "OPEN",
                        "body", "```jcoding-intent\n{\"intent\":\"implement_story\",\"criteria\":["
                                + "{\"id\":\"AC-1\",\"text\":\"preserve and recover work safely\"}]}\n```");
            }
        };
        List<List<String>> gitCalls = new ArrayList<>();
        String[] diff = {"first"};
        Controller.GitOperationsFactory factory = new Controller.GitOperationsFactory(repo) {
            @Override public GitOperations create() {
                return new GitOperations(repo, (path, args) -> {
                    gitCalls.add(args);
                    String output = switch (args.getFirst()) {
                        case "branch" -> "story/82-foreign\n";
                        case "status" -> " M file.txt\0";
                        case "rev-parse" -> "abc";
                        case "diff" -> diff[0];
                        default -> "";
                    };
                    return new GitOperations.GitResult(0, output, "");
                });
            }
        };
        int[] calls = {0};
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime) {
            @Override public CodexResult run(Map<String, Object> cfg, Path prompt, Path cwd,
                                              boolean dryRun, Runnable heartbeat, Integer timeout) {
                calls[0]++;
                assertThat(Config.section(cfg, "codex")).containsEntry("diagnostic_read_only", true);
                try {
                    assertThat(Files.readString(prompt)).contains("PREFLIGHT SUPERVISOR", "story/82-foreign");
                } catch (java.io.IOException exc) { throw new IllegalStateException(exc); }
                // Even a supervisor claiming SUCCESS cannot bypass the unsafe tree.
                return new CodexResult(0, "DECISION_ACTION: RESUME_IMPLEMENTATION\n"
                        + "DECISION_REASON: foreign work must be preserved\nBLOCKER_CLASS: PROCESS\n"
                        + "RECOVERY_ACTION: establish ownership before checkout\nRESULT_STATUS: SUCCESS\n");
            }
        };
        StateStore store = new StateStore(root.resolve("state/epic-27.json"));
        Map<String, Object> state = new LinkedHashMap<>(Map.of("current_story", 83, "phase", "BLOCKED_REQUIREMENTS"));
        WorkflowLock lock = WorkflowLock.acquire(root.resolve("state/epic-27.lock"), 900, 3, 10);
        try {
            for (int tick = 0; tick < 4; tick++) {
                WorkflowOrchestrator controller = new WorkflowOrchestrator(config, state, store, github,
                        repo, root, epic, 27, false, factory, runner, new ReviewCoordinator(), lock);
                assertThat(controller.reconcileAndExecute(new ArrayList<>(), null)).isEqualTo(1);
                state = store.load();
                assertThat(state.get("phase")).as("state after tick %s: %s", tick, state)
                        .isEqualTo(missingPrompt ? "BLOCKED_CONFIGURATION" : "BLOCKED_REQUIREMENTS");
            }
            assertThat(calls[0]).isEqualTo(2);
            assertThat(state.get("next_action")).isEqualTo("WAIT_FOR_PREFLIGHT_EVIDENCE");
            assertThat(state.get("preflight_failure").toString()).contains("establish ownership", "HELD");
            assertThat(gitCalls).noneMatch(args -> List.of("switch", "checkout", "stash", "commit", "push").contains(args.getFirst()));
            diff[0] = "new evidence";
            WorkflowOrchestrator controller = new WorkflowOrchestrator(config, state, store, github,
                    repo, root, epic, 27, false, factory, runner, new ReviewCoordinator(), lock);
            controller.reconcileAndExecute(new ArrayList<>(), null);
            assertThat(calls[0]).isEqualTo(3);
        } finally {
            lock.release();
        }
    }
}
