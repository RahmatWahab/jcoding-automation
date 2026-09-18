package com.rahmatwahab.codexautomation;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Git branch, worktree, commit, push, and delivery-verification operations.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code require_clean_worktree}, {@code ensure_recoverable_worktree},
 * {@code push_branch}, {@code commit_and_push}, {@code verify_delivered_head},
 * {@code verify_delivered_head_with_retry}, {@code prepare_story_branch}.
 * All process execution is behind an injectable {@link GitRunner} so tests use
 * real {@code git} in a temp repo or a fake; no remote is contacted in tests.</p>
 */
public final class GitOperations {

    /** Result of a git invocation (stdout + exit code). */
    public record GitResult(int exitCode, String stdout, String stderr) {
    }

    /** Injectable git execution boundary. */
    @FunctionalInterface
    public interface GitRunner {
        GitResult run(Path repo, List<String> args);
    }

    private static final Set<String> DEFAULT_BRANCHES = Set.of("main", "master");
    private static final Pattern SLUG_CLEAN = Pattern.compile("[^a-z0-9]+");

    private final Path repo;
    private final GitRunner runner;

    public GitOperations(Path repo, GitRunner runner) {
        this.repo = repo;
        this.runner = runner;
    }

    /** Production runner shelling out to {@code git} with check=true semantics. */
    public static GitOperations forRepo(Path repo) {
        return new GitOperations(repo, GitOperations::executeGit);
    }

