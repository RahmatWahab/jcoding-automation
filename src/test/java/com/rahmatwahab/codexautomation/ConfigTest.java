package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Configuration-validation tests. Pure in-memory maps; no external systems.
 * Each test asserts either acceptance or the specific offending key in the
 * error message.
 */
class ConfigTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> valid() {
        return map(
                "repository", map("local_path", "/repo", "owner", "o", "name", "n"),
                "epic", map("number", 1),
                "codex", map("executable", "codex", "model", "m", "reasoning_effort", "low"),
                "workflow", map());
    }

    @Test
    void validConfigIsAccepted() {
        assertThatCode(() -> Config.validate(map(
                "repository", map("local_path", "/repo", "owner", "o", "name", "n"),
                "epic", map("number", 1),
                "codex", map("executable", "codex", "model", "model", "reasoning_effort", "low"))))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidReasoningEffortIsRejected() {
        Map<String, Object> config = valid();
        Config.section(config, "codex").put("reasoning_effort", "invalid");
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning_effort");
    }

    @Test
    void defaultMergePolicyIsManual() {
        // No auto_merge flag = manual merge; config is valid.
        assertThatCode(() -> Config.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void mutationFlagsMustBeBoolean() {
        Map<String, Object> config = valid();
        Config.section(config, "workflow").put("allow_push", "true");
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow_push");
    }

    @Test
    void sandboxBypassMustBeBoolean() {
        Map<String, Object> config = valid();
        Config.section(config, "codex").put("dangerously_bypass_sandbox", "true");
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dangerously_bypass_sandbox");
    }

    @Test
    void threadResolutionFlagMustBeBoolean() {
        Map<String, Object> config = valid();
        Config.section(config, "workflow").put("allow_thread_resolution", "true");
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow_thread_resolution");
    }

    @Test
    void epicNumberMustBeInteger() {
        Map<String, Object> config = valid();
        Config.section(config, "epic").put("number", "1");
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epic.number");
    }

    @Test
    void unknownBudgetPhaseIsRejected() {
        Map<String, Object> config = valid();
        config.put("budgets", map("unknown_phase", 100));
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgets.unknown_phase");
    }

    @Test
    void nonPositiveTimeoutIsValidated() {
        Map<String, Object> config = valid();
        Config.section(config, "codex").put("execution_timeout_seconds", 0);
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution_timeout_seconds");
    }

    @Test
    void autoMergeTrueIsAccepted() {
        // auto_merge=true enables fully autonomous merge; config is valid.
        Map<String, Object> config = valid();
        Config.section(config, "workflow").put("auto_merge", true);
        assertThatCode(() -> Config.validate(config)).doesNotThrowAnyException();
    }

    @Test
    void autoMergeFlagMustBeBoolean() {
        Map<String, Object> config = valid();
        Config.section(config, "workflow").put("auto_merge", "true");
        assertThatThrownBy(() -> Config.validate(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auto_merge");
    }
}
