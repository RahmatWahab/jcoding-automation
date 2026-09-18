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
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            int code = process.waitFor();
            return new GitResult(code, stdout, stderr);
        } catch (Exception exc) {
            throw new IllegalStateException("failed to run git " + String.join(" ", args), exc);
        }
    }
}
