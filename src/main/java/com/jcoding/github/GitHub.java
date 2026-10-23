package com.jcoding.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Authenticated GitHub CLI operations and review/thread mutations.
 *
 * <p>Pure workflow logic. All
 * process execution goes through an injectable {@link CommandRunner} so tests
 * can supply canned {@code gh} responses; the production runner shells out to
 * the configured CLI. No test in this project ever contacts real GitHub.</p>
 */
public class GitHub {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GitHub.class);

    /** Result of a CLI invocation: exit code and captured streams. */
    public static final class CommandResult {
        public final int returnCode;
        public final String stdout;
        public final String stderr;

        public CommandResult(int returnCode, String stdout, String stderr) {
            this.returnCode = returnCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    /** Signals that a CLI invocation exceeded its timeout. */
    public static final class CommandTimeout extends RuntimeException {
        public CommandTimeout(String message) {
            super(message);
        }
    }

    /** Injectable process execution boundary. */
    @FunctionalInterface
    public interface CommandRunner {
        CommandResult run(List<String> command, int timeoutSeconds);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String repo;
    private final String owner;
    private final String name;
    private final String tokenEnvironmentVariable;
    private final String cli;
    private final int timeoutSeconds;
    private final CommandRunner runner;
    private final java.util.function.Function<String, String> environment;

    public GitHub(String owner, String name) {
        this(owner, name, "GH_TOKEN", "gh", 30, defaultRunner("GH_TOKEN"), System::getenv);
    }

    public GitHub(String owner, String name, String tokenEnvironmentVariable, String cli, int timeoutSeconds) {
        this(owner, name, tokenEnvironmentVariable, cli, timeoutSeconds,
                defaultRunner(tokenEnvironmentVariable), System::getenv);
    }

    public GitHub(String owner, String name, String tokenEnvironmentVariable, String cli,
                  int timeoutSeconds, CommandRunner runner,
                  java.util.function.Function<String, String> environment) {
        this.owner = owner;
        this.name = name;
        this.repo = owner + "/" + name;
        this.tokenEnvironmentVariable = tokenEnvironmentVariable;
        this.cli = cli;
        this.timeoutSeconds = timeoutSeconds;
        this.runner = runner;
        this.environment = environment;
    }

    private java.util.function.Consumer<String> mutationGuard = action -> { };
    private Runnable cancellationCheck = () -> { };
    private java.util.function.Supplier<String> intentIdentity = () -> "";

    public void intentIdentity(java.util.function.Supplier<String> identity) {
        this.intentIdentity = java.util.Objects.requireNonNull(identity);
    }

    public void cancellationCheck(Runnable check) {
        this.cancellationCheck = java.util.Objects.requireNonNull(check);
    }

    public void mutationGuard(java.util.function.Consumer<String> guard) {
        this.mutationGuard = java.util.Objects.requireNonNull(guard);
    }

    private com.jcoding.state.EffectJournal effects;
    public void effectStore(com.jcoding.state.StateStore store) { effects = new com.jcoding.state.EffectJournal(store); }

    private Object effect(String action, Map<String, Object> payload, boolean replaySafe,
                          java.util.function.Function<String, Object> observe,
                          java.util.function.Function<String, Object> mutation) {
        Map<String, Object> bound = new java.util.LinkedHashMap<>(payload);
        bound.put("repository", repo);
        String intent = intentIdentity.get();
        bound.put("intent_hash", intent);
        if (effects == null) return mutation.apply(""); // Injectable standalone adapter, controller always binds a store.
        return effects.execute(action, bound, replaySafe, observe, mutation, () -> {
            if (!intent.equals(intentIdentity.get())) throw new GitHubError("Intent changed before external effect");
            mutationGuard.accept(action);
        });
    }

    private static String marker(String key) { return "<!-- jcoding-effect:" + key + " -->"; }
    private static String marked(String body, String key) { return key.isBlank() ? body : body + "\n\n" + marker(key); }
    private static String withoutMarker(String body) { return body.replaceAll("(?s)\\s*<!-- jcoding-effect:[a-f0-9]+ -->", "").strip(); }

    private Object findComment(int issue, String key) {
        Object raw = issueComments(issue);
        if (!(raw instanceof List<?> pages)) throw new GitHubError("Incomplete comment read-back");
        for (Object page : pages) {
            if (!(page instanceof List<?> comments)) throw new GitHubError("Incomplete comment page");
            for (Object item : comments) if (bodyOf(item).contains(marker(key))) return item;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object threadEvidence(String threadId, String key, boolean resolved) {
        String query = "query($id:ID!,$after:String) { node(id:$id) { ... on PullRequestReviewThread { isResolved "
                + "comments(first:100,after:$after) { nodes { id body } pageInfo { hasNextPage endCursor } } } } }";
        String cursor = null;
        java.util.Set<String> cursors = new java.util.HashSet<>();
        for (int page = 0; page < 100; page++) {
            Map<?, ?> data = (Map<?, ?>) run("api", "graphql", "-f", "query=" + query, "-F", "id=" + threadId,
                    "-F", cursor == null ? "after=null" : "after=" + cursor);
            Map<?, ?> node = (Map<?, ?>) ((Map<?, ?>) data.get("data")).get("node");
            if (node == null) throw new GitHubError("Thread read-back is unavailable");
            if (resolved) return Boolean.TRUE.equals(node.get("isResolved")) ? Map.of("thread", threadId, "resolved", true) : null;
            Map<?, ?> comments = (Map<?, ?>) node.get("comments");
            for (Object comment : (List<?>) comments.get("nodes")) if (bodyOf(comment).contains(marker(key))) return comment;
            Map<?, ?> info = (Map<?, ?>) comments.get("pageInfo");
            if (Boolean.FALSE.equals(info.get("hasNextPage"))) return null;
            cursor = (String) info.get("endCursor");
            if (cursor == null || !cursors.add(cursor)) throw new GitHubError("Incomplete thread read-back");
        }
        throw new GitHubError("Thread read-back exceeded its page budget");
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Execute a {@code gh} command and parse JSON output, falling back to the
     * trimmed raw string when the output is not JSON. Wraps the configured CLI.
     */
    public Object run(String... args) {
        String token = environment.apply(tokenEnvironmentVariable);
        if (token == null || token.isEmpty()) {
            throw new GitHubError("GitHub token environment variable is missing: " + tokenEnvironmentVariable);
        }
        List<String> command = new ArrayList<>();
        command.add(cli);
        for (String arg : args) {
            command.add(arg);
        }
        CommandResult result;
        long startNanos = System.nanoTime();
        String label = args.length >= 2 ? args[0] + " " + args[1] : (args.length == 1 ? args[0] : cli);
        try {
            java.util.concurrent.FutureTask<CommandResult> task = new java.util.concurrent.FutureTask<>(
                    () -> runner.run(command, timeoutSeconds));
            Thread.ofVirtual().name("jcoding-gh-call").start(task);
            try {
                while (true) {
                    try {
                        cancellationCheck.run();
                        result = task.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException waiting) {
                        // Recheck cancellation without waiting for the CLI timeout.
                    }
                }
            } catch (RuntimeException cancelled) {
                task.cancel(true);
                throw cancelled;
            } catch (InterruptedException interrupted) {
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw new GitHubError("GitHub command interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new GitHubError("GitHub command failed", cause);
            }
        } catch (CommandTimeout exc) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.error("GH CALL TIMEOUT: {} after {} ms (limit {} s)", label, elapsedMs, timeoutSeconds);
            throw new GitHubError("GitHub command timed out after " + timeoutSeconds
                    + " seconds: " + cli, exc);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (result.stdout.length() > 8 * 1024 * 1024 || result.stderr.length() > 8 * 1024 * 1024) {
            throw new GitHubError("GitHub command output exceeded the 8 MiB limit");
        }
        int outSize = result.stdout.length();
        if (elapsedMs >= 5000) {
            log.warn("GH CALL SLOW: {} took {} ms (exit {}, {} bytes)", label, elapsedMs,
                    result.returnCode, outSize);
        } else {
            log.debug("GH CALL: {} took {} ms (exit {}, {} bytes)", label, elapsedMs,
                    result.returnCode, outSize);
        }
        if (result.returnCode != 0) {
            String message = !result.stderr.strip().isEmpty()
                    ? result.stderr.strip() : result.stdout.strip();
            throw new GitHubError(redact(message, token));
        }
        try {
            return MAPPER.readValue(result.stdout, Object.class);
        } catch (IOException notJson) {
            return result.stdout.strip();
        }
    }

    private static String redact(String value, String token) {
        String clean = token == null || token.isBlank() ? value : value.replace(token, "[REDACTED]");
        return clean.replaceAll("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:gh_token|github_token|password|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s]+", "$1[REDACTED]");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runObject(String... args) {
        Object value = run(args);
        return value instanceof Map ? (Map<String, Object>) value : new java.util.LinkedHashMap<>();
    }

    public Object issue(int number) {
        return run("issue", "view", String.valueOf(number), "--repo", repo,
                "--json", "number,title,state,body,labels,assignees,url,author");
    }

    /** Post a controller-authored clarification request to an issue. */
    public void commentIssue(int number, String body) {
        mutationGuard.accept("comment");
        effect("comment", Map.of("issue", number, "body", body), false,
                key -> findComment(number, key),
                key -> run("issue", "comment", String.valueOf(number), "--repo", repo, "--body", marked(body, key)));
    }

    /** Fetch issue conversation comments for idempotent recovery after a crash. */
    public Object issueComments(int number) {
        return pages("repos/" + repo + "/issues/" + number + "/comments");
    }

    /**
     * Read every page of a list endpoint as a list of pages. Pages are requested explicitly
     * instead of using {@code gh api --paginate --slurp}: {@code --slurp} is missing from older
     * gh releases (for example 2.45.0 shipped by Ubuntu 24.04), which made every EPIC read fail.
     * A short page ends the listing; an unexpected shape or exhausted budget fails closed.
     */
    private Object pages(String endpoint) {
        final int pageSize = 100;
        List<Object> result = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            Object raw = run("api", endpoint + "?per_page=" + pageSize + "&page=" + page);
            if (!(raw instanceof List<?> items)) throw new GitHubError("Malformed paged response from " + endpoint);
            result.add(items);
            if (items.size() < pageSize) return result;
        }
        throw new GitHubError("Paged read exceeded its page budget: " + endpoint);
    }

    @SuppressWarnings("unchecked")
    public Object prs() {
        String query = "query($owner:String!, $name:String!, $after:String) { repository(owner:$owner,name:$name) { "
                + "pullRequests(first:100,after:$after,orderBy:{field:UPDATED_AT,direction:DESC}) { "
                + "pageInfo { hasNextPage endCursor } nodes { number title state isDraft mergedAt headRefName headRefOid url body } } } }";
        List<Object> result = new ArrayList<>();
        java.util.Set<String> cursors = new java.util.HashSet<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            Object raw = run("api", "graphql", "-f", "query=" + query, "-F", "owner=" + owner,
                    "-F", "name=" + name, "-F", cursor == null ? "after=null" : "after=" + cursor);
            try {
                Map<?, ?> connection = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) raw).get("data"))
                        .get("repository")).get("pullRequests");
                List<?> nodes = (List<?>) connection.get("nodes");
                Map<?, ?> info = (Map<?, ?>) connection.get("pageInfo");
                if (nodes == null || info == null || !(info.get("hasNextPage") instanceof Boolean)) throw new IllegalArgumentException();
                result.addAll(nodes);
                if (Boolean.FALSE.equals(info.get("hasNextPage"))) return result;
                cursor = (String) info.get("endCursor");
                if (cursor == null || cursor.isBlank() || !cursors.add(cursor)) throw new IllegalArgumentException();
            } catch (RuntimeException exc) { throw new GitHubError("Incomplete PR discovery; refusing to select a story", exc); }
        }
        throw new GitHubError("PR discovery exceeded page limit");
    }

    /** Native child relationships are authoritative; incidental body links are not. */
    public List<Integer> childStoryNumbers(int epic) {
        Object raw = pages("repos/" + repo + "/issues/" + epic + "/sub_issues");
        if (!(raw instanceof List<?> pages)) throw new GitHubError("Malformed sub-issue response");
        List<Integer> result = new ArrayList<>();
        for (Object page : pages) {
            if (!(page instanceof List<?> issues)) throw new GitHubError("Malformed sub-issue page");
            for (Object issue : issues) {
                if (!(issue instanceof Map<?, ?> child) || !(child.get("number") instanceof Number n)) {
                    throw new GitHubError("Malformed sub-issue identity");
                }
                result.add(n.intValue());
            }
        }
        return result;
    }

    private static final String THREADS_QUERY =
            "query($owner:String!, $name:String!, $number:Int!, $after:String) {\n"
                    + "          repository(owner:$owner, name:$name) { pullRequest(number:$number) {\n"
                    + "            reviewThreads(first:100, after:$after) { pageInfo { hasNextPage endCursor } nodes { id isResolved comments(first:100) {\n"
                    + "              pageInfo { hasNextPage }\n"
                    + "              nodes { id body createdAt author { login } commit { oid } }\n"
                    + "            } } }\n"
                    + "          } }\n"
                    + "        }";

    /**
     * PR detail plus review threads fetched via GraphQL (paginated). Missing
     * thread data must never be interpreted as "no threads": on any failure the
     * PR is marked {@code reviewThreadsAvailable=false}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pr(int number) {
        Map<String, Object> data = runObject("pr", "view", String.valueOf(number), "--repo", repo,
                "--json", "number,state,isDraft,mergedAt,headRefName,headRefOid,url,"
                        + "reviewDecision,comments,reviews,statusCheckRollup,mergeable,mergeStateStatus");
        try {
            List<Object> nodes = new ArrayList<>();
            String cursor = null;
            boolean completed = false;
            for (int i = 0; i < 100; i++) {
                List<String> args = new ArrayList<>(List.of(
                        "api", "graphql", "-f", "query=" + THREADS_QUERY,
                        "-F", "owner=" + owner, "-F", "name=" + name, "-F", "number=" + number));
                args.add("-F");
                args.add(cursor == null ? "after=null" : "after=" + cursor);
                Object threadData = run(args.toArray(new String[0]));
                Map<String, Object> connection = navigateReviewThreads(threadData);
                List<Object> pageNodes = (List<Object>) connection.get("nodes");
                if (pageNodes == null) {
                    throw new GitHubError("malformed review thread response");
                }
                for (Object threadObj : pageNodes) {
                    Map<String, Object> thread = (Map<String, Object>) threadObj;
                    Object commentsObj = thread.get("comments");
                    Map<String, Object> comments = commentsObj instanceof Map
                            ? (Map<String, Object>) commentsObj : Map.of();
                    Object pageInfoObj = comments.get("pageInfo");
                    Map<String, Object> pageInfo = pageInfoObj instanceof Map
                            ? (Map<String, Object>) pageInfoObj : Map.of();
                    if (Boolean.TRUE.equals(pageInfo.get("hasNextPage"))) {
                        throw new GitHubError("review thread comments exceed the supported page size");
                    }
                }
                nodes.addAll(pageNodes);
                Map<String, Object> page = (Map<String, Object>) connection.get("pageInfo");
                if (page == null || !Boolean.TRUE.equals(page.get("hasNextPage"))) {
                    completed = true;
                    break;
                }
                cursor = (String) page.get("endCursor");
            }
            if (!completed) {
                throw new GitHubError("review thread pagination exceeded safety limit");
            }
            data.put("reviewThreads", nodes);
            data.put("reviewThreadsAvailable", true);
        } catch (GitHubError | ClassCastException | NullPointerException exc) {
            // Missing thread data must never be interpreted as no threads.
            data.put("reviewThreads", new ArrayList<>());
            data.put("reviewThreadsAvailable", false);
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigateReviewThreads(Object threadData) {
        Map<String, Object> root = (Map<String, Object>) threadData;
        Map<String, Object> dataNode = (Map<String, Object>) root.get("data");
        Map<String, Object> repository = (Map<String, Object>) dataNode.get("repository");
        Map<String, Object> pullRequest = (Map<String, Object>) repository.get("pullRequest");
        Map<String, Object> reviewThreads = (Map<String, Object>) pullRequest.get("reviewThreads");
        if (reviewThreads == null) {
            throw new GitHubError("missing reviewThreads");
        }
        return reviewThreads;
    }

    public void comment(int number, String body) {
        mutationGuard.accept("comment");
        Object head = effects == null ? "standalone" : pr(number).get("headRefOid");
        effect("comment", Map.of("pr", number, "head", head, "body", body), false,
                key -> findComment(number, key),
                key -> run("pr", "comment", String.valueOf(number), "--repo", repo, "--body", marked(body, key)));
    }

    /**
     * Merge a pull request. {@code method} selects the merge strategy
     * ("merge", "squash", or "rebase"). Used only when the fully autonomous
     * {@code workflow.auto_merge} policy is enabled; the default policy keeps
     * the manual-merge gate and never calls this.
     */
    public void mergePr(int number, String method) {
        throw new GitHubError("Merge requires an expected head SHA");
    }

    public void mergePr(int number, String method, String expectedHead) {
        mutationGuard.accept("merge");
        if (expectedHead == null || expectedHead.isBlank()) throw new GitHubError("Merge requires an expected head SHA");
        String strategy = switch (method == null ? "" : method) {
            case "squash" -> "--squash";
            case "rebase" -> "--rebase";
            default -> "--merge";
        };
        effect("merge", Map.of("pr", number, "head", expectedHead, "method", strategy), true,
                key -> { Map<String, Object> current = pr(number);
                    return "MERGED".equals(current.get("state")) || current.get("mergedAt") != null ? current : null; },
                key -> run("pr", "merge", String.valueOf(number), "--repo", repo, strategy, "--match-head-commit", expectedHead));
    }

    public void resolveThread(String threadId) {
        mutationGuard.accept("resolve");
        String mutation = "mutation($threadId:ID!) {\n"
                + "          resolveReviewThread(input:{threadId:$threadId}) { thread { isResolved } }\n"
                + "        }";
        effect("resolve", Map.of("thread", threadId), true,
                key -> threadEvidence(threadId, key, true),
                key -> run("api", "graphql", "-f", "query=" + mutation, "-f", "threadId=" + threadId));
    }

    /** Reply to an existing review thread using GitHub's native mutation. */
    public void replyThread(String threadId, String body) {
        mutationGuard.accept("reply");
        String mutation = "mutation($threadId:ID!, $body:String!) {\n"
                + "          addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:$threadId, body:$body}) {\n"
                + "            comment { id }\n"
                + "          }\n"
                + "        }";
        effect("reply", Map.of("thread", threadId, "body", body), false,
                key -> threadEvidence(threadId, key, false),
                key -> run("api", "graphql", "-f", "query=" + mutation,
                        "-f", "threadId=" + threadId, "-f", "body=" + marked(body, key)));
    }

    /**
     * Whether any normal PR conversation comment contains the marker. A review
     * body may quote a trigger without being the authoritative issue-comment.
     */
    @SuppressWarnings("unchecked")
    public boolean hasComment(Map<String, Object> pr, String marker) {
        for (Object item : commentList(pr)) {
            String body = bodyOf(item);
            if (body.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasExactComment(Map<String, Object> pr, String body) {
        for (Object item : commentList(pr)) {
            if (withoutMarker(bodyOf(item)).equals(body)) {
                return true;
            }
        }
        return false;
    }

    /** Find a comment posted after a durable pre-call intent marker. */
    public boolean hasExactCommentAfter(Map<String, Object> pr, String body, String timestamp) {
        for (Object item : commentList(pr)) {
            if (!(item instanceof Map<?, ?> comment)) {
                continue;
            }
            String itemBody = bodyOf(item);
            String createdAt = comment.get("createdAt") == null ? "" : String.valueOf(comment.get("createdAt"));
            if (withoutMarker(itemBody).equals(body) && createdAt.compareTo(timestamp) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create a pull request and return its authoritative detail map.
     *
     * <p>{@code gh pr create} does not support {@code --json} on all supported
     * gh versions, so we create the PR (which prints the PR URL to stdout) and
     * then fetch the structured detail with {@code gh pr view --json}. This
     * avoids the "unknown flag: --json" failure on {@code gh pr create}.</p>
     */
    public Object createPr(String title, String body, String base, String head) {
        mutationGuard.accept("create_pr");
        return effect("create_pr", Map.of("title", title, "body", body, "base", base, "head", head), false,
                key -> findCreatedPr(head, base, key), key -> createPrOnce(title, marked(body, key), base, head));
    }

    private Object findCreatedPr(String head, String base, String key) {
        Object raw = run("pr", "list", "--repo", repo, "--state", "all", "--head", head, "--base", base,
                "--limit", "100", "--json", "number,body");
        if (!(raw instanceof List<?> prs)) throw new GitHubError("Cannot reconcile PR creation");
        for (Object rawPr : prs) if (rawPr instanceof Map<?, ?> pr && bodyOf(pr).contains(marker(key))) {
            return pr(((Number) pr.get("number")).intValue());
        }
        if (prs.size() >= 100) throw new GitHubError("PR creation read-back may be truncated");
        return null;
    }

    private Object createPrOnce(String title, String body, String base, String head) {
        Object result = run("pr", "create", "--repo", repo, "--base", base, "--head", head,
                "--title", title, "--body", body);
        String output = result instanceof Map ? String.valueOf(((Map<?, ?>) result).get("url"))
                : String.valueOf(result);
        int number = parsePrNumberFromUrl(output);
        if (number <= 0) {
            throw new GitHubError("PR created but its number could not be parsed from gh output: "
                    + output);
        }
        return pr(number);
    }

    /** Extract the trailing PR number from a gh pr create URL/output. */
    static int parsePrNumberFromUrl(String output) {
        if (output == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/pull/(\\d+)").matcher(output);
        int number = -1;
        while (m.find()) {
            number = Integer.parseInt(m.group(1));
        }
        return number;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> commentList(Map<String, Object> pr) {
        Object comments = pr.get("comments");
        return comments instanceof List ? (List<Object>) comments : List.of();
    }

    private static String bodyOf(Object item) {
        if (item instanceof Map<?, ?> comment) {
            Object body = comment.get("body");
            return body == null ? "" : String.valueOf(body);
        }
        return "";
    }

    /** Production runner: shells out to the configured CLI with a timeout. */
    static CommandRunner defaultRunner() {
        return defaultRunner("GH_TOKEN");
    }

    static CommandRunner defaultRunner(String tokenName) {
        return (command, timeoutSeconds) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> env = builder.environment();
            env.clear();
            for (String key : List.of("PATH", "LANG", "LC_ALL", "GH_HOST")) {
                String value = System.getenv(key);
                if (value != null) env.put(key, value);
            }
            String token = System.getenv(tokenName);
            if (token != null) env.put("GH_TOKEN", token);
            env.put("HOME", "/nonexistent");
            env.put("GH_PROMPT_DISABLED", "1");
            Process process;
            try {
                process = builder.start();
            } catch (IOException exc) {
                throw new GitHubError("failed to execute command: " + command.get(0), exc);
            }
            // Drain stdout and stderr concurrently. A subprocess that produces
            // more output than the OS pipe buffer holds (e.g. a large EPIC body)
            // blocks on write until its streams are read; reading only after
            // waitFor() would deadlock. Reader threads consume in parallel.
            java.io.ByteArrayOutputStream outBuffer = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream errBuffer = new java.io.ByteArrayOutputStream();
            java.util.concurrent.atomic.AtomicBoolean overflow = new java.util.concurrent.atomic.AtomicBoolean();
            Thread outReader = streamPump(process.getInputStream(), outBuffer, overflow);
            Thread errReader = streamPump(process.getErrorStream(), errBuffer, overflow);
            outReader.start();
            errReader.start();
            try {
                long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
                boolean finished = false;
                while (System.nanoTime() < deadline && !overflow.get()) {
                    if (process.waitFor(100, TimeUnit.MILLISECONDS)) { finished = true; break; }
                }
                if (overflow.get()) {
                    killTree(process);
                    throw new GitHubError("GitHub command output exceeded 8 MiB");
                }
                if (!finished) {
                    killTree(process);
                    process.waitFor();
                    throw new CommandTimeout("command timed out");
                }
                // Ensure all buffered output is fully consumed after exit.
                outReader.join(TimeUnit.SECONDS.toMillis(5));
                errReader.join(TimeUnit.SECONDS.toMillis(5));
                return new CommandResult(process.exitValue(),
                        outBuffer.toString(StandardCharsets.UTF_8),
                        errBuffer.toString(StandardCharsets.UTF_8));
            } catch (InterruptedException exc) {
                killTree(process);
                Thread.currentThread().interrupt();
                throw new GitHubError("interrupted while executing command", exc);
            }
        };
    }

    private static void killTree(Process process) {
        try (var descendants = process.descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        process.destroyForcibly();
    }

    /** A daemon thread that copies an input stream into a buffer until EOF. */
    private static Thread streamPump(java.io.InputStream source, java.io.ByteArrayOutputStream sink,
                                     java.util.concurrent.atomic.AtomicBoolean overflow) {
        Thread thread = new Thread(() -> {
            byte[] chunk = new byte[8192];
            int read;
            try {
                while ((read = source.read(chunk)) != -1) {
                    synchronized (sink) {
                        int remaining = 8 * 1024 * 1024 - sink.size();
                        if (read > remaining) {
                            if (remaining > 0) sink.write(chunk, 0, remaining);
                            overflow.set(true);
                            return;
                        }
                        sink.write(chunk, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // Stream closed on process termination; stop draining.
            }
        });
        thread.setDaemon(true);
        return thread;
    }
}
