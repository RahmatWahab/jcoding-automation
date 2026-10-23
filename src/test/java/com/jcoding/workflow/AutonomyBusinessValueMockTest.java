package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mock business invariants: protect bounded spend and keep tool execution offline. */
class AutonomyBusinessValueMockTest {
    @TempDir Path temporary;
    @Test
    void missingProviderUsageConsumesConfiguredTokenAllowanceInsteadOfEnablingFreeRetries() {
        Map<String, Object> state = new LinkedHashMap<>(Map.of("current_story", 73));
        Map<String, Object> limits = Map.of("max_story_tokens", 500,
                "max_story_worker_calls", 8, "max_story_active_seconds", 600);

        ExecutionBudget.reserve(state, limits, 60);
        ExecutionBudget.finish(state, 1_000, Map.of()); // provider omitted usage telemetry

        assertThatThrownBy(() -> ExecutionBudget.reserve(state, limits, 60))
                .isInstanceOf(ExecutionBudget.Exhausted.class)
                .hasMessageContaining("reported_tokens=500");
    }

    @Test
    void modelWorkerCannotGrantNetworkToRepositoryToolSubprocesses() {
        var command = CodexRunner.buildCommand(Map.of("model", "example-model",
                "reasoning_effort", "low", "approval_policy", "never"), Path.of("/workspace/repo"));

        assertThat(command).contains("sandbox_workspace_write.network_access=false");
    }

    @Test
    void workerEnvironmentNeverReceivesDeliveryOrProviderCredentials() {
        Map<String, String> worker = WorkerIsolation.environment(Map.of("PATH", "/usr/bin",
                "GH_TOKEN", "github-secret", "OPENAI_API_KEY", "provider-secret"), true);
        assertThat(worker).doesNotContainKeys("GH_TOKEN", "OPENAI_API_KEY");
        assertThat(worker.get("GIT_TERMINAL_PROMPT")).isEqualTo("0");
    }

    @Test
    void lostGithubResponseCannotDuplicateCommentUntilAnOperatorReconcilesTheEffect() {
        var store = new com.jcoding.state.StateStore(temporary.resolve("effects.json"));
        var journal = new com.jcoding.state.EffectJournal(store);
        Map<String, Object> payload = Map.of("issue", 73, "body", "Clarification required");
        AtomicInteger remoteComments = new AtomicInteger();
        AtomicBoolean remoteVisible = new AtomicBoolean();
        AtomicBoolean first = new AtomicBoolean(true);
        java.util.function.Function<String, Object> observe = key -> remoteVisible.get()
                ? Map.of("id", 901, "key", key) : null;
        java.util.function.Function<String, Object> mutate = key -> {
            remoteComments.incrementAndGet();
            if (first.getAndSet(false)) throw new IllegalStateException("response lost");
            remoteVisible.set(true);
            return Map.of("id", 901);
        };

        assertThatThrownBy(() -> journal.execute("comment", payload, false, observe, mutate, () -> { }))
                .hasMessageContaining("response lost");
        assertThatThrownBy(() -> journal.execute("comment", payload, false, observe, mutate, () -> { }))
                .hasMessageContaining("uncertain");
        assertThat(remoteComments.get()).isEqualTo(1);
        String key = com.jcoding.state.EffectJournal.key("comment", payload);
        store.authorizeEffectRetry(key, "Complete remote pagination proved the comment absent");
        journal.execute("comment", payload, false, observe, mutate, () -> { });
        assertThat(remoteComments.get()).isEqualTo(2);
        assertThat(store.effect(key).get("status")).isEqualTo("VERIFIED");
    }