    private String checkedStdout(String... args) {
        GitResult result = runner.run(repo, List.of(args));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " failed: " + result.stderr());
        }
        return result.stdout();
    }

    private void checked(String... args) {
        GitResult result = runner.run(repo, List.of(args));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " failed: " + result.stderr());
        }
    }

    /** Current branch, rejecting the default (main/master) branch. */
    public String gitBranch() {
        String branch = checkedStdout("branch", "--show-current").strip();
        if (branch.isEmpty() || DEFAULT_BRANCHES.contains(branch)) {
            throw new IllegalStateException("Codex must work on a non-default feature branch");
        }
        return branch;
    }

    private List<String> sourceChanges() {
        String out = checkedStdout("status", "--porcelain");
        List<String> changes = new ArrayList<>();
        for (String line : out.split("\n", -1)) {
            if (!line.isEmpty() && !pathOf(line).startsWith(".codex/")) {
                changes.add(line);
            }
        }
        return changes;
    }

    /** Reject any pre-existing changes (ignoring .codex/ metadata). */
    public void requireCleanWorktree() {
        if (!sourceChanges().isEmpty()) {
            throw new IllegalStateException("working tree contains pre-existing changes");
        }
    }

    /**
     * Classification of the working tree used to route recovery without a hard,
     * self-perpetuating block. Unlike {@link #ensureRecoverableWorktree}, this
     * never throws for a dirty default branch: it reports the situation so the
     * controller can send the case to bounded reconciliation instead of pinning
     * {@code BLOCKED_REPOSITORY} forever.
     */
    public enum WorktreeState {
        /** No source changes (ignoring .codex/ metadata). Safe to proceed. */
        CLEAN,
        /** Changes exist on the recorded active story branch. Recoverable. */
        RECOVERABLE_STORY_BRANCH,
        /** Changes exist on the default branch. Needs reconciliation/cleanup. */
        DIRTY_DEFAULT_BRANCH,
        /** Changes exist on a branch that is not the active story branch. */
        DIRTY_UNKNOWN_BRANCH
    }

    /** Non-throwing worktree classification for recovery routing. */
    public WorktreeState classifyWorktree(Map<String, Object> state) {
        List<String> changes = sourceChanges();
        if (changes.isEmpty()) {
            return WorktreeState.CLEAN;
        }
        String branch = checkedStdout("branch", "--show-current").strip();
        if (branch.isEmpty() || DEFAULT_BRANCHES.contains(branch)) {
            return WorktreeState.DIRTY_DEFAULT_BRANCH;
        }
        Object expected = state.get("branch");
        if (expected != null && !String.valueOf(expected).isEmpty()
                && branch.equals(String.valueOf(expected))) {
            return WorktreeState.RECOVERABLE_STORY_BRANCH;
        }
        return WorktreeState.DIRTY_UNKNOWN_BRANCH;
    }

    /** Allow recovery of interrupted controller work only on its story branch. */
    public void ensureRecoverableWorktree(Map<String, Object> state) {
        List<String> changes = sourceChanges();
        if (changes.isEmpty()) {
            return;
        }
        String branch = gitBranch();
        Object expected = state.get("branch");
        Object story = state.get("current_story");
        if (expected == null || String.valueOf(expected).isEmpty()
                || !branch.equals(expected) || story == null || String.valueOf(story).isEmpty()) {
            throw new IllegalStateException("working tree contains changes outside the active story branch");
        }
        TreeSet<String> files = new TreeSet<>();
        for (String line : changes) {
            files.add(pathOf(line));
        }
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("branch", branch);
        recovery.put("story", Engine.asInt(story));
        recovery.put("files", new ArrayList<>(files));
        recovery.put("action", "RESUME_CONTROLLER_OWNED_WORKTREE");
        state.put("worktree_recovery", recovery);
    }

    public void pushBranch(String branch) {
        checked("push", "--set-upstream", "origin", branch);
    }

    /**
     * Check out an existing story branch so subsequent worktree validation and
     * delivery operate on the correct branch instead of the default branch.
     *
     * <p>Refuses the default (main/master) branch. Fetches the branch from
     * origin first so a fresh checkout can create a local tracking branch when
     * one does not exist yet. If the branch already exists locally it is
     * switched to; otherwise it is created from {@code origin/<branch>}. This is
     * the missing step that previously left the controller stranded on the
     * default branch, causing the recovery loop.</p>
     *
     * @return {@code true} if the branch was switched to (or already current);
     *         {@code false} when {@code branch} is blank (no active branch yet).
     */
    public boolean checkoutStoryBranch(String branch) {
        if (branch == null || branch.isEmpty()) {
            return false;
        }
        if (DEFAULT_BRANCHES.contains(branch)) {
            throw new IllegalStateException(
                    "refusing to check out the default branch as a story branch: " + branch);
        }
        String current = checkedStdout("branch", "--show-current").strip();
        if (current.equals(branch)) {
            return true;
        }
        // Best-effort fetch so a tracking branch can be created; a missing
        // remote ref is not fatal when the branch already exists locally.
        runner.run(repo, List.of("fetch", "origin", branch));
        String existingLocal = checkedStdout("branch", "--list", branch).strip();
        List<String> switchArgs = existingLocal.isEmpty()
                ? List.of("checkout", "-B", branch, "origin/" + branch)
                : List.of("switch", branch);
        GitResult first = runner.run(repo, switchArgs);
        if (first.exitCode() == 0) {
            return true;
        }
        // A checkout can be blocked by local changes that "would be overwritten"
        // — typically a stray/uncommitted file left by an interrupted run. Rather
        // than fail hard (which loops the controller), stash the changes
        // non-destructively, retry the switch, and leave the stash for recovery.
        // git stash is recoverable via `git stash list` / `git stash pop`.
        String err = first.stderr() == null ? "" : first.stderr();
        boolean blockedByLocalChanges = err.contains("would be overwritten")
                || err.contains("commit your changes or stash them");
        if (!blockedByLocalChanges) {
            throw new IllegalStateException("git " + String.join(" ", switchArgs)
                    + " failed: " + err);
        }
        GitResult stash = runner.run(repo, List.of(
                "stash", "push", "--include-untracked", "-m",
                "codex-automation: auto-stash before checkout " + branch));
        if (stash.exitCode() != 0) {
            throw new IllegalStateException("unable to stash blocking local changes before "
                    + "checking out " + branch + ": " + stash.stderr());
        }
        GitResult retry = runner.run(repo, switchArgs);
        if (retry.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", switchArgs)
                    + " still failed after stashing local changes: " + retry.stderr());
        }
        return true;
    }

    /**
     * Stage (excluding .codex), commit, and push. Refuses credential-like
     * paths. Returns the new HEAD sha, or {@code null} if there is nothing to
     * commit.
     */
    public String commitAndPush(String branch, int story) {
        List<String> sourceLines = sourceChanges();
        if (sourceLines.isEmpty()) {
            return null;
        }
        List<String> protectedPaths = new ArrayList<>();
        for (String line : sourceLines) {
            String path = stripQuotes(pathOf(line));
            String lower = path.toLowerCase();
            String fileName = Path.of(lower).getFileName().toString();
            boolean isProtected = lower.startsWith(".env")
                    || lower.endsWith(".pem") || lower.endsWith(".key")
                    || lower.endsWith(".p12") || lower.endsWith(".pfx")
                    || pathParts(lower).stream().anyMatch(p -> p.equals("secrets") || p.equals("credentials"))
                    || fileName.contains("token") || fileName.contains("password")
                    || fileName.contains("credential");
            if (isProtected) {
                protectedPaths.add(path);
            }
        }
        if (!protectedPaths.isEmpty()) {
            protectedPaths.sort(String::compareTo);
            throw new IllegalStateException("refusing to stage protected or credential-like paths: "
                    + String.join(", ", protectedPaths));
        }
        checked("add", "-A", "--", ".", ":(exclude).codex");
        checked("commit", "-m", "fix: address story #" + story + " automation changes");
        pushBranch(branch);
        return checkedStdout("rev-parse", "HEAD").strip();
    }

    /** Confirm local, origin, and PR heads all reference the same commit. */
    public boolean verifyDeliveredHead(String branch, Map<String, Object> pr) {
        String local = checkedStdout("rev-parse", "HEAD").strip();
        String remoteOutput = checkedStdout("ls-remote", "origin", "refs/heads/" + branch).strip();
        String remote = remoteOutput.isEmpty() ? "" : remoteOutput.split("\\s+")[0];
        String prHead = Engine.asString(pr.get("headRefOid"));
        return !local.isEmpty() && !remote.isEmpty()
                && prHead.equals(local) && local.equals(remote);
    }

    /** Delivery verification result: verified flag plus the latest PR snapshot. */
    public record DeliveryResult(boolean verified, Map<String, Object> pr) {
    }

    /**
     * Verify delivery while allowing GitHub's PR head to converge, refreshing
     * the authoritative PR snapshot between attempts.
     */
    public DeliveryResult verifyDeliveredHeadWithRetry(String branch, GitHub github, int prNumber,
                                                       int attempts, double delaySeconds,
                                                       Sleeper sleeper) {
        Map<String, Object> latest = new LinkedHashMap<>();
        int total = Math.max(1, attempts);
        for (int attempt = 1; attempt <= total; attempt++) {
            latest = github.pr(prNumber);
            if (verifyDeliveredHead(branch, latest)) {
                return new DeliveryResult(true, latest);
            }
            if (attempt < total) {
                sleeper.sleep(Math.max(0.0, delaySeconds));
            }
        }
        return new DeliveryResult(false, latest);
    }

    /** Injectable sleep so tests do not wait in real time. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(double seconds);
    }

    /** Synchronize the base branch and create a fresh story branch. */
    public StoryBranch prepareStoryBranch(String baseBranch, int story, String title) {
        requireCleanWorktree();
        checked("fetch", "origin", baseBranch);
        String current = checkedStdout("branch", "--show-current").strip();
        if (!current.equals(baseBranch)) {
            checked("switch", baseBranch);
        }
        checked("pull", "--ff-only", "origin", baseBranch);
        String remoteHead = checkedStdout("rev-parse", "origin/" + baseBranch).strip();
        String localHead = checkedStdout("rev-parse", "HEAD").strip();
        if (!localHead.equals(remoteHead)) {
            throw new IllegalStateException("local " + baseBranch + " does not match origin/" + baseBranch);
        }
        String slug = slugify(title);
        String branch = "story/" + story + "-" + slug;
        String existing = checkedStdout("branch", "--list", branch).strip();
        if (!existing.isEmpty()) {
            throw new IllegalStateException("story branch already exists: " + branch);
        }
        checked("switch", "-c", branch);
        return new StoryBranch(branch, remoteHead);
    }

    /** Prepared story branch and the base head it was cut from. */
    public record StoryBranch(String branch, String baseHead) {
    }

    static String slugify(String title) {
        String base;
        if (title != null && !title.isEmpty()) {
            String normalized = Normalizer.normalize(title, Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}", "")
                    .replaceAll("[^\\x00-\\x7F]", "")
                    .toLowerCase();
            base = normalized;
        } else {
            base = "implementation";
        }
        String slug = SLUG_CLEAN.matcher(base).replaceAll("-");
        slug = trim(slug, '-');
        if (slug.length() > 50) {
            slug = slug.substring(0, 50);
        }
        slug = trim(slug, '-');
        return slug.isEmpty() ? "implementation" : slug;
    }

    // -- helpers -------------------------------------------------------------

    /** Porcelain path field is everything from column 3. */
    private static String pathOf(String porcelainLine) {
        return porcelainLine.length() >= 3 ? porcelainLine.substring(3) : "";
    }

    private static String stripQuotes(String value) {
        String v = value;
        if (v.startsWith("\"")) {
            v = v.substring(1);
        }
        if (v.endsWith("\"")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static List<String> pathParts(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String trim(String value, char ch) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ch) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ch) {
            end--;
        }
        return value.substring(start, end);
    }

    private static GitResult executeGit(Path repo, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
            Process process = builder.start();
            // Drain both streams concurrently to avoid a pipe-buffer deadlock
            // when git produces large output (reading one stream fully while
            // the other fills its buffer would hang).
            java.io.ByteArrayOutputStream outBuffer = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream errBuffer = new java.io.ByteArrayOutputStream();
            Thread outReader = pump(process.getInputStream(), outBuffer);
            Thread errReader = pump(process.getErrorStream(), errBuffer);
            outReader.start();
            errReader.start();
            int code = process.waitFor();
            outReader.join();
            errReader.join();
            return new GitResult(code,
                    outBuffer.toString(java.nio.charset.StandardCharsets.UTF_8),
                    errBuffer.toString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception exc) {
            throw new IllegalStateException("failed to run git " + String.join(" ", args), exc);
        }
    }

    private static Thread pump(java.io.InputStream source, java.io.ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            byte[] chunk = new byte[8192];
            int read;
            try {
                while ((read = source.read(chunk)) != -1) {
                    sink.write(chunk, 0, read);
                }
            } catch (java.io.IOException ignored) {
                // stream closed on process exit
            }
        });
        thread.setDaemon(true);
        return thread;
    }
}
