package com.jcoding.workflow;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business-value tests for the typed state codec. They protect the two
 * properties the refactor must not break: no data loss on round-trip, and a
 * single correct story-reset boundary.
 */
class WorkflowStateCodecTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void roundTripPreservesUnknownAndUnmappedKeys() {
        // TS-7: a legacy epic-<n>.json must round-trip with no data loss —
        // every key not owned by the typed model is preserved verbatim.
        Map<String, Object> legacy = map(
                "phase", "FIXING_FEEDBACK",
                "current_story", 52,
                "current_pr", 187,
                "branch", "story/52-x",
                "resume_attempts", 2,
                "resume_guard_head", "HEAD_A",
                "resume_total_story", "52",
                "resume_total_attempts", 5,
                "resume_total_resolved_baseline", 3,
                // Unknown / unmapped keys that must survive untouched.
                "some_future_flag", "keep-me",
                "completed_stories", List.of(1, 2, 3),
                "nested", map("a", 1, "b", List.of("x", "y")));

        Map<String, Object> after = WorkflowStateCodec.roundTrip(legacy);

        assertThat(after.get("some_future_flag")).isEqualTo("keep-me");
        assertThat(after.get("completed_stories")).isEqualTo(List.of(1, 2, 3));
        assertThat(after.get("nested")).isEqualTo(map("a", 1, "b", List.of("x", "y")));
        // Mapped control keys are preserved with identical values.
        assertThat(after.get("resume_attempts")).isEqualTo(2);
        assertThat(after.get("resume_total_attempts")).isEqualTo(5);
        assertThat(after.get("resume_guard_head")).isEqualTo("HEAD_A");
    }

    @Test
    void loadExposesTypedProgressFromLegacyKeys() {
        Map<String, Object> legacy = map(
                "phase", "RECONCILING", "current_story", 7, "current_pr", 42,
                "branch", "story/7", "resume_guard_head", "HEAD_X",
                "resume_attempts", 3, "resume_total_story", "7",
                "resume_total_attempts", 9, "resume_total_resolved_baseline", 4);

        WorkflowStateCodec.WorkflowState typed = WorkflowStateCodec.load(legacy);

        assertThat(typed.phase()).isEqualTo("RECONCILING");
        assertThat(typed.currentStory()).isEqualTo(7);
        assertThat(typed.currentPr()).isEqualTo(42);
        assertThat(typed.progress().sameHeadAttempts()).isEqualTo(3);
        assertThat(typed.progress().cumulativeAttempts()).isEqualTo(9);
        assertThat(typed.progress().resolvedBaseline()).isEqualTo(4);
    }

    @Test
    void clearStoryScopedClearsOnlyStoryScopedKeys() {
        // The single reset boundary: story-scoped control keys are removed;
        // cross-story state (completed_stories, phase, epic-level) survives so
        // one story's completion cannot corrupt the EPIC's progress.
        Map<String, Object> state = map(
                "phase", "IDLE",
                "completed_stories", List.of(52),
                "resume_attempts", 3,
                "stuck_head", "HEAD_A",
                "feedback_attempts", 2,
                "reconciliation_attempts", 1,
                "last_processed_commit", "abc",
                "branch", "story/52-x");

        WorkflowStateCodec.clearStoryScoped(state);

        // Story-scoped keys gone.
        assertThat(state).doesNotContainKeys(
                "resume_attempts", "stuck_head", "feedback_attempts",
                "reconciliation_attempts", "last_processed_commit");
        // Cross-story / caller-owned keys survive.
        assertThat(state.get("completed_stories")).isEqualTo(List.of(52));
        assertThat(state.get("phase")).isEqualTo("IDLE");
    }
}
