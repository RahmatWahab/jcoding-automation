package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutonomyContractsTest {
    @TempDir Path root;

    @Test void ciRequiresPositiveEvidenceForEveryRequiredCheck() {
        assertThat(CiGate.evaluate(List.of(Map.of("state", "FAILURE")), List.of(), false)).isEqualTo(CiGate.Verdict.FAILED);
        assertThat(CiGate.evaluate(List.of(), List.of(), false)).isEqualTo(CiGate.Verdict.WAITING);
        assertThat(CiGate.evaluate(List.of(Map.of("state", "SUCCESS", "context", "unit")), List.of("unit"), false)).isEqualTo(CiGate.Verdict.PASSED);
        assertThat(CiGate.evaluate(List.of(Map.of("state", "SUCCESS", "context", "unit")), List.of("security"), false)).isEqualTo(CiGate.Verdict.WAITING);
        assertThat(CiGate.evaluate(List.of(Map.of("conclusion", "SKIPPED", "name", "security")), List.of("security"), false)).isEqualTo(CiGate.Verdict.WAITING);
        assertThat(CiGate.evaluate(List.of(Map.of("status", "MYSTERY")), List.of(), false)).isEqualTo(CiGate.Verdict.WAITING);
    }

    @Test void mutationAndCancellationAreCheckedAtExecutionBoundary() throws Exception {
        Path control = root.resolve("control.json");
        Files.writeString(control, "{\"job_start\":true}");
        Map<String,Object> config = new LinkedHashMap<>(Map.of("_job_control_path", control.toString(),
                "automation", Map.of("dry_run", false), "workflow", Map.of("allow_push", true)));
        ActionPolicy policy = new ActionPolicy(config);
        assertThatCode(() -> policy.require("git")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.require("merge")).hasMessageContaining("auto_merge");
        Files.writeString(control, "{\"job_start\":false}");
        assertThatThrownBy(() -> policy.require("git")).isInstanceOf(ActionPolicy.Cancelled.class);
        config.put("_force_run", true);
        Files.writeString(control, "{\"job_start\":true,\"cancel\":true}");
        assertThatThrownBy(policy::checkActive).isInstanceOf(ActionPolicy.Cancelled.class);
        assertThatThrownBy(() -> new ActionPolicy(Map.of(), true).require("git")).hasMessageContaining("Dry-run");
    }

    @Test void changedRequirementsInvalidateProofButPreserveHistory() {
        Map<String,Object> state = new LinkedHashMap<>();
        Map<String,Object> config = Map.of("intent", Map.of("require_acceptance_criteria", true));
        Map<String,Object> issue = new LinkedHashMap<>(Map.of("number", 42, "title", "Fix", "body", "## Acceptance criteria\n- Correct output"));
        assertThat(GoalContract.observe(state, GoalContract.capture(issue, Map.of(), config))).isFalse();
        state.put("validation_checkpoint", Map.of("delivered_head", "old"));
        state.put("plan_binding", Map.of());
        issue.put("body", "## Acceptance criteria\n- Different output");
        assertThat(GoalContract.observe(state, GoalContract.capture(issue, Map.of(), config))).isTrue();
        assertThat(state).doesNotContainKeys("validation_checkpoint", "plan_binding");
        assertThat((List<?>) state.get("goal_history")).hasSize(1);
        issue.put("body", "Unclear request");
        assertThat(GoalContract.capture(issue, Map.of(), config).get("status")).isEqualTo("NEEDS_CLARIFICATION");
    }

    @Test void intentContractRejectsUnknownIntentAndCannotGrantWorkerAuthority() {
        Map<String,Object> config = Map.of("intent", Map.of("require_acceptance_criteria", false),
                "workflow", Map.of("allow_push", false, "auto_merge", true));
        Map<String,Object> issue = Map.of("number", 7, "title", "Do work",
                "body", "```jcoding-intent\n{\"intent\":\"deploy_to_production\"}\n```\nRequirements");
        Map<String,Object> goal = GoalContract.capture(issue, Map.of(), config);
        assertThat(goal.get("status")).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(goal.get("allowed_actions")).isEqualTo(List.of("implement", "verify"));
        assertThat(goal.get("autonomy_ceiling")).isEqualTo("controller_policy");
    }

    @Test void planMustBelongToStoryAndCoverCriteriaWithAcyclicTasks() throws Exception {
        Map<String,Object> state = new LinkedHashMap<>(Map.of("current_story", 42, "base_commit", "base",
                "goal_contract", Map.of("requirements_hash", "req", "criteria", List.of(Map.of("id", "AC-1")))));
        Map<String,Object> config = Map.of("verification", Map.of("checks", List.of(Map.of("id", "unit",
                "command", List.of("mock-check"), "criteria", List.of("AC-1")))));
        Map<String,Object> task = new LinkedHashMap<>(Map.of("id", "T1", "description", "Fix behavior",
                "depends_on", List.of(), "criteria", List.of("AC-1"), "checks", List.of("unit")));
        Map<String,Object> plan = new LinkedHashMap<>(Map.of("schema_version", 1, "story", 42,
                "requirements_hash", "req", "base_sha", "base", "tasks", List.of(task)));
        ObjectMapper json = new ObjectMapper();
        json.writeValue(root.resolve("plan.json").toFile(), plan);
        assertThat(TaskPlan.validate(root, state, config).tasks()).hasSize(1);
        plan.put("story", 43); json.writeValue(root.resolve("plan.json").toFile(), plan);
        assertThatThrownBy(() -> TaskPlan.validate(root, state, config)).hasMessageContaining("does not match");
        plan.put("story", 42); task.put("depends_on", List.of("T1"));
        json.writeValue(root.resolve("plan.json").toFile(), plan);
        assertThatThrownBy(() -> TaskPlan.validate(root, state, config)).hasMessageContaining("dependency");
    }

    @Test void passiveWaitBudgetSurvivesTicksAndWakesOnNewEvidence() {
        Map<String,Object> state = new LinkedHashMap<>();
        Map<String,Object> pr = new LinkedHashMap<>(Map.of("headRefOid", "a"));
        Map<String,Object> options = Map.of("wait_timeout_seconds", 10, "max_wait_diagnoses", 1);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(WaitPolicy.observe(state, "WAITING_FOR_CI", pr, options, now)).isEqualTo(WaitPolicy.Decision.WAIT);
        assertThat(WaitPolicy.observe(state, "WAITING_FOR_CI", pr, options, now.plusSeconds(11))).isEqualTo(WaitPolicy.Decision.DIAGNOSE);
        assertThat(WaitPolicy.observe(state, "WAITING_FOR_CI", pr, options, now.plusSeconds(22))).isEqualTo(WaitPolicy.Decision.HOLD);
        pr.put("headRefOid", "b");
        assertThat(WaitPolicy.observe(state, "WAITING_FOR_CI", pr, options, now.plusSeconds(23))).isEqualTo(WaitPolicy.Decision.WAIT);
    }

    @Test void recoveryChangesStrategyWithoutChangingPermissions() {
        Map<String,Object> state = new LinkedHashMap<>(Map.of("goal_contract", Map.of("requirements_hash", "r")));
        for (int i=0; i<3; i++) RecoveryMemory.observe(state, "VERIFYING", "retry", "same failure", false);
        assertThat(state.get("recovery_strategy")).isEqualTo("REPLAN_OR_ESCALATE");
        for (int i=0; i<60; i++) RecoveryMemory.observe(state, "VERIFYING", "retry", "same failure", false);
        assertThat((List<?>) state.get("recovery_memory")).hasSize(50);
        assertThat(RecoveryMemory.relevant(state)).hasSize(3);
    }

    @Test void workersDoNotInheritDeliveryCredentialsOrInjectionEnvironment() {
        Map<String,String> clean = WorkerIsolation.environment(Map.of("PATH", "/usr/bin", "GH_TOKEN", "secret",
                "AWS_SECRET_ACCESS_KEY", "secret2", "BASH_ENV", "/tmp/evil", "OPENAI_API_KEY", "model"), true);
        assertThat(clean).doesNotContainKeys("GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "BASH_ENV");
        assertThat(clean).doesNotContainKey("OPENAI_API_KEY");
        assertThat(WorkerIsolation.environment(clean, false)).doesNotContainKey("OPENAI_API_KEY");
    }

    @Test void branchSlugIsOwnershipEvidence() {
        assertThat(Engine.prForStory(List.of(Map.of("headRefName", "story/42-fix")), 42)).hasSize(1);
        assertThat(Engine.prForStory(List.of(Map.of("headRefName", "story/420-fix")), 42)).isEmpty();
    }

    @Test void taskRepairStopsAfterRepeatedIdenticalCriterionFailureAndClearsOnVerification() {
        Map<String, Object> task = new LinkedHashMap<>(Map.of("id", "T1", "criteria", List.of("AC-1")));
        assertThat(TaskLedger.observeFailure(task, "same-check-failure")).isFalse();
        assertThat(TaskLedger.observeFailure(task, "same-check-failure")).isTrue();
        TaskLedger.verified(task, Map.of("identity", "new-proof"));
        assertThat(task).doesNotContainKeys("failure_signature", "failure_repeats", "stalled_at");
    }
}
