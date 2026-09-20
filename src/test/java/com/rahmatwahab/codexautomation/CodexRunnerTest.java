package com.rahmatwahab.codexautomation;

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
                "--approve-for-me", "exec", "-");
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

    // -- mid-phase lost-lock handling ----------------------------------------
    //
    // The mid-phase lost-lock path (heartbeat throws LockOwnershipLost while the
    // worker runs) is verified deterministically without launching a process:
    //  - WorkflowLockTest proves refresh() throws LockOwnershipLost when the
    //    owner file is removed or ownership changes (the source of the throw).
    //  - ControllerTest/Controller proves the top-level handler catches
    //    LockOwnershipLost and ends the run cleanly (INFO, return 0).
    //  - CodexRunner.run() wraps the read loop in `catch (RuntimeException abort)`
    //    which force-destroys and reaps the child before rethrowing, so the
    //    worker is never orphaned. A real-process test here is intentionally
    //    avoided: run() builds its own command internally, leaving no seam to
    //    inject a controllable long-lived child without launching a real codex.

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
}
