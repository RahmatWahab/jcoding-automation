package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionBudgetTest {
    @Test void callsSurvivePhaseHeadAndStorySwitches() {
        Map<String, Object> state = new LinkedHashMap<>(Map.of("current_story", 81));
        Map<String, Object> config = Map.of("max_story_worker_calls", 2);
        ExecutionBudget.reserve(state, config, 60);
        ExecutionBudget.finish(state, 100, Map.of("input_tokens", 42L));
        state.put("phase", "RECONCILING");
        state.put("current_pr_head", "new");
        ExecutionBudget.reserve(state, config, 60);
        ExecutionBudget.finish(state, 100, Map.of());
        state.put("current_story", 82);
        ExecutionBudget.reserve(state, config, 60);
        ExecutionBudget.finish(state, 10, Map.of());
        state.put("current_story", 81);
        assertThatThrownBy(() -> ExecutionBudget.reserve(state, config, 60)).isInstanceOf(ExecutionBudget.Exhausted.class);
        assertThat(ExecutionBudget.ledger(state).get("input_tokens")).isEqualTo(42L);
    }

    @Test void crashedReservationIsChargedAndRemainingTimeLimitsEveryCall() {
        Map<String, Object> state = new LinkedHashMap<>(Map.of("current_story", 81));
        Map<String, Object> config = Map.of("max_story_active_seconds", 90);
        assertThat(ExecutionBudget.reserve(state, config, 60)).isEqualTo(60);
        assertThat(ExecutionBudget.reserve(state, config, 60)).isEqualTo(30);
        assertThatThrownBy(() -> ExecutionBudget.reserve(state, config, 60)).isInstanceOf(ExecutionBudget.Exhausted.class);
    }

    @Test void tokenCeilingAndExplicitEpochResetAreEnforced() {
        Map<String, Object> state = new LinkedHashMap<>(Map.of("current_story", 81));
        Map<String, Object> config = new LinkedHashMap<>(Map.of("max_story_tokens", 100));
        ExecutionBudget.reserve(state, config, 60);
        ExecutionBudget.finish(state, 1, Map.of("input_tokens", 90L, "cached_input_tokens", 80L, "output_tokens", 10L));
        assertThatThrownBy(() -> ExecutionBudget.reserve(state, config, 60)).isInstanceOf(ExecutionBudget.Exhausted.class);
        config.put("recovery_epoch", 1);
        assertThat(ExecutionBudget.reserve(state, config, 60)).isEqualTo(60);
        assertThat(ExecutionBudget.ledger(state)).containsKey("previous_epoch");
    }
}
