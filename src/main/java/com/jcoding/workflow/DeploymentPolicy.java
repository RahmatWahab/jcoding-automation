package com.jcoding.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit rollout admission; a passing, revision-bound canary permits autonomous promotion. */
final class DeploymentPolicy {
    private DeploymentPolicy() { }

    static String mode(Map<String, Object> config) {
        return Engine.asString(Config.section(config, "deployment").getOrDefault("mode", "development"));
    }

    static List<String> readiness(Map<String, Object> config) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> deployment = Config.section(config, "deployment");
        Map<String, Object> codex = Config.section(config, "codex");
        Map<String, Object> verification = Config.section(config, "verification");
        Map<String, Object> workflow = Config.section(config, "workflow");
        if (!List.of("canary", "autonomous").contains(mode(config))) reasons.add("Select deployment.mode canary or autonomous for unattended execution");
        if (!"bubblewrap".equals(codex.get("isolation"))) reasons.add("Unattended workers require bubblewrap isolation");
        if (Engine.asList(verification.get("checks")).isEmpty() || Boolean.FALSE.equals(verification.get("required"))) reasons.add("Configure required executable business checks");
        for (Object raw : Engine.asList(verification.get("checks"))) {
            if (raw instanceof Map<?, ?> check && Engine.asList(check.get("criteria")).isEmpty()) reasons.add("Every check must map acceptance criterion IDs: " + check.get("id"));
        }
        if (!Boolean.TRUE.equals(Config.section(config, "automation").get("enable_planning"))) reasons.add("Unattended execution requires task planning");
        if (!Boolean.TRUE.equals(Config.section(config, "intent").get("require_acceptance_criteria"))) reasons.add("Explicit acceptance criteria must be required");
        rolloutReadiness(config, reasons);
        return reasons;
    }

    private static void rolloutReadiness(Map<String, Object> config, List<String> reasons) {
        Map<String, Object> deployment = Config.section(config, "deployment");
        Map<String, Object> workflow = Config.section(config, "workflow");
        if ("canary".equals(mode(config))) {
            if (!Config.isPositiveInt(deployment.get("canary_story"))) reasons.add("Canary requires one positive canary_story");
            if (Boolean.TRUE.equals(workflow.get("auto_merge"))) reasons.add("Canary cannot merge");
        }
        // CI is optional but must be an explicit decision: name the required checks, or
        // set workflow.allow_no_checks=true to run on controller-side verification alone.
        if (Engine.asList(workflow.get("required_checks")).isEmpty() && !Boolean.TRUE.equals(workflow.get("allow_no_checks"))) {
            reasons.add("Rollout requires named CI checks (workflow.required_checks) or an explicit workflow.allow_no_checks=true");
        }
        if (config.containsKey("_controller_root")) {
            java.nio.file.Path controller = java.nio.file.Path.of(config.get("_controller_root").toString()).toAbsolutePath().normalize();
            java.nio.file.Path target = java.nio.file.Path.of(Engine.asString(Config.section(config, "repository").get("local_path"))).toAbsolutePath().normalize();
            if (controller.startsWith(target) || target.startsWith(controller)) reasons.add("Worker repository and controller installation must be separate trees");
        }
    }

    static String fingerprint(Map<String, Object> config) {
        Map<String, Object> permissions = new LinkedHashMap<>(Config.section(config, "workflow"));
        permissions.remove("auto_merge"); // The sole permission promoted by the successful canary.
        return GoalContract.hashData(Map.of("repository", Config.section(config, "repository"),
                "codex", Config.section(config, "codex"), "verification", Config.section(config, "verification"),
                "workflow", permissions, "intent", Config.section(config, "intent"),
                "automation", Config.section(config, "automation"), "runtime", RuntimeIdentity.fingerprint(config)));
    }

    static void admit(Map<String, Object> config, Map<String, Object> state, Integer story) {
        if ("development".equals(mode(config))) return; // Embedded/mock callers; CLI forbids unattended development mode.
        List<String> failures = readiness(config);
        if (!failures.isEmpty()) throw new IllegalStateException(String.join("; ", failures));
        Object provider = state.get("provider_canary");
        if (!(provider instanceof Map<?, ?> proof) || !fingerprint(config).equals(proof.get("configuration_hash"))) {
            throw new IllegalStateException("Current runtime requires a read-only provider canary");
        }
        long providerHours = Engine.asLongOrDefault(Config.section(config, "deployment")
                .get("provider_canary_valid_hours"), 24);
        if (Instant.parse(Engine.asString(((Map<?, ?>) provider).get("verified_at")))
                .plusSeconds(providerHours * 3600).isBefore(Instant.now())) {
            throw new IllegalStateException("Provider canary evidence expired");
        }
        if ("canary".equals(mode(config))) {
            if (story != null && !story.equals(Engine.asInt(Config.section(config, "deployment").get("canary_story")))) {
                throw new IllegalStateException("Canary is confined to its configured story");
            }
            return;
        }
        Object raw = state.get("canary_evidence");
        if (!(raw instanceof Map<?, ?> receipt) || !fingerprint(config).equals(receipt.get("configuration_hash"))) {
            throw new IllegalStateException("Autonomous rollout requires a canary for the current configuration");
        }
        long hours = Engine.asLongOrDefault(Config.section(config, "deployment").get("canary_valid_hours"), 168);
        if (Instant.parse(Engine.asString(receipt.get("verified_at"))).plusSeconds(hours * 3600).isBefore(Instant.now())) {
            throw new IllegalStateException("Canary evidence expired");
        }
        if (Boolean.TRUE.equals(state.get("autonomy_demoted"))) throw new IllegalStateException("Autonomy demoted after an invariant failure; run a new canary");
    }

    static void recordCanary(Map<String, Object> config, Map<String, Object> state, Map<String, Object> pr) {
        if (!"canary".equals(mode(config))) return;
        if (!(state.get("validation_checkpoint") instanceof Map<?, ?> proof)
                || !Engine.asString(pr.get("headRefOid")).equals(proof.get("delivered_head"))) return;
        Map<String, Object> workflow = Config.section(config, "workflow");
        if (CiGate.evaluate(pr.get("statusCheckRollup"), Engine.asList(workflow.get("required_checks"))
                .stream().map(String::valueOf).toList(), Boolean.TRUE.equals(workflow.get("allow_no_checks"))) != CiGate.Verdict.PASSED) return;
        if (!Engine.actionableFeedback(pr).isEmpty() || ReviewCoordinator.reviewGateOpen(pr)) return;
        state.put("canary_evidence", Map.of("configuration_hash", fingerprint(config), "head", pr.get("headRefOid"),
                "story", state.get("current_story"), "pr", pr.get("number"), "verified_at", Instant.now().toString()));
        state.remove("autonomy_demoted");
    }
}
