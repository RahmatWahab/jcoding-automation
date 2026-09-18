package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Core-engine tests: PR classification, story/PR reference matching, quota
 * detection, linked-story discovery, story selection, planning actions, and
 * review-event extraction. Pure in-memory maps; no external systems.
 */
class EngineTest {

    // -- helpers to build GitHub-style JSON maps concisely -------------------

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> list(Map<String, Object>... items) {
        return new ArrayList<>(List.of(items));
    }

    // --  ------------------------------------------------------

    @Test
    void storyNumbersDoNotMatchPrefixes() {
        List<Map<String, Object>> prs = list(map("number", 1, "title", "Fix #27", "body", "Closes #45"));
        assertThat(Engine.prForStory(prs, 2)).isEmpty();
        assertThat(Engine.prForStory(prs, 45).get(0).get("number")).isEqualTo(1);
    }

    @Test
    void classifyPrWaitsForCi() {
        Map<String, Object> pr = map("state", "OPEN",
                "statusCheckRollup", List.of(map("status", "IN_PROGRESS")));
        assertThat(Engine.classifyPr(pr)).isEqualTo("WAITING_FOR_CI");
    }

    @Test
    void classifyPrDetectsUnresolvedThreadWhenAvailable() {
        Map<String, Object> pr = map("state", "OPEN",
                "statusCheckRollup", List.of(),
                "reviewThreads", List.of(map("isResolved", false)));
        assertThat(Engine.classifyPr(pr)).isEqualTo("CHANGES_REQUESTED");
    }

    @Test
    void quotaDetection() {
        assertThat(Engine.quotaError("Your weekly usage limit has been reached")).isTrue();
        assertThat(Engine.quotaError("tests completed successfully")).isFalse();
        assertThat(Engine.quotaError("gh pr list --limit 100")).isFalse();
        assertThat(Engine.quotaError("diff --git a/file b/file\n- previous limit handling")).isFalse();
    }

    @Test
    void epicNumberIsNotAStory() {
        Map<String, Object> epic = map("number", 27, "body", "Closes #27 and #45");
        assertThat(Engine.linkedStoryNumbers(epic)).doesNotContain(27);
        assertThat(Engine.linkedStoryNumbers(epic)).contains(45);
    }

    @Test
    void epicStoryMarkdownUrlsAreDiscoveredWithoutIncludingEpic() {
        Map<String, Object> epic = map("number", 1, "body",
                "EPIC https://github.com/example/project/issues/1\n"
                        + "- [S001](https://github.com/example/project/issues/2)\n"
                        + "- [S002](https://github.com/example/project/issues/3)\n"
                        + "- duplicate #2\n");
        assertThat(Engine.linkedStoryNumbers(epic)).containsExactly(2, 3);
    }

    @Test
    void missingReviewCursorFailsClosed() {
        Map<String, Object> pr = map("comments",
                List.of(map("id", "new", "body", "please fix", "submittedAt", "2026-01-02")));
        assertThat(Engine.reviewEvents(pr, "cursor-from-another-snapshot")).isEmpty();
    }

    // --  ----------------------------------------------------

    @Test
    void failedCiRequiresChanges() {
        Map<String, Object> pr = map("state", "OPEN",
                "statusCheckRollup", List.of(map("status", "COMPLETED", "conclusion", "FAILURE")));
        assertThat(Engine.classifyPr(pr)).isEqualTo("CHANGES_REQUESTED");
    }

    @Test
    void mergedPrHasPriorityOverChecks() {
        Map<String, Object> pr = map("state", "CLOSED",
                "mergedAt", "2026-01-01T00:00:00Z", "statusCheckRollup", List.of());
        assertThat(Engine.classifyPr(pr)).isEqualTo("MERGED");
    }

    // --  ---------------------------------------------

    @Test
    void selectsFirstOpenStoryWithAcceptanceCriteria() {
        List<Map<String, Object>> stories = list(
                map("number", 1, "state", "CLOSED", "body", "acceptance"),
                map("number", 2, "state", "OPEN", "body", "Acceptance criteria"),
                map("number", 3, "state", "OPEN", "body", "Acceptance criteria"));
        assertThat(Engine.selectStory(stories, Set.of()).get("number")).isEqualTo(2);
    }

    @Test
    void excludesBlockedAndCompletedStories() {
        List<Map<String, Object>> stories = list(
                map("number", 1, "state", "OPEN", "body", "Acceptance",
                        "labels", List.of(map("name", "blocked"))),
                map("number", 2, "state", "OPEN", "body", "Acceptance"));
        assertThat(Engine.selectStory(stories, Set.of(2))).isNull();
    }

    // --  -----------------------------------------------------

    @Test
    void waitStatesNeverStartWork() {
        assertThat(Engine.nextAction("WAITING_FOR_CI")).isEqualTo("WAIT");
        assertThat(Engine.nextAction("WAITING_FOR_MANUAL_MERGE")).isEqualTo("WAIT");
    }

    @Test
    void feedbackTakesPriority() {
        assertThat(Engine.nextAction("WAITING_FOR_HUMAN_REVIEW", true)).isEqualTo("RUN_FEEDBACK");
    }

    @Test
    void blockedStateStops() {
        assertThat(Engine.nextAction("CONFLICT_DETECTED")).isEqualTo("STOP");
    }

    // --  -----------------------------------------------------

    @Test
    void reviewEventsAreExtractableAndCursorable() {
        Map<String, Object> pr = map(
                "reviews", List.of(map("id", "r1", "body", "Please add a test",
                        "author", map("login", "reviewer"), "commit", map("oid", "abc"))),
                "comments", List.of());
        assertThat(Engine.reviewEvents(pr).get(0).get("id")).isEqualTo("r1");
        assertThat(Engine.reviewEvents(pr, "r1")).isEmpty();
    }
}
