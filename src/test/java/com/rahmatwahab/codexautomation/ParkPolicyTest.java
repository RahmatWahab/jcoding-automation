package com.rahmatwahab.codexautomation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business-value tests for the park-and-advance policy (Requirement 5). They
 * protect the safety properties: default is inert (behavior-preserving), a
 * parked story is excluded from selection, and a story auto-resumes only when
 * its head genuinely advances.
 */
class ParkPolicyTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> story(int n) {
        return map("number", n, "state", "OPEN", "body", "", "url", "http://x/" + n);
    }

    @Test
    void defaultPolicyIsHoldAndInert() {
        // Behavior preservation: absent/other terminal_policy is disabled, so
        // no parking machinery ever activates.
        assertThat(ParkPolicy.enabled(map())).isFalse();
        assertThat(ParkPolicy.enabled(map("terminal_policy", "hold"))).isFalse();
        assertThat(ParkPolicy.enabled(map("terminal_policy", "park_and_advance"))).isTrue();
    }

    @Test
    void parkThenExcludeFromSelection() {
        Map<String, Object> state = new LinkedHashMap<>();
        assertThat(ParkPolicy.park(state, 52, "headA", "UNRECOVERABLE", "t0")).isTrue();
        // Re-parking the same head is idempotent (no duplicate notification).
        assertThat(ParkPolicy.park(state, 52, "headA", "UNRECOVERABLE", "t1")).isFalse();

        List<Map<String, Object>> candidates = new ArrayList<>(List.of(story(52), story(53)));
        List<Map<String, Object>> selectable = ParkPolicy.excludeParked(candidates, state);
        assertThat(selectable).extracting(s -> s.get("number")).containsExactly(53);
    }

    @Test
    void autoUnparksOnlyWhenHeadAdvances() {
        Map<String, Object> state = new LinkedHashMap<>();
        ParkPolicy.park(state, 52, "headA", "UNRECOVERABLE", "t0");

        // Same head: stays parked.
        assertThat(ParkPolicy.unparkIfHeadAdvanced(state, 52, "headA")).isFalse();
        assertThat(ParkPolicy.parkedStoryNumbers(state)).containsExactly(52);

        // Head advanced: un-parked and resumes.
        assertThat(ParkPolicy.unparkIfHeadAdvanced(state, 52, "headB")).isTrue();
        assertThat(ParkPolicy.parkedStoryNumbers(state)).isEmpty();
    }

    @Test
    void reparkAfterHeadAdvanceUsesNewHead() {
        // A story parked, un-parked on advance, then parked again on the new
        // head is treated as a fresh park (notifies again).
        Map<String, Object> state = new LinkedHashMap<>();
        ParkPolicy.park(state, 52, "headA", "UNRECOVERABLE", "t0");
        ParkPolicy.unparkIfHeadAdvanced(state, 52, "headB");
        assertThat(ParkPolicy.park(state, 52, "headB", "UNRECOVERABLE", "t2")).isTrue();
        assertThat(ParkPolicy.parkedHead(state, 52)).isEqualTo("headB");
    }

    @Test
    void excludeParkedIsNoOpWhenNothingParked() {
        // Under the default policy the parked set is empty, so selection input
        // is returned unchanged (identity), guaranteeing no behavior change.
        List<Map<String, Object>> candidates = new ArrayList<>(List.of(story(1), story(2)));
        assertThat(ParkPolicy.excludeParked(candidates, new LinkedHashMap<>())).isSameAs(candidates);
    }

    @Test
    void parkedStoryReasonAndHeadAreRecorded() {
        // The park record carries head + reason so an operator (and auto-unpark)
        // can act on it — not just a bare flag.
        Map<String, Object> state = new LinkedHashMap<>();
        ParkPolicy.park(state, 52, "headA", "UNRECOVERABLE", "2026-01-01T00:00:00Z");
        assertThat(ParkPolicy.parkedHead(state, 52)).isEqualTo("headA");
        Object rec = ((Map<?, ?>) state.get(ParkPolicy.STATE_KEY)).get("52");
        assertThat(rec).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("reason", "UNRECOVERABLE")
                .containsEntry("parked_at", "2026-01-01T00:00:00Z");
    }
}
