package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GitHub-wrapper tests. Every test injects a fake command runner and a fake
 * environment; no real {@code gh} process and no network are used. Assertions
 * check concrete parsed values, timeout classification, GraphQL field usage,
 * and fail-closed review-thread behavior.
 */
class GitHubTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** GitHub instance whose token env is present and whose runner is supplied. */
    private static GitHub withRunner(GitHub.CommandRunner runner) {
        return new GitHub("owner", "repo", "GH_TOKEN", "gh", 30, runner, name -> "test-token");
    }

    // -- has_comment 

    @Test
    void commentMarkerIsIdempotent() {
        GitHub gh = new GitHub("owner", "repo");
        Map<String, Object> pr = map("comments", List.of(map("body", "READY TO MERGE")),
                "reviews", List.of());
        assertThat(gh.hasComment(pr, "READY TO MERGE")).isTrue();
        assertThat(gh.hasComment(pr, "@codex review")).isFalse();
    }

    @Test
    void connectorReviewTextDoesNotCountAsTriggerComment() {
        GitHub gh = new GitHub("owner", "repo");
        Map<String, Object> pr = map("comments", List.of(),
                "reviews", List.of(map("body", "Reviews are triggered by \"@codex review\"")));
        assertThat(gh.hasComment(pr, "@codex review")).isFalse();
    }

    @Test
    void reviewTriggerMarkerIsDetected() {
        GitHub gh = new GitHub("owner", "repo");
        Map<String, Object> pr = map("comments", List.of(map("body", "@codex review")),
                "reviews", List.of());
        assertThat(gh.hasComment(pr, "@codex review")).isTrue();
    }

    @Test
    void hasExactCommentAfterRespectsTimestamp() {
        GitHub gh = new GitHub("owner", "repo");
        Map<String, Object> pr = map("comments", List.of(
                map("body", "@codex review", "createdAt", "2026-01-01T00:00:01+00:00")));
        assertThat(gh.hasExactCommentAfter(pr, "@codex review", "2026-01-01T00:00:00+00:00")).isTrue();
        assertThat(gh.hasExactCommentAfter(pr, "@codex review", "2026-01-01T00:00:02+00:00")).isFalse();
    }

    // -- reply_thread GraphQL field  -----------------

    @Test
    void replyThreadUsesGithubThreadInputField() {
        List<List<String>> calls = new ArrayList<>();
        GitHub gh = withRunner((command, timeout) -> {
            calls.add(command);
            return new GitHub.CommandResult(0, "{}", "");
        });
        gh.replyThread("PRRT_thread", "fixed");
        String query = calls.stream().flatMap(List::stream)
                .filter(arg -> arg.startsWith("query="))
                .map(arg -> arg.substring("query=".length()))
                .findFirst().orElseThrow();
        assertThat(query).contains("pullRequestReviewThreadId:$threadId");
        assertThat(query).doesNotContain("input:{threadId:$threadId");
    }

    @Test
    void resolveThreadUsesResolveMutation() {
        List<List<String>> calls = new ArrayList<>();
        GitHub gh = withRunner((command, timeout) -> {
            calls.add(command);
            return new GitHub.CommandResult(0, "{}", "");
        });
        gh.resolveThread("PRRT_thread");
        String query = calls.stream().flatMap(List::stream)
                .filter(arg -> arg.startsWith("query="))
                .map(arg -> arg.substring("query=".length()))
                .findFirst().orElseThrow();
        assertThat(query).contains("resolveReviewThread(input:{threadId:$threadId})");
    }

    // -- timeout classification  ---------------------

    @Test
    void githubCommandHasTimeout() {
        GitHub gh = withRunner((command, timeout) -> {
            throw new GitHub.CommandTimeout("gh timed out");
        });
        assertThatThrownBy(() -> gh.run("repo", "view"))
                .isInstanceOf(GitHubError.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void githubTimeoutIsConfigurable() {
        GitHub gh = new GitHub("owner", "repo", "GH_TOKEN", "gh", 7);
        assertThat(gh.timeoutSeconds()).isEqualTo(7);
    }

    // -- run() behavior: token gate, error surface, JSON fallback ------------

    @Test
    void missingTokenIsRejected() {
        GitHub gh = new GitHub("owner", "repo", "GH_TOKEN", "gh", 30,
                (command, timeout) -> new GitHub.CommandResult(0, "{}", ""),
                name -> null);
        assertThatThrownBy(() -> gh.run("repo", "view"))
                .isInstanceOf(GitHubError.class)
                .hasMessageContaining("token environment variable is missing");
    }

    @Test
    void nonZeroExitSurfacesStderr() {
        GitHub gh = withRunner((command, timeout) ->
                new GitHub.CommandResult(1, "", "not found"));
        assertThatThrownBy(() -> gh.run("issue", "view", "999"))
                .isInstanceOf(GitHubError.class)
                .hasMessage("not found");
    }

    @Test
    void nonJsonOutputFallsBackToTrimmedString() {
        GitHub gh = withRunner((command, timeout) ->
                new GitHub.CommandResult(0, "  plain text output  \n", ""));
        assertThat(gh.run("pr", "comment")).isEqualTo("plain text output");
    }

    @Test
    void jsonOutputIsParsedIntoObject() {
        GitHub gh = withRunner((command, timeout) ->
                new GitHub.CommandResult(0, "{\"number\": 7, \"state\": \"OPEN\"}", ""));
        Object result = gh.issue(7);
        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("number")).isEqualTo(7);
    }

    // -- real runner: large output must not deadlock (regression) ------------

    @Test
    void defaultRunnerHandlesOutputLargerThanPipeBuffer() {
        // Regression for the pipe-buffer deadlock: a subprocess that writes far
        // more than the OS pipe buffer (~64 KB) must be fully read without
        // hanging. Exercises the real production runner (GitHub.defaultRunner).
        GitHub shell = new GitHub("owner", "repo", "GH_TOKEN", "/bin/sh", 30,
                GitHub.defaultRunner(), name -> "token");
        // Produce >1 MB of non-JSON text (so run() returns the raw string rather
        // than parsing a leading token). Far exceeds the OS pipe buffer, so a
        // non-draining reader would deadlock and hit the 30s timeout.
        String script = "seq 1 200000 | sed 's/^/line-/'";
        Object out = shell.run("-c", script);
        assertThat(out).isInstanceOf(String.class);
        assertThat(((String) out).length()).isGreaterThan(500000);
    }

    // -- pr() review-thread aggregation and fail-closed behavior -------------

    @Test
    void prAggregatesReviewThreadsWhenAvailable() {
        GitHub gh = withRunner((command, timeout) -> {
            boolean isGraphql = command.contains("graphql");
            if (isGraphql) {
                String body = "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                        + "\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},"
                        + "\"nodes\":[{\"id\":\"PRRT_1\",\"isResolved\":false,"
                        + "\"comments\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[]}}]}}}}}";
                return new GitHub.CommandResult(0, body, "");
            }
            return new GitHub.CommandResult(0, "{\"number\":42,\"state\":\"OPEN\"}", "");
        });
        Map<String, Object> pr = gh.pr(42);
        assertThat(pr.get("reviewThreadsAvailable")).isEqualTo(true);
        assertThat((List<?>) pr.get("reviewThreads")).hasSize(1);
    }

    @Test
    void prFailsClosedWhenThreadQueryFails() {
        GitHub gh = withRunner((command, timeout) -> {
            boolean isGraphql = command.contains("graphql");
            if (isGraphql) {
                return new GitHub.CommandResult(1, "", "API error");
            }
            return new GitHub.CommandResult(0, "{\"number\":42,\"state\":\"OPEN\"}", "");
        });
        Map<String, Object> pr = gh.pr(42);
        assertThat(pr.get("reviewThreadsAvailable")).isEqualTo(false);
        assertThat((List<?>) pr.get("reviewThreads")).isEmpty();
    }

    @Test
    void prFailsClosedWhenCommentsExceedPageSize() {
        GitHub gh = withRunner((command, timeout) -> {
            boolean isGraphql = command.contains("graphql");
            if (isGraphql) {
                String body = "{\"data\":{\"repository\":{\"pullRequest\":{\"reviewThreads\":{"
                        + "\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null},"
                        + "\"nodes\":[{\"id\":\"PRRT_1\",\"isResolved\":false,"
                        + "\"comments\":{\"pageInfo\":{\"hasNextPage\":true},\"nodes\":[]}}]}}}}}";
                return new GitHub.CommandResult(0, body, "");
            }
            return new GitHub.CommandResult(0, "{\"number\":42}", "");
        });
        Map<String, Object> pr = gh.pr(42);
        assertThat(pr.get("reviewThreadsAvailable")).isEqualTo(false);
    }
}
