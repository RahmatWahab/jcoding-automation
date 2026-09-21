package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Review-coordination tests: fixed-thread id extraction, thread-id
 * canonicalization, fix-reply construction, the review gate, the idempotent
 * Codex review trigger, and the PR-creation gate. A recording GitHub (injected
 * fake command runner) captures posted comments; no real GitHub or network is
 * used.
 */
class ReviewCoordinatorTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** GitHub whose run() records the args, so pr comment posts are observable. */
    private static final class RecordingGitHub {
        final List<List<String>> calls = new ArrayList<>();
        final GitHub github;

        RecordingGitHub() {
            this.github = new GitHub("owner", "repo", "GH_TOKEN", "gh", 30,
                    (command, timeout) -> {
                        calls.add(command);
                        return new GitHub.CommandResult(0, "", "");
                    }, name -> "test-token");
        }

        List<String[]> postedComments() {
            List<String[]> posts = new ArrayList<>();
            for (List<String> call : calls) {
                if (call.size() >= 2 && call.get(1).equals("pr") && call.contains("comment")) {
                    int bodyIndex = call.indexOf("--body") + 1;
                    int numberIndex = call.indexOf("comment") + 1;
                    posts.add(new String[] {call.get(numberIndex), call.get(bodyIndex)});
                }
            }
            return posts;
        }
    }

    private final ReviewCoordinator coordinator = new ReviewCoordinator(() -> "2026-01-01T00:00:00+00:00");

    // -- fixed_thread_ids ----------------------------------------------------

    @Test
    void workerMustExplicitlyIdentifyFixedThreads() {
        Set<String> ids = ReviewCoordinator.fixedThreadIds("FIXED_THREAD: PRRT_one\nFIXED_THREAD: PRRT_two\n");
        assertThat(ids).containsExactlyInAnyOrder("PRRT_one", "PRRT_two");
    }

    @Test
    void workerPromptPlaceholderIsNotAThreadDeclaration() {
        Set<String> ids = ReviewCoordinator.fixedThreadIds(
                "FIXED_THREAD: <thread-id>\nFIXED_THREAD: PRRC_real_comment\n");
        assertThat(ids).containsExactly("PRRC_real_comment");
    }

    @Test
    void workerCanIdentifySupersededThreads() {
        Set<String> ids = ReviewCoordinator.supersededThreadIds(
                "SUPERSEDED_THREAD: PRRT_old\nSUPERSEDED_THREAD: PRRC_comment\n");

        assertThat(ids).containsExactly("PRRT_old", "PRRC_comment");
    }

    // -- canonical_fixed_thread_ids ------------------------------------------

    @Test
    void commentIdIsCanonicalizedToParentThreadId() {
        Map<String, Object> pr = map("reviewThreads", List.of(map(
                "id", "PRRT_thread",
                "comments", map("nodes", List.of(map("id", "PRRC_comment", "body", "finding"))))));
        ReviewCoordinator.ThreadIdMapping mapping =
                ReviewCoordinator.canonicalFixedThreadIds(pr, Set.of("PRRC_comment"));
        assertThat(mapping.canonical()).containsExactly("PRRT_thread");
        assertThat(mapping.unknown()).isEmpty();
    }

    @Test
    void threadIdIsKeptCanonicalAndUnknownIdIsReported() {
        Map<String, Object> pr = map("reviewThreads", List.of(map(
                "id", "PRRT_thread", "comments", map("nodes", List.of()))));
        ReviewCoordinator.ThreadIdMapping mapping =
                ReviewCoordinator.canonicalFixedThreadIds(pr, Set.of("PRRT_thread", "PRRC_missing"));
        assertThat(mapping.canonical()).containsExactly("PRRT_thread");
        assertThat(mapping.unknown()).containsExactly("PRRC_missing");
    }

    // -- thread_fix_reply / fix_reply_heads ----------------------------------

    @Test
    void threadFixReplyEmbedsCommitAndFinding() {
        String reply = ReviewCoordinator.threadFixReply(
                map("thread_id", "PRRT_one", "body", "Fix rollback"),
                "3e58bc411d9b5b8e1336222f98c08de2ac222fe6", "bash tests — passed");
        assertThat(reply).contains("3e58bc411d9b");
        assertThat(reply).contains("thread=PRRT_one");
    }

    @Test
    void threadReplyMarkerUsesGraphqlThreadIdAndIsDetectable() {
        Map<String, Object> thread = map("id", "PRRT_one", "comments", map("nodes", new ArrayList<>()));
        String reply = ReviewCoordinator.threadFixReply(thread, "abcdef1234567890", "tests passed");
        assertThat(reply).contains("thread=PRRT_one");
        @SuppressWarnings("unchecked")
        List<Object> nodes = (List<Object>) ((Map<String, Object>) thread.get("comments")).get("nodes");
        nodes.add(map("body", reply));
        assertThat(ReviewCoordinator.fixReplyHeads(thread)).containsExactly("abcdef1234567890");
    }

    // -- review_gate_open  -----

    @Test
    void reviewGateIsOpenWhenConnectorReviewIsForOldHead() {
        Map<String, Object> pr = map("state", "OPEN", "headRefOid", "new-head", "mergedAt", null,
                "reviews", List.of(map("author", map("login", "chatgpt-codex-connector"),
                        "submittedAt", "2026-01-01", "commit", map("oid", "old-head"))));
        assertThat(ReviewCoordinator.reviewGateOpen(pr)).isTrue();
    }

    // -- ensure_codex_review_trigger  ----------------

    @Test
    void reviewTriggerIsPostedForUnmarkedOpenHead() {
        RecordingGitHub gh = new RecordingGitHub();
        Map<String, Object> state = map();
        Map<String, Object> pr = map("number", 177, "state", "OPEN", "mergedAt", null,
                "headRefOid", "abc123", "comments", List.of(),
                "reviews", List.of(map("body", "Reviews are triggered by \"@codex review\"")));
        boolean posted = coordinator.ensureCodexReviewTrigger(gh.github, pr, state,
                map("allow_comments", true), false, null);
        assertThat(posted).isTrue();
        assertThat(gh.postedComments()).hasSize(1);
        assertThat(gh.postedComments().get(0)).containsExactly("177", "@codex review");
        assertThat(state.get("last_review_trigger_commit")).isEqualTo("abc123");
        assertThat(state.get("last_review_trigger_verified_commit")).isEqualTo("abc123");
    }

    @Test
    void reviewTriggerIsIdempotentForSameHead() {
        RecordingGitHub gh = new RecordingGitHub();
        Map<String, Object> state = map("last_review_trigger_commit", "abc123",
                "last_review_trigger_verified_commit", "abc123");
        Map<String, Object> pr = map("number", 177, "state", "OPEN", "headRefOid", "abc123",
                "comments", List.of(), "reviews", List.of());
        boolean posted = coordinator.ensureCodexReviewTrigger(gh.github, pr, state,
                map("allow_comments", true), false, null);
        assertThat(posted).isFalse();
        assertThat(gh.postedComments()).isEmpty();
    }

    @Test
    void historicalTriggerDoesNotSuppressNewHead() {
        RecordingGitHub gh = new RecordingGitHub();
        Map<String, Object> state = map("last_review_trigger_commit", "old-head");
        Map<String, Object> pr = map("number", 177, "state", "OPEN", "mergedAt", null,
                "headRefOid", "new-head", "comments", List.of(map("body", "@codex review")),
                "reviews", List.of());
        boolean posted = coordinator.ensureCodexReviewTrigger(gh.github, pr, state,
                map("allow_comments", true), false, null);
        assertThat(posted).isTrue();
        assertThat(gh.postedComments().get(0)).containsExactly("177", "@codex review");
        assertThat(state.get("last_review_trigger_commit")).isEqualTo("new-head");
    }

    @Test
    void pendingTriggerRecoversAfterPostedComment() {
        RecordingGitHub gh = new RecordingGitHub();
        Map<String, Object> state = map("review_trigger_pending",
                map("head", "new-head", "started_at", "2026-01-01T00:00:00+00:00"));
        Map<String, Object> pr = map("number", 177, "state", "OPEN", "mergedAt", null,
                "headRefOid", "new-head",
                "comments", List.of(map("body", "@codex review", "createdAt", "2026-01-01T00:00:01+00:00")),
                "reviews", List.of());
        boolean posted = coordinator.ensureCodexReviewTrigger(gh.github, pr, state,
                map("allow_comments", true), false, null);
        assertThat(posted).isFalse();
        assertThat(gh.postedComments()).isEmpty();
        assertThat(state.get("last_review_trigger_commit")).isEqualTo("new-head");
    }

    @Test
    void triggerSuppressedWhenCommentsDisabled() {
        RecordingGitHub gh = new RecordingGitHub();
        Map<String, Object> pr = map("number", 177, "state", "OPEN", "headRefOid", "abc",
                "comments", List.of(), "reviews", List.of());
        boolean posted = coordinator.ensureCodexReviewTrigger(gh.github, pr, map(),
                map("allow_comments", false), false, null);
        assertThat(posted).isFalse();
        assertThat(gh.postedComments()).isEmpty();
    }

    // -- create_story_pr gate  -----------------------

    @Test
    void prCreationIsDisabledByDefault() {
        RecordingGitHub gh = new RecordingGitHub();
        GitOperations git = new GitOperations(java.nio.file.Path.of("/repo"),
                (repo, args) -> {
                    throw new AssertionError("git must not be called when PR creation is disabled");
                });
        Map<String, Object> config = map(
                "repository", map("base_branch", "main"),
                "workflow", map("allow_push", false, "allow_pr_creation", false));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> coordinator.createStoryPr(git, gh.github, config, 45, 27))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }
}
