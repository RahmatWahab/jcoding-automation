package com.rahmatwahab.codexautomation;

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

/**
 * Git-operations tests. Most tests use an injected fake
 * {@link GitOperations.GitRunner}; a couple use real {@code git} in a
 * {@code @TempDir}. No git remote is contacted.
 */
class GitOperationsTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Records git invocations and returns scripted output per subcommand. */
    private static final class FakeGit implements GitOperations.GitRunner {
        final List<List<String>> calls = new ArrayList<>();
        final Map<String, String> outputs = new LinkedHashMap<>();

        FakeGit output(String key, String value) {
            outputs.put(key, value);
            return this;
        }

        @Override
        public GitOperations.GitResult run(Path repo, List<String> args) {
            calls.add(args);
            String stdout = "";
            if (args.size() >= 2) {
                stdout = outputs.getOrDefault(args.get(0) + " " + args.get(1),
                        outputs.getOrDefault(args.get(0), ""));
            } else if (!args.isEmpty()) {
                stdout = outputs.getOrDefault(args.get(0), "");
            }
            return new GitOperations.GitResult(0, stdout, "");
        }

        boolean hasCall(String... prefix) {
            for (List<String> call : calls) {
                if (call.size() >= prefix.length) {
                    boolean match = true;
                    for (int i = 0; i < prefix.length; i++) {
                        if (!call.get(i).equals(prefix[i])) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // -- git_branch ----------------------------------------------------------

    @Test
    void defaultBranchIsRejected() {
        FakeGit git = new FakeGit().output("branch --show-current", "main\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThatThrownBy(ops::gitBranch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("feature branch");
    }

    // -- commit_and_push -----------------------------------------------------

    @Test
    void commitExcludesCodexMetadata() {
        FakeGit git = new FakeGit();
        // First status returns a source change; rev-parse returns a sha.
        git.outputs.put("status --porcelain", " M src/file.txt\n");
        git.outputs.put("rev-parse HEAD", "abc\n");
        git.outputs.put("rev-parse", "abc\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        ops.commitAndPush("feature", 1);
        boolean found = git.calls.stream().anyMatch(c ->
                c.size() >= 3 && c.get(0).equals("add") && c.get(1).equals("-A")
                        && c.contains(":(exclude).codex"));
        assertThat(found).isTrue();
    }

    @Test
    void metadataOnlyChangesDoNotCreateEmptyCommit() {
        FakeGit git = new FakeGit().output("status --porcelain", "?? .codex/\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.commitAndPush("feature", 1)).isNull();
        assertThat(git.calls).hasSize(1);
    }

    @Test
    void credentialLikePathsAreRefused() {
        FakeGit git = new FakeGit().output("status --porcelain", " M config/secrets/token.txt\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThatThrownBy(() -> ops.commitAndPush("feature", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential-like paths");
    }

    // -- prepare_story_branch ------------------------------------------------

    @Test
    void prepareStoryBranchSyncsBaseAndCreatesBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", "");
        git.outputs.put("branch --show-current", "main\n");
        git.outputs.put("rev-parse origin/main", "abc\n");
        git.outputs.put("rev-parse HEAD", "abc\n");
        git.outputs.put("rev-parse", "abc\n");
        git.outputs.put("branch --list", "");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        GitOperations.StoryBranch result = ops.prepareStoryBranch("main", 46, "Next Story");
        assertThat(result.branch()).isEqualTo("story/46-next-story");
        assertThat(result.baseHead()).isEqualTo("abc");
        assertThat(git.hasCall("fetch", "origin", "main")).isTrue();
        assertThat(git.hasCall("pull", "--ff-only", "origin", "main")).isTrue();
        assertThat(git.hasCall("switch", "-c", "story/46-next-story")).isTrue();
    }

    // -- ensure_recoverable_worktree -----------------------------------------

    @Test
    void recoverableWorktreeIsAllowedOnActiveStoryBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", " M scripts/example.sh\n");
        git.outputs.put("branch --show-current", "story/46-next-story\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        Map<String, Object> state = map("branch", "story/46-next-story", "current_story", 46);
        ops.ensureRecoverableWorktree(state);
        @SuppressWarnings("unchecked")
        Map<String, Object> recovery = (Map<String, Object>) state.get("worktree_recovery");
        assertThat(recovery.get("action")).isEqualTo("RESUME_CONTROLLER_OWNED_WORKTREE");
    }

    @Test
    void recoverableWorktreeRejectsUnknownBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", " M scripts/example.sh\n");
        // A non-default branch that differs from the recorded active story branch.
        git.outputs.put("branch --show-current", "story/99-unrelated\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThatThrownBy(() -> ops.ensureRecoverableWorktree(
                map("branch", "story/46-next-story", "current_story", 46)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the active story branch");
    }

    @Test
    void recoverableWorktreeRejectsDefaultBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", " M scripts/example.sh\n");
        git.outputs.put("branch --show-current", "main\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        // On the default branch, git_branch itself refuses (non-feature branch).
        assertThatThrownBy(() -> ops.ensureRecoverableWorktree(
                map("branch", "story/46-next-story", "current_story", 46)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("feature branch");
    }

    // -- checkout_story_branch -----------------------------------------------

    @Test
    void checkoutStoryBranchSwitchesToExistingLocalBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("branch --show-current", "main\n");
        git.outputs.put("branch --list", "story/47-net\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.checkoutStoryBranch("story/47-net")).isTrue();
        assertThat(git.hasCall("fetch", "origin", "story/47-net")).isTrue();
        assertThat(git.hasCall("switch", "story/47-net")).isTrue();
    }

    @Test
    void checkoutStoryBranchCreatesTrackingBranchWhenMissingLocally() {
        FakeGit git = new FakeGit();
        git.outputs.put("branch --show-current", "main\n");
        git.outputs.put("branch --list", ""); // no local branch yet
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.checkoutStoryBranch("story/47-net")).isTrue();
        assertThat(git.hasCall("checkout", "-B", "story/47-net", "origin/story/47-net")).isTrue();
    }

    @Test
    void checkoutStoryBranchIsNoOpWhenAlreadyOnBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("branch --show-current", "story/47-net\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.checkoutStoryBranch("story/47-net")).isTrue();
        assertThat(git.hasCall("switch", "story/47-net")).isFalse();
        assertThat(git.hasCall("checkout", "-B", "story/47-net")).isFalse();
    }

    @Test
    void checkoutStoryBranchReturnsFalseForBlankBranch() {
        FakeGit git = new FakeGit();
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.checkoutStoryBranch("")).isFalse();
        assertThat(git.calls).isEmpty();
    }

    @Test
    void checkoutStoryBranchRefusesDefaultBranch() {
        FakeGit git = new FakeGit();
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThatThrownBy(() -> ops.checkoutStoryBranch("main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default branch");
    }

    // -- classify_worktree (non-throwing recovery routing) -------------------

    @Test
    void classifyWorktreeReturnsCleanWhenNoChanges() {
        FakeGit git = new FakeGit().output("status --porcelain", "");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.classifyWorktree(map())).isEqualTo(GitOperations.WorktreeState.CLEAN);
    }

    @Test
    void classifyWorktreeReportsDirtyDefaultBranchWithoutThrowing() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", "?? migration-updates/\n");
        git.outputs.put("branch --show-current", "main\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        // This is exactly the stuck-repo scenario: it must classify, not throw.
        assertThat(ops.classifyWorktree(map("branch", "story/47-net", "current_story", 47)))
                .isEqualTo(GitOperations.WorktreeState.DIRTY_DEFAULT_BRANCH);
    }

    @Test
    void classifyWorktreeReportsRecoverableStoryBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", " M src/file.txt\n");
        git.outputs.put("branch --show-current", "story/47-net\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.classifyWorktree(map("branch", "story/47-net", "current_story", 47)))
                .isEqualTo(GitOperations.WorktreeState.RECOVERABLE_STORY_BRANCH);
    }

    @Test
    void classifyWorktreeReportsDirtyUnknownBranch() {
        FakeGit git = new FakeGit();
        git.outputs.put("status --porcelain", " M src/file.txt\n");
        git.outputs.put("branch --show-current", "story/99-unrelated\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        assertThat(ops.classifyWorktree(map("branch", "story/47-net", "current_story", 47)))
                .isEqualTo(GitOperations.WorktreeState.DIRTY_UNKNOWN_BRANCH);
    }

    // -- verify_delivered_head_with_retry ------------------------------------

    @Test
    void deliveryHeadVerificationRetriesUntilGithubConverges() {
        // Fake git: local HEAD and remote both resolve to "abc".
        FakeGit git = new FakeGit();
        git.outputs.put("rev-parse HEAD", "abc\n");
        git.outputs.put("ls-remote origin", "abc\trefs/heads/feature\n");
        GitOperations ops = new GitOperations(Path.of("/repo"), git);
        // Fake GitHub: first snapshot old head, second converged head.
        List<Map<String, Object>> snapshots = new ArrayList<>(List.of(
                map("headRefOid", "old"), map("headRefOid", "abc")));
        int[] index = {0};
        GitOperations.DeliveryResult result = ops.verifyDeliveredHeadWithRetry(
                "feature", new StubGitHub(snapshots, index), 177, 2, 0, seconds -> { });
        assertThat(result.verified()).isTrue();
        assertThat(result.pr().get("headRefOid")).isEqualTo("abc");
    }

    /** GitHub subclass that serves scripted PR snapshots without any process. */
    private static final class StubGitHub extends GitHub {
        private final List<Map<String, Object>> snapshots;
        private final int[] index;

        StubGitHub(List<Map<String, Object>> snapshots, int[] index) {
            super("owner", "repo", "GH_TOKEN", "gh", 30,
                    (command, timeout) -> new GitHub.CommandResult(0, "{}", ""), name -> "t");
            this.snapshots = snapshots;
            this.index = index;
        }

        @Override
        public Map<String, Object> pr(int number) {
            return snapshots.get(Math.min(index[0]++, snapshots.size() - 1));
        }
    }

    // -- require_clean_worktree (real git in temp dir) -----------------------

    @Test
    void dirtyWorktreeIsRejectedWithRealGit(@TempDir Path tmp) throws Exception {
        runGit(tmp, "init", "-q");
        Files.writeString(tmp.resolve("unrelated.txt"), "user work");
        GitOperations ops = GitOperations.forRepo(tmp);
        assertThatThrownBy(ops::requireCleanWorktree)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pre-existing");
    }

    @Test
    void codexMetadataIsIgnoredWithRealGit(@TempDir Path tmp) throws Exception {
        runGit(tmp, "init", "-q");
        Files.createDirectories(tmp.resolve(".codex"));
        Files.writeString(tmp.resolve(".codex").resolve("state.json"), "{}");
        GitOperations ops = GitOperations.forRepo(tmp);
        // Should not throw: only .codex metadata is present.
        ops.requireCleanWorktree();
    }

    private static void runGit(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(repo.toFile()).inheritIO().start();
        p.waitFor();
    }
}
