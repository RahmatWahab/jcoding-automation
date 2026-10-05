package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Codex-runner tests: command construction, credential redaction, and
 * dry-run safety. Pure/in-memory; no codex process is launched. Redaction uses
 * an injected fake environment so no real secrets are involved.
 */
class CodexRunnerTest {

    @SafeVarargs
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // -- buildCommand --------------------------------------------------------

    @Test
    void buildCommandIncludesModelEffortAndApproval() {
        List<String> command = CodexRunner.buildCommand(
                map("executable", "codex", "model", "gpt-5.6-luna",
                        "reasoning_effort", "medium", "approval_policy", "never"),
                Path.of("/repo"));
        assertThat(command).containsSubsequence("codex", "--cd", "/repo",
                "--model", "gpt-5.6-luna", "-c", "model_reasoning_effort=medium",
                "--approve-for-me", "exec", "--json", "-");
    }

    @Test
    void buildCommandUsesSandboxBypassInsteadOfApproval() {
        List<String> command = CodexRunner.buildCommand(
                map("executable", "codex", "approval_policy", "never",
                        "dangerously_bypass_sandbox", true),
                Path.of("/repo"));
        assertThat(command).contains("--dangerously-bypass-approvals-and-sandbox");
        assertThat(command).doesNotContain("--approve-for-me");
    }

    // -- redactSensitive -----------------------------------------------------

    @Test
    void redactsEnvironmentSecretValues() {
        CodexRunner runner = new CodexRunner(
                Map.of("GH_TOKEN", "ghp_supersecretvalue", "PATH", "/usr/bin"),
                System::nanoTime);
        String redacted = runner.redactSensitive("using token ghp_supersecretvalue to auth");
        assertThat(redacted).doesNotContain("ghp_supersecretvalue");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void redactsAuthorizationBearerHeaders() {
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime);
        String redacted = runner.redactSensitive("Authorization: Bearer abc.def.ghi");
        assertThat(redacted).contains("Authorization: Bearer [REDACTED]");
        assertThat(redacted).doesNotContain("abc.def.ghi");
    }

