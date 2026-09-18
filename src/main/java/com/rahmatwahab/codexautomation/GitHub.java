package com.rahmatwahab.codexautomation;

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
        this(owner, name, "GH_TOKEN", "gh", 30, defaultRunner(), System::getenv);
    }

    public GitHub(String owner, String name, String tokenEnvironmentVariable, String cli, int timeoutSeconds) {
        this(owner, name, tokenEnvironmentVariable, cli, timeoutSeconds, defaultRunner(), System::getenv);
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
            result = runner.run(command, timeoutSeconds);
        } catch (CommandTimeout exc) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.error("GH CALL TIMEOUT: {} after {} ms (limit {} s)", label, elapsedMs, timeoutSeconds);
            throw new GitHubError("GitHub command timed out after " + timeoutSeconds
                    + " seconds: " + cli, exc);
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
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
            throw new GitHubError(message);
        }
        try {
            return MAPPER.readValue(result.stdout, Object.class);
        } catch (IOException notJson) {
            return result.stdout.strip();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runObject(String... args) {
        Object value = run(args);
        return value instanceof Map ? (Map<String, Object>) value : new java.util.LinkedHashMap<>();
    }

    public Object issue(int number) {
        return run("issue", "view", String.valueOf(number), "--repo", repo,
                "--json", "number,title,state,body,labels,assignees,url");
    }

    public Object prs() {
        return run("pr", "list", "--repo", repo, "--state", "all", "--limit", "100",
                "--json", "number,title,state,isDraft,mergedAt,headRefName,url,body,"
                        + "reviewDecision,statusCheckRollup");
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
                        + "reviewDecision,comments,reviews,statusCheckRollup");
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
        run("pr", "comment", String.valueOf(number), "--repo", repo, "--body", body);
    }

    public void resolveThread(String threadId) {
        String mutation = "mutation($threadId:ID!) {\n"
                + "          resolveReviewThread(input:{threadId:$threadId}) { thread { isResolved } }\n"
                + "        }";
        run("api", "graphql", "-f", "query=" + mutation, "-f", "threadId=" + threadId);
    }

    /** Reply to an existing review thread using GitHub's native mutation. */
    public void replyThread(String threadId, String body) {
        String mutation = "mutation($threadId:ID!, $body:String!) {\n"
                + "          addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:$threadId, body:$body}) {\n"
                + "            comment { id }\n"
                + "          }\n"
                + "        }";
        run("api", "graphql", "-f", "query=" + mutation,
                "-f", "threadId=" + threadId, "-f", "body=" + body);
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
            if (bodyOf(item).strip().equals(body)) {
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
            if (itemBody.strip().equals(body) && createdAt.compareTo(timestamp) >= 0) {
                return true;
            }
        }
        return false;
    }

    public Object createPr(String title, String body, String base, String head) {
        return run("pr", "create", "--repo", repo, "--base", base, "--head", head,
                "--title", title, "--body", body,
                "--json", "number,url,state,isDraft,headRefName,headRefOid");
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
        return (command, timeoutSeconds) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
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
            Thread outReader = streamPump(process.getInputStream(), outBuffer);
            Thread errReader = streamPump(process.getErrorStream(), errBuffer);
            outReader.start();
            errReader.start();
            try {
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
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
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new GitHubError("interrupted while executing command", exc);
            }
        };
    }

    /** A daemon thread that copies an input stream into a buffer until EOF. */
    private static Thread streamPump(java.io.InputStream source, java.io.ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            byte[] chunk = new byte[8192];
            int read;
            try {
                while ((read = source.read(chunk)) != -1) {
                    sink.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // Stream closed on process termination; stop draining.
            }
        });
        thread.setDaemon(true);
        return thread;
    }
}
