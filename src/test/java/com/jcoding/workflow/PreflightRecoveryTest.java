package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreflightRecoveryTest {
    @Test
    void unchangedFailureStopsSpendingAndChangedEvidenceWakesIt() {
        Map<String, Object> state = new LinkedHashMap<>();
        Map<String, Object> failure = PreflightRecovery.observe(state, "one", "dirty");
        assertThat(PreflightRecovery.reserve(failure, 2)).isTrue();
        failure = PreflightRecovery.observe(state, "one", "dirty");
        assertThat(PreflightRecovery.reserve(failure, 2)).isTrue();
        assertThat(PreflightRecovery.reserve(failure, 2)).isFalse();
        assertThat(PreflightRecovery.reserve(failure, 2)).isFalse();
        assertThat(failure).containsEntry("stage", "HELD");
        failure = PreflightRecovery.observe(state, "two", "dirty");
        assertThat(PreflightRecovery.reserve(failure, 2)).isTrue();
        assertThat(failure).containsEntry("attempts", 1);
    }
}