    @Test
    void redactsInlineSecretAssignments() {
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime);
        String redacted = runner.redactSensitive("GH_TOKEN=ghp_inline_value_here");
        assertThat(redacted).contains("GH_TOKEN=[REDACTED]");
        assertThat(redacted).doesNotContain("ghp_inline_value_here");
    }

    @Test
    void redactsMultipleDistinctSecretsAndCombinesWithRegex() {
        // Precomputed sensitive values must still redact every configured secret
        // and compose with the header/assignment regex passes in one call.
        CodexRunner runner = new CodexRunner(
                new java.util.LinkedHashMap<>(Map.of(
                        "GH_TOKEN", "ghp_aaa", "MY_SECRET", "s3cr3t", "PATH", "/usr/bin")),
                System::nanoTime);
        String redacted = runner.redactSensitive(
                "gh=ghp_aaa other=s3cr3t Authorization: Bearer xyz keep=/usr/bin");
        assertThat(redacted).doesNotContain("ghp_aaa");
        assertThat(redacted).doesNotContain("s3cr3t");
        assertThat(redacted).contains("Authorization: Bearer [REDACTED]");
        // Non-sensitive env values (PATH) are never redacted.
        assertThat(redacted).contains("keep=/usr/bin");
    }

    @Test
    void redactionHandlesEmptyAndNullInputWithoutError() {
        CodexRunner runner = new CodexRunner(
                Map.of("GH_TOKEN", "ghp_aaa"), System::nanoTime);
        assertThat(runner.redactSensitive("")).isEmpty();
        assertThat(runner.redactSensitive(null)).isNull();
    }

    @Test
    void redactionLeavesNonSecretTextUnchanged() {
        CodexRunner runner = new CodexRunner(
                Map.of("GH_TOKEN", "ghp_aaa"), System::nanoTime);
        String clean = "just a normal log line with no secrets";
        assertThat(runner.redactSensitive(clean)).isEqualTo(clean);
    }

    // -- dry run -------------------------------------------------------------

    @Test
    void dryRunSuppressesExecutionAndReturnsSuccess() {
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime);
        Map<String, Object> config = map("codex", map("executable", "codex", "model", "m"));
        CodexRunner.CodexResult result = runner.run(config, Path.of("/tmp/prompt.md"),
                Path.of("/repo"), true, null, null);
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).isEmpty();
    }

    @Test
    void dryRunSuppressesEvenMissingExecutable() {
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime);
        Map<String, Object> config = map("codex", map("executable", "/does/not/exist",
                "model", "m", "reasoning_effort", "low", "approval_policy", "never"));
        CodexRunner.CodexResult result = runner.run(config, Path.of("/tmp/prompt.md"),
                Path.of("/repo"), true, null, null);
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).isEmpty();
    }

    // Subprocess timeout and heartbeat cleanup are covered by CodexRunnerProcessTest.

    // -- command construction (approval flag) --------------------------------

    @Test
    void commandUsesSupportedApprovalFlagNotAskForApproval() {
        List<String> command = CodexRunner.buildCommand(
                map("executable", "codex", "model", "m", "reasoning_effort", "low",
                        "approval_policy", "never"),
                Path.of("/repo"));
        assertThat(command).contains("--approve-for-me");
        assertThat(command).doesNotContain("--ask-for-approval");
    }

    // -- per-phase override resolution --------------------------------------

    @Test
    void effectiveConfigReturnsBaseWhenNoOverridesConfigured() {
        Map<String, Object> codex = map("executable", "codex", "model", "base-model",
                "reasoning_effort", "medium");
        Map<String, Object> eff = CodexRunner.effectiveCodexConfig(codex, "IMPLEMENTING");
        assertThat(eff.get("model")).isEqualTo("base-model");
        assertThat(eff.get("reasoning_effort")).isEqualTo("medium");
    }

    @Test
    void effectiveConfigReturnsBaseWhenPhaseNullOrUnlisted() {
        Map<String, Object> codex = map("model", "base-model", "reasoning_effort", "medium",
                "phase_overrides", map("RECONCILING", map("reasoning_effort", "high")));
        // null phase -> base
        assertThat(CodexRunner.effectiveCodexConfig(codex, null).get("reasoning_effort"))
                .isEqualTo("medium");
        // phase not present in overrides -> base
        assertThat(CodexRunner.effectiveCodexConfig(codex, "IMPLEMENTING").get("reasoning_effort"))
                .isEqualTo("medium");
    }

    @Test
    void effectiveConfigAppliesPartialOverrideAndInheritsRest() {
        Map<String, Object> codex = map("model", "base-model", "reasoning_effort", "medium",
                "approval_policy", "never",
                "phase_overrides", map("RECONCILING", map("reasoning_effort", "high")));
        Map<String, Object> eff = CodexRunner.effectiveCodexConfig(codex, "RECONCILING");
        // override wins for effort...
        assertThat(eff.get("reasoning_effort")).isEqualTo("high");
        // ...but model and other base keys are inherited...
        assertThat(eff.get("model")).isEqualTo("base-model");
        assertThat(eff.get("approval_policy")).isEqualTo("never");
        // ...and the phase_overrides key never leaks into the effective config.
        assertThat(eff).doesNotContainKey("phase_overrides");
    }

    @Test
    void effectiveConfigOverrideFlowsIntoBuiltCommand() {
        Map<String, Object> codex = map("executable", "codex", "model", "base-model",
                "reasoning_effort", "medium",
                "phase_overrides", map("PLANNING",
                        map("model", "planner-model", "reasoning_effort", "high")));
        List<String> command = CodexRunner.buildCommand(
                CodexRunner.effectiveCodexConfig(codex, "PLANNING"), Path.of("/repo"));
        assertThat(command).containsSequence("--model", "planner-model");
        assertThat(command).contains("model_reasoning_effort=high");
    }
}