    @Test
    void crashResumeAndBoundedReplanKeepUnchangedVerifiedBusinessWork() {
        var store = new com.jcoding.state.StateStore(temporary.resolve("tasks.json"));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("goal_contract", Map.of("requirements_hash", "story-version-1"));
        Map<String, Object> first = Map.of("id", "login", "description", "Implement login",
                "depends_on", List.of(), "criteria", List.of("AC-1"), "checks", List.of("login-check"));
        Map<String, Object> second = Map.of("id", "recovery", "description", "Implement recovery",
                "depends_on", List.of("login"), "criteria", List.of("AC-2"), "checks", List.of("recovery-check"));
        TaskLedger.bind(state, new TaskPlan.Validated("plan-v1", List.of(first, second)));
        TaskLedger.verified(TaskLedger.tasks(state).getFirst(), Map.of("snapshot", "verified-head",
                "requirements_hash", "story-version-1", "global_policy_hash", "policy-v1"));
        store.save(state);

        Map<String, Object> resumed = store.load();
        Map<String, Object> failed = TaskLedger.next(resumed);
        assertThat(failed.get("id")).isEqualTo("recovery");
        assertThat(TaskLedger.requestReplan(resumed, failed, 1)).isTrue();
        assertThat(TaskLedger.requestReplan(resumed, failed, 1)).isFalse();
        Map<String, Object> revised = Map.of("id", "recovery", "description", "Use a different recovery strategy",
                "depends_on", List.of("login"), "criteria", List.of("AC-2"), "checks", List.of("recovery-check"));
        TaskLedger.bind(resumed, new TaskPlan.Validated("plan-v2", List.of(first, revised)));
        assertThat(TaskLedger.tasks(resumed).getFirst().get("status")).isEqualTo("VERIFIED");
        assertThat(TaskLedger.tasks(resumed).get(1).get("status")).isEqualTo("PENDING");
        assertThat(TaskLedger.complete(resumed)).isFalse();
    }

    @Test
    void ambiguousIntentAbstainsAndCompoundWorkRequiresAnExplicitOrderedContract() {
        Map<String, Object> issue = Map.of("title", "Fix broken login and add recovery", "body", "");
        IntentRegistry.Selection unresolved = IntentRegistry.select(issue, Map.of(), Map.of());
        assertThat(unresolved.intent()).isBlank();
        assertThat(unresolved.questions()).isNotEmpty();
        assertThat(unresolved.candidates()).isNotEmpty();

        IntentRegistry.Selection compound = IntentRegistry.select(issue,
                Map.of("intents", List.of("repair_story", "implement_story")), Map.of());
        assertThat(compound.questions()).isEmpty();
        assertThat(compound.sequence()).containsExactly("repair_story", "implement_story");
        IntentRegistry.Selection conflict = IntentRegistry.select(
                Map.of("title", "Fix login", "labels", List.of("intent:implement_story")),
                Map.of("intent", "repair_story"), Map.of());
        assertThat(conflict.questions()).anyMatch(q -> q.contains("conflicting"));
    }

    @Test
    void clarificationOnlyAcceptsAReplyBoundToThePendingRequestFromATrustedMaintainer() {
        String marker = "<!-- jcoding-answer:1234567890abcdef -->";
        String body = marker + "\n```jcoding-intent\n{\"intent\":\"repair_story\"}\n```";
        Map<String, Object> outsider = Map.of("id", 1, "author_association", "NONE", "body", body,
                "created_at", "2026-01-01T00:00:00Z");
        Map<String, Object> maintainer = Map.of("id", 2, "author_association", "MEMBER", "body", body,
                "created_at", "2026-01-02T00:00:00Z", "author", Map.of("login", "requester"));
        assertThat(ClarificationReply.latest(List.of(List.of(outsider, maintainer)),
                "1234567890abcdef1234567890abcdef", "requester").get("id")).isEqualTo("2");
        assertThat(ClarificationReply.latest(List.of(List.of(outsider)),
                "1234567890abcdef1234567890abcdef", "requester")).isEmpty();
    }

