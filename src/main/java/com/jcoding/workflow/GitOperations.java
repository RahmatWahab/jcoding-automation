package com.jcoding.workflow;
import com.jcoding.github.GitHub;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
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
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "java", "kt", "py", "sh", "bash", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h", "cpp");

    /** A local policy rejection, distinct from authentication/network failures. */
    public static final class ProtectedPathsException extends IllegalStateException {
        private final List<String> paths;

        ProtectedPathsException(List<String> paths) {
            super("refusing to stage protected or credential-like paths: " + String.join(", ", paths));
            this.paths = List.copyOf(paths);
        }

        public List<String> paths() {
            return paths;
        }
    }

    private final Path repo;
    private final GitRunner runner;
    private Runnable mutationGuard = () -> { };
    private Runnable cancellationCheck = () -> { };
    private java.util.function.Supplier<String> intentIdentity = () -> "";
    private List<Object> protectedGlobs = List.of(".github/**", "config/**", "prompts/**");
    private Set<String> authorizedPaths;
    private String credentialEnvironmentVariable = "GH_TOKEN";
    private String githubHost = "github.com";
    private String expectedRepositoryPath = "";

    private com.jcoding.state.EffectJournal effects;
    public GitOperations effectStore(com.jcoding.state.StateStore store) {
        effects = new com.jcoding.state.EffectJournal(store);
        return this;
    }

    public GitOperations mutationGuard(Runnable guard) {
        this.mutationGuard = guard;
        return this;
    }

    public GitOperations cancellationCheck(Runnable check) {
        this.cancellationCheck = java.util.Objects.requireNonNull(check);
        return this;
    }

    public GitOperations intentIdentity(java.util.function.Supplier<String> identity) {
        this.intentIdentity = java.util.Objects.requireNonNull(identity);
        return this;
    }

    public GitOperations protectedPaths(List<Object> globs) {
        this.protectedGlobs = List.copyOf(globs);
        return this;
    }

    /** Controller-owned paths in this story checkout; null is reserved for isolated unit fixtures. */
    public GitOperations authorizedPaths(Set<String> paths) {
        this.authorizedPaths = new java.util.HashSet<>(paths);
        return this;
    }

    public GitOperations githubCredentials(String environmentVariable, String host, String repositoryPath) {
        if (environmentVariable == null || !environmentVariable.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid GitHub credential environment variable");
        if (host == null || !host.matches("[A-Za-z0-9.-]+") || repositoryPath == null
                || (!repositoryPath.isBlank() && !repositoryPath.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
            throw new IllegalArgumentException("Invalid GitHub remote identity");
        credentialEnvironmentVariable = environmentVariable;
        githubHost = host.toLowerCase(Locale.ROOT);
        expectedRepositoryPath = repositoryPath;
        return this;
    }

    public GitOperations authorizeToolPaths(List<String> paths) {
        if (authorizedPaths == null) authorizedPaths = new java.util.HashSet<>();
        authorizedPaths.addAll(paths);
        return this;
    }

    public GitOperations(Path repo, GitRunner runner) {
        this.repo = repo;
        this.runner = runner;
    }

    private GitOperations(Path repo) {
        this.repo = repo;
        this.runner = (checkout, args) -> executeGit(checkout, args, cancellationCheck,
                credentialEnvironmentVariable, githubHost, expectedRepositoryPath);
    }

    /** Production runner shelling out to {@code git} with check=true semantics. */
    public static GitOperations forRepo(Path repo) {
        return new GitOperations(repo);
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
        String branch = currentBranchName();
        if (branch.isEmpty() || DEFAULT_BRANCHES.contains(branch)) {
            throw new IllegalStateException("Codex must work on a non-default feature branch");
        }
        return branch;
    }

    /** Return the current branch without applying the feature-branch guard. */
    public String currentBranchName() {
        return checkedStdout("branch", "--show-current").strip();
    }

    public String head() {
        return checkedStdout("rev-parse", "HEAD").strip();
    }

    /** Canonical Git identity and checkout root, queried through the constrained Git runner. */
    public String repositoryTopLevel() {
        return checkedStdout("rev-parse", "--show-toplevel").strip();
    }

    public String commonGitDirectory() {
        Path value = Path.of(checkedStdout("rev-parse", "--path-format=absolute", "--git-common-dir").strip());
        try { return value.toRealPath().toString(); }
        catch (java.io.IOException exc) { throw new IllegalStateException("Cannot identify Git common directory", exc); }
    }

    /** Create a detached worktree without switching or cleaning the operator checkout. */
    public void addDetachedWorktree(Path target) {
        mutationGuard.run();
        if (Files.exists(target) || target.toAbsolutePath().normalize().startsWith(repo.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Worktree path must be a new directory outside the operator checkout");
        }
        checked("worktree", "add", "--detach", target.toAbsolutePath().normalize().toString(), "HEAD");
    }

    /** Remove a registered worktree without recursively deleting arbitrary paths. */
    public void removeWorktree(Path target) {
        Path expected = Path.of(repositoryTopLevel()).toAbsolutePath().normalize();
        Path candidate = target.toAbsolutePath().normalize();
        if (candidate.equals(expected) || !candidate.startsWith(expected.getParent())
                || candidate.getParent() == null || candidate.getParent().getParent() == null
                || !candidate.getParent().getParent().getFileName().toString().equals(".jcoding-worktrees"))
            throw new IllegalArgumentException("Refusing worktree removal outside sibling checkout scope");
        mutationGuard.run();
        cancellationCheck.run();
        checked("worktree", "remove", "--force", candidate.toString());
    }

    /** Keep a stash under a dedicated ref, including across a crash after stash push. */
    public String preserveWorktree(String ref) {
        mutationGuard.run();
        if (!ref.startsWith("refs/jcoding/checkpoints/")) {
            throw new IllegalArgumentException("invalid checkpoint ref");
        }
        GitResult existing = runner.run(repo, List.of("rev-parse", "--verify", ref));
        if (existing.exitCode() == 0 && !existing.stdout().isBlank()) {
            requireCleanWorktree();
            return existing.stdout().strip();
        }
        String sha = "";
        for (String entry : checkedStdout("stash", "list", "--format=%H %gs").split("\n")) {
            if (entry.endsWith(": " + ref)) {
                sha = entry.split(" ", 2)[0];
                break;
            }
        }
        if (sha.isEmpty()) {
            if (sourceChanges().isEmpty()) throw new IllegalStateException("checkpoint content is missing: " + ref);
            checked("stash", "push", "--include-untracked", "-m", ref,
                    "--", ".", ":(exclude).codex");
            sha = checkedStdout("rev-parse", "refs/stash").strip();
        }
        checked("update-ref", ref, sha);
        requireCleanWorktree();
        return sha;
    }

    /** Apply without dropping the durable backup; preserve staged vs unstaged work. */
    public void restoreWorktree(String ref) {
        mutationGuard.run();
        requireCleanWorktree();
        checked("stash", "apply", "--index", ref);
    }

    /**
     * Recover a branch lost from durable state after a crash between branch
     * creation and state persistence. This is deliberately fail-closed: only a
     * branch generated for the currently selected story may be adopted.
     */
    public boolean recoverMissingStoryBranch(Map<String, Object> state) {
        Object recorded = state.get("branch");
        Object story = state.get("current_story");
        if (recorded != null && !String.valueOf(recorded).isBlank()
                || story == null || String.valueOf(story).isBlank()) {
            return false;
        }
        int storyNumber;
        try {
            storyNumber = Engine.asInt(story);
        } catch (RuntimeException exc) {
            return false;
        }
        String branch = currentBranchName();
        String prefix = "story/" + storyNumber + "-";
        if (branch.isEmpty() || DEFAULT_BRANCHES.contains(branch)
                || !branch.startsWith(prefix) || branch.length() == prefix.length()) {
            return false;
        }
        state.put("branch", branch);
        return true;
    }

    private record Change(String status, String path, String oldPath) { }

    /** NUL porcelain is mandatory: quoting and line splitting are unsafe for paths. */
    private List<Change> sourceChanges() {
        String out = checkedStdout("status", "--porcelain=v1", "-z", "--untracked-files=all");
        List<Change> changes = new ArrayList<>();
        int cursor = 0;
        while (cursor < out.length()) {
            int end = out.indexOf('\0', cursor);
            if (end < 0) throw new IllegalStateException("Malformed NUL-delimited git status");
            String entry = out.substring(cursor, end);
            cursor = end + 1;
            if (entry.length() < 4 || entry.charAt(2) != ' ') {
                throw new IllegalStateException("Malformed git status record");
            }
            String status = entry.substring(0, 2);
            String path = entry.substring(3);
            String oldPath = "";
            if (status.indexOf('R') >= 0 || status.indexOf('C') >= 0) {
                end = out.indexOf('\0', cursor);
                if (end < 0) throw new IllegalStateException("Malformed rename status record");
                oldPath = out.substring(cursor, end);
                cursor = end + 1;
            }
            if (!path.startsWith(".codex/") || !oldPath.isEmpty()) {
                changes.add(new Change(status, path, oldPath));
            }
        }
        return changes;
    }

    /**
     * The set of changed source file paths in the working tree (ignoring
     * {@code .codex/} metadata). Read-only; used to select which specialist
     * reviewers are relevant to the current diff. Never throws for a clean tree
     * (returns an empty list).
     */
    public List<String> changedSourcePaths() {
        List<String> paths = new ArrayList<>();
        for (Change change : sourceChanges()) {
            String path = change.path();
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** Bounded portable local delivery manifest, including untracked files and deletion/rename metadata. */
    public List<Map<String, Object>> localChangeManifest() {
        List<Change> changes = sourceChanges();
        if (changes.size() > 100) throw new IllegalStateException("Local delivery exceeds 100 changed paths");
        validateStagingPaths(changes);
        List<Map<String, Object>> manifest = new ArrayList<>();
        long total = 0;
        for (Change change : changes) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("status", change.status());
            record.put("path", change.path());
            record.put("old_path", change.oldPath());
            Path file = repo.resolve(change.path()).normalize();
            if (!file.startsWith(repo.toAbsolutePath().normalize())) throw new IllegalStateException("Path escapes checkout");
            if (Files.exists(file) && Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                try {
                    long size = Files.size(file);
                    total += size;
                    if (size > 2_000_000 || total > 10_000_000) throw new IllegalStateException("Local delivery content exceeds limit");
                    byte[] content = Files.readAllBytes(file);
                    if (secretLike(new String(content, StandardCharsets.UTF_8)))
                        throw new ProtectedPathsException(List.of(change.path()));
                    record.put("content_base64", java.util.Base64.getEncoder().encodeToString(content));
                    record.put("sha256", WorkerToolBoundary.sha256(content));
                } catch (java.io.IOException exc) { throw new IllegalStateException("Cannot package changed file", exc); }
            } else {
                record.put("deleted", true);
            }
            manifest.add(record);
        }
        return List.copyOf(manifest);
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
        List<Change> changes = sourceChanges();
        if (changes.isEmpty()) {
            return WorktreeState.CLEAN;
        }
        String branch = currentBranchName();
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
        List<Change> changes = sourceChanges();
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
        validateStagingPaths(changes);
        TreeSet<String> files = new TreeSet<>();
        for (Change change : changes) {
            files.add(change.path());
        }
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("branch", branch);
        recovery.put("story", Engine.asInt(story));
        recovery.put("files", new ArrayList<>(files));
        recovery.put("action", "RESUME_CONTROLLER_OWNED_WORKTREE");
        state.put("worktree_recovery", recovery);
    }

    public void pushBranch(String branch) {
        mutationGuard.run();
        String intent = intentIdentity.get();
        if (effects == null) { checked("push", "--set-upstream", "origin", branch); return; }
        String expected = checkedStdout("rev-parse", "HEAD").strip();
        if (expected.isBlank()) throw new IllegalStateException("Push requires a verified local head");
        effects.execute("push", Map.of("repository", repo.toAbsolutePath().normalize().toString(), "branch", branch,
                        "head", expected, "intent_hash", intent), true,
                key -> {
                    String remote = checkedStdout("ls-remote", "origin", "refs/heads/" + branch).strip();
                    return !remote.isEmpty() && expected.equals(remote.split("\\s+")[0]) ? Map.of("head", expected) : null;
                }, key -> {
                    if (!intent.equals(intentIdentity.get())) throw new IllegalStateException("Intent changed before push");
                    if (!expected.equals(checkedStdout("rev-parse", "HEAD").strip())) throw new IllegalStateException("Head changed before push");
                    checked("push", "--set-upstream", "origin", branch);
                    return expected;
                }, mutationGuard);
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
        mutationGuard.run();
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
        requireCleanWorktree();
        String existingLocal = checkedStdout("branch", "--list", branch).strip();
        // Reuse a local story branch without contacting its remote. Fetch only
        // when a new local tracking branch must be created.
        if (!existingLocal.isEmpty()) {
            GitResult switched = runner.run(repo, List.of("switch", branch));
            if (switched.exitCode() == 0) return true;
            throw new IllegalStateException("git switch " + branch + " failed: " + switched.stderr());
        }
        runner.run(repo, List.of("fetch", "origin", branch));
        List<String> switchArgs = List.of("checkout", "-B", branch, "origin/" + branch);
        GitResult first = runner.run(repo, switchArgs);
        if (first.exitCode() == 0) {
            return true;
        }
        throw new IllegalStateException("git " + String.join(" ", switchArgs)
                + " failed: " + first.stderr());
    }

    /**
     * Stage (excluding .codex), commit, and push. Refuses credential-like
     * paths. Returns the new HEAD sha, or {@code null} if there is nothing to
     * commit.
     */
    public String commitAndPush(String branch, int story) {
        mutationGuard.run();
        List<Change> sourceLines = sourceChanges();
        if (sourceLines.isEmpty()) {
            // A process may have died after commit but before push or state save.
            // Reconcile the remote head on every clean-tree delivery attempt.
            if (effects != null) pushBranch(branch);
            return null;
        }
        validateStagingPaths(sourceLines);
        if (!gitBranch().equals(branch)) throw new IllegalStateException("Story branch changed before delivery");
        List<String> add = new ArrayList<>(List.of("add", "-A", "--"));
        for (Change change : sourceLines) {
            add.add(change.path());
            if (!change.oldPath().isEmpty()) add.add(change.oldPath());
        }
        checked(add.toArray(String[]::new));
        validateStagedIndex(sourceLines);
        mutationGuard.run();
        if (effects == null) {
            checked("commit", "-m", "fix: address story #" + story + " automation changes");
        } else {
            String intent = intentIdentity.get();
            String parent = checkedStdout("rev-parse", "HEAD").strip();
            String tree = checkedStdout("write-tree").strip();
            String message = "fix: address story #" + story + " automation changes";
            effects.execute("commit", Map.of("repository", repo.toAbsolutePath().normalize().toString(),
                            "branch", branch, "story", story, "parent", parent, "tree", tree,
                            "intent_hash", intent), true,
                    key -> {
                        String head = checkedStdout("rev-parse", "HEAD").strip();
                        String actualParent = checkedStdout("show", "-s", "--format=%P", "HEAD").strip();
                        String actualTree = checkedStdout("rev-parse", "HEAD^{tree}").strip();
                        return parent.equals(actualParent) && tree.equals(actualTree) ? Map.of("head", head) : null;
                    }, key -> {
                        if (!intent.equals(intentIdentity.get())) throw new IllegalStateException("Intent changed before commit");
                        checked("commit", "-m", message); return checkedStdout("rev-parse", "HEAD").strip();
                    },
                    mutationGuard);
        }
        pushBranch(branch);
        return checkedStdout("rev-parse", "HEAD").strip();
    }

    /** Cheap read-only preflight, also used before expensive specialist reviews. */
    public void validateStagingPaths() {
        validateStagingPaths(sourceChanges());
    }

    private void validateStagingPaths(List<Change> sourceLines) {
        List<String> protectedPaths = new ArrayList<>();
        for (Change change : sourceLines) {
            for (String path : List.of(change.path(), change.oldPath())) {
            if (path.isEmpty()) continue;
            if (path.indexOf('\0') >= 0 || Path.of(path).isAbsolute() || path.equals("..")
                    || path.startsWith("../") || path.contains("/../")
                    || !repo.toAbsolutePath().normalize().resolve(path).normalize()
                            .startsWith(repo.toAbsolutePath().normalize())) {
                protectedPaths.add(path);
                continue;
            }
            if (authorizedPaths != null && !authorizedPaths.contains(path)) {
                protectedPaths.add(path);
                continue;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            String fileName = Path.of(lower).getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            boolean sourceFile = dot > 0 && SOURCE_EXTENSIONS.contains(fileName.substring(dot + 1))
                    && (lower.startsWith("src/") || lower.startsWith("tests/")
                        || lower.startsWith("test/") || lower.startsWith("scripts/"))
                    && !Files.isSymbolicLink(repo.resolve(path));
            // Source about credentials is different from credential storage. Hard
            // secret locations/extensions always win, even inside tests/ or src/.
            boolean isProtected = RuntimeVerifier.matches(path.replace('\\', '/'), protectedGlobs)
                    || pathParts(lower).stream().anyMatch(p -> p.startsWith(".env"))
                    || lower.equals(".npmrc") || lower.endsWith("/.npmrc")
                    || lower.equals("config/prod.json") || lower.startsWith(".github/")
                    || lower.endsWith(".pem") || lower.endsWith(".key")
                    || lower.endsWith(".p12") || lower.endsWith(".pfx")
                    || pathParts(lower).stream().anyMatch(p -> p.equals("secrets") || p.equals("credentials"))
                    || ((fileName.contains("token") || fileName.contains("password")
                        || fileName.contains("credential")) && !sourceFile);
            if (isProtected || Files.isSymbolicLink(repo.resolve(path))) {
                protectedPaths.add(path);
            }
            }
        }
        if (!protectedPaths.isEmpty()) {
            protectedPaths.sort(String::compareTo);
            throw new ProtectedPathsException(protectedPaths);
        }
    }

    private void validateStagedIndex(List<Change> expected) {
        String output = checkedStdout("diff", "--cached", "--name-only", "-z");
        Set<String> permitted = new java.util.HashSet<>();
        for (Change change : expected) {
            permitted.add(change.path());
            if (!change.oldPath().isEmpty()) permitted.add(change.oldPath());
        }
        for (String path : output.split("\0", -1)) {
            if (!path.isEmpty() && !permitted.contains(path)) {
                throw new ProtectedPathsException(List.of(path));
            }
        }
        if (output.isEmpty()) throw new IllegalStateException("No staged changes after explicit staging");
        String modes = checkedStdout("ls-files", "--stage", "-z");
        for (String entry : modes.split("\0", -1)) {
            if (entry.isEmpty()) continue;
            int tab = entry.indexOf('\t');
            if (tab < 0) throw new IllegalStateException("Malformed staged index");
            String path = entry.substring(tab + 1);
            if (!permitted.contains(path)) continue;
            if (entry.startsWith("120000 ") || entry.startsWith("160000 ")) {
                throw new ProtectedPathsException(List.of(path));
            }
            Path file = repo.resolve(path);
            if (Files.isRegularFile(file)) {
                try {
                    if (Files.size(file) > 2_000_000) throw new ProtectedPathsException(List.of(path));
                    String content = checkedStdout("show", ":" + path);
                    if (content.length() > 2_000_000) throw new ProtectedPathsException(List.of(path));
                    if (secretLike(content)) {
                        throw new ProtectedPathsException(List.of(path));
                    }
                } catch (java.io.IOException exc) {
                    throw new IllegalStateException("Cannot inspect staged file: " + path, exc);
                }
            }
        }
    }

    private static boolean secretLike(String content) {
        if (Pattern.compile("(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                + "github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|"
                + "sk-[A-Za-z0-9_-]{20,}").matcher(content).find()) return true;
        for (Map.Entry<String, String> secret : System.getenv().entrySet()) {
            String name = secret.getKey().toUpperCase(Locale.ROOT);
            String value = secret.getValue();
            if ((name.endsWith("TOKEN") || name.endsWith("SECRET")
                    || name.endsWith("PASSWORD") || name.endsWith("API_KEY"))
                    && value != null && value.length() >= 12 && content.contains(value)) return true;
        }
        return false;
    }

    /** Opaque evidence for waking a held recovery; never stores file contents. */
    public String recoveryFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(checkedStdout("rev-parse", "HEAD").getBytes(StandardCharsets.UTF_8));
            digest.update(checkedStdout("diff", "--binary", "HEAD", "--", ".", ":(exclude).codex")
                    .getBytes(StandardCharsets.UTF_8));
            for (Change change : sourceChanges()) {
                digest.update((change.status() + change.path() + change.oldPath()).getBytes(StandardCharsets.UTF_8));
                Path file = repo.resolve(change.path());
                if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
                    try (var input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            digest.update(buffer, 0, count);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exc) {
            // Missing evidence must not repeatedly reset the retry budget.
            return "unavailable";
        }
    }

    public void updateFromBase(String baseBranch) {
        mutationGuard.run();
        gitBranch();
        requireCleanWorktree();
        checked("fetch", "origin", baseBranch);
        checked("merge", "--no-commit", "--no-ff", "origin/" + baseBranch);
    }

    public List<String> changedPathsSince(String base) {
        Set<String> paths = new TreeSet<>(changedSourcePaths());
        if (base != null && !base.isBlank()) {
            String changed = checkedStdout("diff", "--name-only", "-z", base, "--");
            for (String path : changed.split("\0", -1)) if (!path.isEmpty()) paths.add(path);
        }
        return List.copyOf(paths);
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
        mutationGuard.run();
        requireCleanWorktree();
        checked("fetch", "origin", baseBranch);
        String current = checkedStdout("branch", "--show-current").strip();
        String remoteHead = checkedStdout("rev-parse", "origin/" + baseBranch).strip();
        if (!current.isBlank() && !current.equals(baseBranch))
            throw new IllegalStateException("Story preparation requires detached or base checkout");
        if (current.equals(baseBranch) && !checkedStdout("rev-parse", "HEAD").strip().equals(remoteHead))
            throw new IllegalStateException("local " + baseBranch + " does not match origin/" + baseBranch);
        String slug = slugify(title);
        String branch = "story/" + story + "-" + slug;
        String existing = checkedStdout("branch", "--list", branch).strip();
        if (!existing.isEmpty()) {
            throw new IllegalStateException("story branch already exists: " + branch);
        }
        checked("switch", "-c", branch, "origin/" + baseBranch);
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

    private static GitResult executeGit(Path repo, List<String> args, Runnable cancellationCheck,
                                        String tokenName, String expectedHost, String expectedPath) {
        Path checkout = repo.toAbsolutePath().normalize();
        if (!Files.isDirectory(checkout) || !Files.exists(checkout.resolve(".git"))) {
            throw new IllegalStateException("Git command requires the configured checkout");
        }
        Path binary;
        try { binary = Path.of("/usr/bin/git").toRealPath(); }
        catch (java.io.IOException exc) { throw new IllegalStateException("Trusted Git executable is unavailable", exc); }
        if (args.size() == 4 && "worktree".equals(args.get(0)) && "remove".equals(args.get(1))
                && "--force".equals(args.get(2))) {
            Path candidate = Path.of(args.get(3)).toAbsolutePath().normalize();
            Path top = Path.of(checkedRepositoryTop(binary, checkout, cancellationCheck)).toAbsolutePath().normalize();
            if (!candidate.startsWith(top.getParent()) || candidate.equals(top) || Files.isSymbolicLink(candidate))
                throw new IllegalArgumentException("Git worktree removal path is outside the registered sibling scope");
        }
        boolean remoteEffect = !args.isEmpty() && Set.of("fetch", "push", "ls-remote").contains(args.getFirst());
        if (remoteEffect) validateGitHubRemote(binary, checkout, expectedHost, expectedPath, cancellationCheck);
        List<String> command = new ArrayList<>(List.of(binary.toString(), "-c", "core.hooksPath=/dev/null",
                "-c", "protocol.ext.allow=never", "-c", "protocol.file.allow=never",
                "-c", "credential.helper="));
        if (remoteEffect) {
            command.addAll(List.of("-c", "credential.helper=!f() { [ \"$1\" = get ] || exit 0; "
                    + "printf 'username=x-access-token\\npassword=%s\\n\\n' \"$JCODING_GIT_TOKEN\"; }"));
        }
        command.addAll(args);
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : List.of("LANG", "LC_ALL", "TERM")) {
            String value = System.getenv(key);
            if (value != null) env.put(key, value);
        }
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", "/nonexistent");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_TERMINAL_PROMPT", "0");
        String token = remoteEffect ? System.getenv(tokenName) : null;
        if (remoteEffect) {
            if (token == null || token.isBlank()) throw new IllegalStateException("GitHub push credential is unavailable");
            env.put("JCODING_GIT_TOKEN", token);
        }
        BoundedProcess.Result result = BoundedProcess.run(command, checkout, env, 120, cancellationCheck);
        if (result.truncated()) throw new IllegalStateException("Git output exceeds safe parsing limit");
        String output = token == null ? result.output() : result.output().replace(token, "[REDACTED]");
        return new GitResult(result.exitCode(), result.exitCode() == 0 ? output : "",
                result.exitCode() == 0 ? "" : output);
    }

    private static String checkedRepositoryTop(Path binary, Path checkout, Runnable cancellationCheck) {
        List<String> command = List.of(binary.toString(), "-c", "protocol.ext.allow=never",
                "-c", "protocol.file.allow=never", "rev-parse", "--show-toplevel");
        BoundedProcess.Result result = BoundedProcess.run(command, checkout,
                Map.of("PATH", "/usr/bin:/bin", "HOME", "/nonexistent", "GIT_CONFIG_NOSYSTEM", "1",
                        "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_TERMINAL_PROMPT", "0"), 10, cancellationCheck);
        if (result.exitCode() != 0 || result.truncated()) throw new IllegalStateException("Cannot identify Git checkout root");
        return result.output().strip();
    }

    private static void validateGitHubRemote(Path binary, Path checkout, String host, String expectedPath,
                                             Runnable cancellationCheck) {
        List<String> command = List.of(binary.toString(), "-c", "protocol.ext.allow=never", "-c",
                "protocol.file.allow=never", "remote", "get-url", "origin");
        BoundedProcess.Result result = BoundedProcess.run(command, checkout, Map.of("PATH", "/usr/bin:/bin",
                "HOME", "/nonexistent", "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_TERMINAL_PROMPT", "0"), 10, cancellationCheck);
        if (result.exitCode() != 0 || result.truncated()) throw new IllegalStateException("Cannot validate GitHub origin before network operation");
        try {
            java.net.URI remote = java.net.URI.create(result.output().strip());
            String path = remote.getPath() == null ? "" : remote.getPath().replaceFirst("^/", "").replaceFirst("[.]git$", "");
            if (!"https".equalsIgnoreCase(remote.getScheme()) || !host.equalsIgnoreCase(remote.getHost())
                    || remote.getUserInfo() != null || remote.getPort() != -1 || remote.getQuery() != null
                    || remote.getFragment() != null || !expectedPath.equalsIgnoreCase(path)) {
                throw new IllegalStateException("Git origin differs from the configured GitHub repository");
            }
        } catch (IllegalArgumentException exc) {
            throw new IllegalStateException("Git origin is not a valid scoped HTTPS GitHub URL", exc);
        }
    }
}
