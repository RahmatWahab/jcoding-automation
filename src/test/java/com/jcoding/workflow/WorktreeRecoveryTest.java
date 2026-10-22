package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeRecoveryTest {
    @TempDir Path repo;

    private void init() throws Exception {
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("file.txt"), "base\n");
        git("add", ".");
        git("commit", "-qm", "base");
        git("switch", "-c", "story/82-old");
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output.strip();
    }

    @Test
    void ownedTerminalWorktreeCleanupRequiresIdentityAndRemovesOnlyRegisteredCheckout() throws Exception {
        Path sibling = Files.createDirectories(repo.resolve("operator"));
        runGit(sibling, "init", "-q", "-b", "main");
        runGit(sibling, "config", "user.email", "test@example.com");
        runGit(sibling, "config", "user.name", "Test");
        Files.writeString(sibling.resolve("seed"), "seed");
        runGit(sibling, "add", ".");
        runGit(sibling, "commit", "-q", "-m", "seed");
        Path checkout = IsolatedCheckout.open(sibling, 900);
        IsolatedCheckout.recordLifecycle(checkout, "VERIFIED_RETAINED");
        assertThat(Files.isDirectory(checkout)).isTrue();
        String source = GitOperations.forRepo(checkout).recoveryFingerprint();
        assertThat(IsolatedCheckout.cleanupDetailed(sibling, 900, checkout, false, source).removed()).isFalse();
        assertThat(Files.isDirectory(checkout)).isTrue();
        assertThat(IsolatedCheckout.cleanupDetailed(repo, 900, checkout, true, source).removed()).isFalse();
        IsolatedCheckout.CleanupResult cleanup = IsolatedCheckout.cleanupDetailed(sibling, 900, checkout, true, source);
        assertThat(cleanup.removed()).as(cleanup.reason()).isTrue();
        assertThat(Files.exists(checkout)).isFalse();
        assertThat(IsolatedCheckout.cleanupDetailed(sibling, 900, checkout, true, source).removed()).isFalse();
    }

    @Test
    void cleanupRetainsOwnedWorktreeWhenItsSourceChangedAfterArtifactVerification() throws Exception {
        Path sibling = Files.createDirectories(repo.resolve("operator"));
        runGit(sibling, "init", "-q", "-b", "main");
        runGit(sibling, "config", "user.email", "test@example.com");
        runGit(sibling, "config", "user.name", "Test");
        Files.writeString(sibling.resolve("seed"), "seed");
        runGit(sibling, "add", ".");
        runGit(sibling, "commit", "-q", "-m", "seed");
        Path checkout = IsolatedCheckout.open(sibling, 902);
        String deliveredSource = GitOperations.forRepo(checkout).recoveryFingerprint();
        IsolatedCheckout.recordLifecycle(checkout, "VERIFIED_RETAINED");
        Files.writeString(checkout.resolve("late-edit"), "must survive");

        IsolatedCheckout.CleanupResult cleanup = IsolatedCheckout.cleanupDetailed(
                sibling, 902, checkout, true, deliveredSource);

        assertThat(cleanup.removed()).isFalse();
        assertThat(cleanup.reason()).contains("changed after verified artifact");
        assertThat(Files.readString(checkout.resolve("late-edit"))).isEqualTo("must survive");
    }

    @Test
    void interruptedOwnedCheckoutIsRetainedAndReactivatedWithRecoveryHistory() throws Exception {
        Path sibling = Files.createDirectories(repo.resolve("operator"));
        runGit(sibling, "init", "-q", "-b", "main");
        runGit(sibling, "config", "user.email", "test@example.com");
        runGit(sibling, "config", "user.name", "Test");
        Files.writeString(sibling.resolve("seed"), "seed");
        runGit(sibling, "add", ".");
        runGit(sibling, "commit", "-q", "-m", "seed");
        Path checkout = IsolatedCheckout.open(sibling, 901);
        IsolatedCheckout.recordLifecycle(checkout, "INTERRUPTED_RETAINED");
        Path owner = checkout.getParent().resolve(checkout.getFileName() + ".owner.json");

        assertThat(IsolatedCheckout.open(sibling, 901)).isEqualTo(checkout);
        Map<?, ?> record = new com.fasterxml.jackson.databind.ObjectMapper().readValue(owner.toFile(), Map.class);
        assertThat(record.get("lifecycle")).isEqualTo("ACTIVE");
        assertThat(record.get("previous_lifecycle")).isEqualTo("INTERRUPTED_RETAINED");
        assertThat(Files.isDirectory(checkout)).isTrue();
    }

    @Test
    void activeOwnerFromPreviousControllerRunIsRecordedAsInterruptedBeforeResume() throws Exception {
        Path sibling = Files.createDirectories(repo.resolve("operator"));
        runGit(sibling, "init", "-q", "-b", "main");
        runGit(sibling, "config", "user.email", "test@example.com");
        runGit(sibling, "config", "user.name", "Test");
        Files.writeString(sibling.resolve("seed"), "seed");
        runGit(sibling, "add", ".");
        runGit(sibling, "commit", "-q", "-m", "seed");
        Path checkout = IsolatedCheckout.open(sibling, 903);

        assertThat(IsolatedCheckout.open(sibling, 903)).isEqualTo(checkout);
        Path owner = checkout.getParent().resolve(checkout.getFileName() + ".owner.json");
        Map<?, ?> record = new com.fasterxml.jackson.databind.ObjectMapper().readValue(owner.toFile(), Map.class);
        assertThat(record.get("lifecycle")).isEqualTo("ACTIVE");
        assertThat(record.get("previous_lifecycle")).isEqualTo("ACTIVE");
        assertThat(record.get("recovery_reason")).isEqualTo("previous_controller_ended_while_checkout_active");
        assertThat(record.get("recovered_at")).isNotNull();
    }

    private void runGit(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }

    private Map<String, Object> parkedState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("current_story", 82);
        state.put("branch", "story/82-old");
        ParkPolicy.parkWithCheckpoint(state, 82, "", "unfinished", "now");
        state.put("current_story", 83);
        state.remove("branch");
        return state;
    }

    @Test
    void preservesParkedStoryBeforeNextStoryAndRestoresIndexAndUntrackedFiles() throws Exception {
        init();
        Files.writeString(repo.resolve("file.txt"), "staged\n");
        git("add", "file.txt");
        Files.writeString(repo.resolve("file.txt"), "unstaged\n");
        Files.write(repo.resolve("new.bin"), new byte[]{0, 1, 2, -1});
        Files.createDirectories(repo.resolve(".codex"));
        Files.writeString(repo.resolve(".codex/keep"), "metadata");
        String index = git("diff", "--cached", "--binary");
        String diff = git("diff", "--binary");
        Map<String, Object> state = parkedState();
        GitOperations ops = GitOperations.forRepo(repo);
        WorktreeRecovery.isolateParkedWork(state, ops, () -> { });
        ops.requireCleanWorktree();
        assertThat(repo.resolve("new.bin")).doesNotExist();
        assertThat(repo.resolve(".codex/keep")).hasContent("metadata");
        git("switch", "-c", "story/83-new", "main");
        assertThat(repo.resolve("file.txt")).hasContent("base\n");
        git("switch", "story/82-old");
        state.put("branch", "story/82-old");
        state.put("current_story", 82);
        WorktreeRecovery.restoreActiveWork(state, ops, () -> { });
        assertThat(git("diff", "--cached", "--binary")).isEqualTo(index);
        assertThat(git("diff", "--binary")).isEqualTo(diff);
        assertThat(Files.readAllBytes(repo.resolve("new.bin"))).containsExactly(0, 1, 2, -1);
        // Re-entry must not reapply or discard anything.
        WorktreeRecovery.restoreActiveWork(state, ops, () -> { });
        assertThat(git("for-each-ref", "--format=%(refname)", "refs/jcoding/checkpoints")).contains("story-82-");
    }

    @Test
    void refusesToMoveUnownedChangesEvenWhenGitWouldAllowCheckout() throws Exception {
        init();
        git("branch", "story/83-new");
        Files.writeString(repo.resolve("file.txt"), "unknown work\n");
        Map<String, Object> state = new LinkedHashMap<>(Map.of("current_story", 83, "branch", "story/83-new"));
        GitOperations ops = GitOperations.forRepo(repo);
        WorktreeRecovery.isolateParkedWork(state, ops, () -> { });
        assertThatThrownBy(() -> ops.checkoutStoryBranch("story/83-new"))
                .hasMessageContaining("pre-existing changes");
        assertThat(ops.currentBranchName()).isEqualTo("story/82-old");
        assertThat(repo.resolve("file.txt")).hasContent("unknown work\n");
        assertThat(git("stash", "list")).isEmpty();
    }

    @Test
    void recoversCrashBetweenStashAndDedicatedRefCreation() throws Exception {
        init();
        Files.writeString(repo.resolve("file.txt"), "preserve me\n");
        GitOperations ops = GitOperations.forRepo(repo);
        String ref = "refs/jcoding/checkpoints/story-82-crash";
        Map<String, Object> state = parkedState();
        Map<String, Object> record = new LinkedHashMap<>(Map.of("story", 82, "branch", "story/82-old",
                "base_head", ops.head(), "fingerprint", ops.recoveryFingerprint(), "ref", ref, "status", "CAPTURING"));
        state.put(WorktreeRecovery.KEY, new LinkedHashMap<>(Map.of("story/82-old", record)));
        git("stash", "push", "-u", "-m", ref);
        WorktreeRecovery.isolateParkedWork(state, ops, () -> { });
        assertThat(record.get("status")).isEqualTo("PRESERVED");
        assertThat(git("show", ref + ":file.txt")).isEqualTo("preserve me");
        assertThat(git("stash", "list").lines()).hasSize(1);
    }

    @Test
    void restoreOnChangedBaseHoldsWithoutLosingBackup() throws Exception {
        init();
        Files.writeString(repo.resolve("file.txt"), "unfinished\n");
        Map<String, Object> state = parkedState();
        GitOperations ops = GitOperations.forRepo(repo);
        WorktreeRecovery.isolateParkedWork(state, ops, () -> { });
        git("commit", "--allow-empty", "-qm", "new base");
        state.put("branch", "story/82-old");
        state.put("current_story", 82);
        assertThatThrownBy(() -> WorktreeRecovery.restoreActiveWork(state, ops, () -> { }))
                .hasMessageContaining("Checkpoint base changed");
        ops.requireCleanWorktree();
        assertThat(git("for-each-ref", "--format=%(refname)", "refs/jcoding/checkpoints")).isNotBlank();
    }

    @Test
    void failedIntentPersistenceDoesNotTouchWorkingTree() throws Exception {
        init();
        Files.writeString(repo.resolve("file.txt"), "keep me\n");
        Map<String, Object> state = parkedState();
        assertThatThrownBy(() -> WorktreeRecovery.isolateParkedWork(state, GitOperations.forRepo(repo),
                () -> { throw new IllegalStateException("state disk unavailable"); }))
                .hasMessageContaining("state disk unavailable");
        assertThat(repo.resolve("file.txt")).hasContent("keep me\n");
        assertThat(git("stash", "list")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedSuccessfulRestoreIsRecognizedWithoutApplyingTwice() throws Exception {
        init();
        Files.writeString(repo.resolve("file.txt"), "unfinished\n");
        Map<String, Object> state = parkedState();
        GitOperations ops = GitOperations.forRepo(repo);
        WorktreeRecovery.isolateParkedWork(state, ops, () -> { });
        Map<String, Object> record = (Map<String, Object>)
                ((Map<?, ?>) state.get(WorktreeRecovery.KEY)).get("story/82-old");
        state.put("branch", "story/82-old");
        state.put("current_story", 82);
        record.put("status", "RESTORING");
        ops.restoreWorktree(record.get("ref").toString());
        WorktreeRecovery.restoreActiveWork(state, ops, () -> { });
        assertThat(record).containsEntry("status", "RESTORED");
        assertThat(repo.resolve("file.txt")).hasContent("unfinished\n");
    }
}