    @Test
    void canaryReceiptsAreBoundToTheExactPolicyAndExpire() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("repository", Map.of("local_path", "/tmp/target", "owner", "example", "name", "target"));
        config.put("codex", Map.of("isolation", "bubblewrap", "model", "canary-model"));
        config.put("verification", Map.of("required", true, "checks", List.of(Map.of(
                "id", "business", "command", List.of("true"), "criteria", List.of("AC-1")))));
        config.put("automation", Map.of("enable_planning", true));
        config.put("intent", Map.of("require_acceptance_criteria", true));
        config.put("workflow", Map.of("required_checks", List.of("build"), "allow_no_checks", false));
        config.put("deployment", Map.of("mode", "autonomous", "canary_valid_hours", 24,
                "provider_canary_valid_hours", 24));
        String fingerprint = DeploymentPolicy.fingerprint(config);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("provider_canary", Map.of("configuration_hash", fingerprint, "verified_at", Instant.now().toString()));
        state.put("canary_evidence", Map.of("configuration_hash", fingerprint, "verified_at", Instant.now().toString()));
        DeploymentPolicy.admit(config, state, 73);
        config.put("codex", Map.of("isolation", "bubblewrap", "model", "changed-model"));
        assertThatThrownBy(() -> DeploymentPolicy.admit(config, state, 73))
                .hasMessageContaining("provider canary");
    }

    private Map<String, Object> rolloutConfig(Map<String, Object> workflow) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("repository", Map.of("local_path", "/tmp/target", "owner", "example", "name", "target"));
        config.put("codex", Map.of("isolation", "bubblewrap"));
        config.put("verification", Map.of("required", true, "checks", List.of(Map.of(
                "id", "business", "command", List.of("true"), "criteria", List.of("*")))));
        config.put("automation", Map.of("enable_planning", true));
        config.put("intent", Map.of("require_acceptance_criteria", true));
        config.put("workflow", workflow);
        config.put("deployment", Map.of("mode", "autonomous"));
        return config;
    }

    @Test
    void ciChecksAreOptionalOnlyThroughAnExplicitOptOut() {
        assertThat(DeploymentPolicy.readiness(rolloutConfig(Map.of("required_checks", List.of(), "allow_no_checks", true))))
                .isEmpty();
        assertThat(DeploymentPolicy.readiness(rolloutConfig(Map.of("required_checks", List.of("build"), "allow_no_checks", false))))
                .isEmpty();
        // Silence is not consent: neither named checks nor the explicit opt-out fails closed.
        assertThat(DeploymentPolicy.readiness(rolloutConfig(Map.of("required_checks", List.of()))))
                .anyMatch(reason -> reason.contains("allow_no_checks"));
        assertThat(DeploymentPolicy.readiness(rolloutConfig(Map.of("required_checks", List.of(), "allow_no_checks", false))))
                .anyMatch(reason -> reason.contains("named CI checks"));
    }

    @Test
    void canaryReceiptIsRecordedWithoutCiOnlyWhenOptedOut() {
        Map<String, Object> pr = new LinkedHashMap<>(Map.of("headRefOid", "abc", "number", 7, "statusCheckRollup", List.of()));
        Map<String, Object> optedOut = rolloutConfig(Map.of("required_checks", List.of(), "allow_no_checks", true));
        optedOut.put("deployment", Map.of("mode", "canary"));
        Map<String, Object> state = new LinkedHashMap<>(Map.of("validation_checkpoint", Map.of("delivered_head", "abc"),
                "current_story", 1));
        DeploymentPolicy.recordCanary(optedOut, state, pr);
        assertThat(state).containsKey("canary_evidence");

        Map<String, Object> strict = rolloutConfig(Map.of("required_checks", List.of("build"), "allow_no_checks", false));
        strict.put("deployment", Map.of("mode", "canary"));
        Map<String, Object> strictState = new LinkedHashMap<>(Map.of("validation_checkpoint", Map.of("delivered_head", "abc"),
                "current_story", 1));
        DeploymentPolicy.recordCanary(strict, strictState, pr);
        assertThat(strictState).doesNotContainKey("canary_evidence");
    }
}
