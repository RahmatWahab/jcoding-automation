package com.jcoding.workflow;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Controller-owned executable proof, bound to source, requirements and verification policy. */
final class RuntimeVerifier {
    @FunctionalInterface interface Executor {
        BoundedProcess.Result run(List<String> command, Path repo, int timeout, Runnable heartbeat);
    }
    private final Executor executor;
    RuntimeVerifier(Executor executor) { this.executor = executor; }

    static RuntimeVerifier isolated(Map<String, Object> config) {
        return new RuntimeVerifier((command, repo, timeout, heartbeat) -> {
            try (WorkerIsolation isolation = WorkerIsolation.create(false)) {
                List<String> wrapped = isolation.wrap(command, repo, Config.section(config, "verification"), false, false);
                return BoundedProcess.run(wrapped, repo, WorkerIsolation.environment(System.getenv(), false), timeout, heartbeat);
            } catch (java.io.IOException exc) { throw new IllegalStateException("Cannot create verifier sandbox", exc); }
        });
    }

    /**
     * Criterion IDs a check covers. The wildcard {@code "*"} means "every criterion in the
     * current story contract", so one suite-level check need not be edited per story. The
     * check must still execute and exit 0 for any criterion to be marked VERIFIED.
     */
    private static List<String> mappedCriteria(Map<?, ?> check, Set<String> requiredCriteria) {
        List<String> declared = Engine.asList(check.get("criteria")).stream().map(String::valueOf).toList();
        if (!declared.contains("*")) return declared;
        List<String> expanded = new ArrayList<>(requiredCriteria);
        java.util.Collections.sort(expanded);
        for (String id : declared) if (!"*".equals(id) && !expanded.contains(id)) expanded.add(id);
        return expanded;
    }

    static boolean required(Map<String, Object> config) {
        return Boolean.TRUE.equals(Config.section(config, "workflow").get("auto_merge"))
                || !Boolean.FALSE.equals(Config.section(config, "verification").get("required"));
    }

    @SuppressWarnings("unchecked")
    boolean verify(Path repo, GitOperations git, Map<String, Object> state,
                   Map<String, Object> config, Runnable heartbeat, Runnable persist) {
        Map<String, Object> policy = Config.section(config, "verification");
        List<Object> checks = Engine.asList(policy.get("checks"));
        if (checks.isEmpty()) {
            return fail(state, "Configure controller-owned verification checks before delivery");
        }
        String snapshot = git.recoveryFingerprint();
        if (snapshot.equals("unavailable")) return fail(state, "Cannot fingerprint validation source");
        String requirements = GoalContract.requirementsHash(state);
        String policyHash = GoalContract.hashData(policy);
        String identity = snapshot + ":" + requirements + ":" + policyHash;
        if (state.get("validation_checkpoint") instanceof Map<?, ?> old && identity.equals(old.get("identity"))
                && criteriaPassed(state, identity)) return true;
        List<Map<String, Object>> evidence = new ArrayList<>();
        try {
            List<String> paths = git.changedPathsSince(Engine.asString(state.get("base_commit")));
            List<Object> allowed = Engine.asList(policy.getOrDefault("allowed_paths", List.of("**")));
            List<Object> protectedPaths = Engine.asList(policy.getOrDefault("protected_paths", List.of(".github/**")));
            int maxFiles = (int) Engine.asLongOrDefault(policy.get("max_changed_files"), 100);
            if (paths.size() > maxFiles) return fail(state, "Changed-file scope exceeds " + maxFiles);
            for (String path : paths) {
                if (!matches(path, allowed) || matches(path, protectedPaths)) return fail(state, "Path outside authorized scope: " + path);
            }
            Set<String> requiredCriteria = new HashSet<>();
            if (state.get("goal_contract") instanceof Map<?, ?> goal) {
                for (Object value : Engine.asList(goal.get("criteria"))) requiredCriteria.add(((Map<?, ?>) value).get("id").toString());
            }
            if (requiredCriteria.isEmpty()) return fail(state, "No current acceptance criteria to verify");
            Set<String> covered = new HashSet<>();
            Set<String> checkIds = new HashSet<>();
            Map<String, Object> criterionEvidence = new java.util.LinkedHashMap<>();
            for (String criterion : requiredCriteria) criterionEvidence.put(criterion, Map.of(
                    "status", "UNVERIFIED", "identity", identity, "source", snapshot,
                    "requirements_hash", requirements, "policy_hash", policyHash));
            state.put("criterion_evidence", criterionEvidence);
            for (Object value : checks) {
                if (!(value instanceof Map<?, ?> check)) return fail(state, "Malformed verifier check");
                String id = Engine.asString(check.get("id"));
                if (id.isBlank() || !checkIds.add(id)) return fail(state, "Missing or duplicate verifier ID");
                List<String> command = Engine.asList(check.get("command")).stream().map(String::valueOf).toList();
                if (command.isEmpty()) return fail(state, "Missing verifier command: " + id);
                int seconds = (int) Engine.asLongOrDefault(check.get("timeout_seconds"), 300);
                int reserved = ExecutionBudget.reserveTool(state, Config.section(config, "automation"), seconds);
                persist.run(); // A crash consumes the reservation.
                long start = System.nanoTime();
                BoundedProcess.Result result;
                try { result = executor.run(command, repo, reserved, heartbeat); }
                finally { ExecutionBudget.finishTool(state, (System.nanoTime() - start) / 1_000_000L); persist.run(); }
                String output = new CodexRunner().redactSensitive(result.output());
                evidence.add(Map.of("id", id, "command", command, "exit_code", result.exitCode(),
                        "output", output.substring(Math.max(0, output.length() - 12000)), "at", Controller.now()));
                state.put("verification_evidence", evidence);
                boolean passed = result.exitCode() == 0 && !result.truncated();
                List<String> mapped = mappedCriteria(check, requiredCriteria);
                for (String criterionId : mapped) {
                    if (!requiredCriteria.contains(criterionId)) return fail(state, "Check maps unknown criterion: " + criterionId);
                    criterionEvidence.put(criterionId, Map.of("status", passed ? "VERIFIED" : "FAILED",
                            "identity", identity, "source", snapshot, "requirements_hash", requirements,
                            "policy_hash", policyHash, "check_id", id, "exit_code", result.exitCode(),
                            "observed_at", Controller.now()));
                }
                persist.run();
                if (!passed) return fail(state, "Verification failed: " + id);
                covered.addAll(mapped);
            }
            if (!covered.containsAll(requiredCriteria) || !criteriaPassed(state, identity))
                return fail(state, "Executable checks do not cover every acceptance criterion");
            if (!snapshot.equals(git.recoveryFingerprint())) return fail(state, "Source changed during verification; evidence invalidated");
            state.put("validation_checkpoint", Map.of("identity", identity, "snapshot", snapshot,
                    "requirements_hash", requirements, "policy_hash", policyHash, "checks", evidence,
                    "verified_at", Controller.now()));
            state.put("verification_mode", "EXECUTABLE");
            state.remove("verification_failure");
            persist.run();
            return true;
        } catch (ActionPolicy.Cancelled cancelled) { throw cancelled; }
        catch (RuntimeException exc) { return fail(state, "Verification could not complete: " + exc.getMessage()); }
    }

    /** Validate one task without installing a story-wide delivery checkpoint. */
    @SuppressWarnings("unchecked")
    boolean verifyTask(Path repo, GitOperations git, Map<String, Object> state, Map<String, Object> config,
                       Map<String, Object> task, Runnable heartbeat, Runnable persist) {
        Map<String, Object> scopedState = new java.util.LinkedHashMap<>(state);
        scopedState.remove("validation_checkpoint");
        Map<String, Object> scopedGoal = new java.util.LinkedHashMap<>((Map<String, Object>) state.get("goal_contract"));
        Set<String> criteria = new HashSet<>(Engine.asList(task.get("criteria")).stream().map(String::valueOf).toList());
        scopedGoal.put("criteria", Engine.asList(scopedGoal.get("criteria")).stream()
                .filter(c -> c instanceof Map<?, ?> m && criteria.contains(Engine.asString(m.get("id")))).toList());
        scopedState.put("goal_contract", scopedGoal);
        Map<String, Object> scopedPolicy = new java.util.LinkedHashMap<>(Config.section(config, "verification"));
        Set<String> checks = new HashSet<>(Engine.asList(task.get("checks")).stream().map(String::valueOf).toList());
        scopedPolicy.put("checks", Engine.asList(scopedPolicy.get("checks")).stream()
                .filter(c -> c instanceof Map<?, ?> m && checks.contains(Engine.asString(m.get("id")))).toList());
        scopedPolicy.put("required", true);
        Map<String, Object> scopedConfig = new java.util.LinkedHashMap<>(config);
        scopedConfig.put("verification", scopedPolicy);
        boolean passed = verify(repo, git, scopedState, scopedConfig, heartbeat, () -> {
            if (scopedState.containsKey(ExecutionBudget.KEY)) state.put(ExecutionBudget.KEY, scopedState.get(ExecutionBudget.KEY));
            if (scopedState.get("criterion_evidence") instanceof Map<?, ?> scopedEvidence) {
                Map<String, Object> merged = new java.util.LinkedHashMap<>();
                if (state.get("criterion_evidence") instanceof Map<?, ?> existing)
                    existing.forEach((key, value) -> merged.put(String.valueOf(key), value));
                scopedEvidence.forEach((key, value) -> merged.put(String.valueOf(key), value));
                state.put("criterion_evidence", merged);
            }
            task.put("verification_evidence", scopedState.getOrDefault("verification_evidence", List.of()));
            persist.run();
        });
        if (passed) {
            Map<String, Object> proof = new java.util.LinkedHashMap<>(
                    (Map<String, Object>) scopedState.get("validation_checkpoint"));
            proof.put("global_policy_hash", GoalContract.hashData(Config.section(config, "verification")));
            TaskLedger.verified(task, proof);
        }
        else {
            task.put("status", "BLOCKED");
            task.put("verification_failure", scopedState.get("verification_failure"));
        }
        persist.run();
        return passed;
    }

    static boolean matches(String path, List<Object> globs) {
        return globs.stream().anyMatch(g -> {
            String pattern = String.valueOf(g);
            if (pattern.equals("**")) return true;
            if (pattern.endsWith("/**") && path.startsWith(pattern.substring(0, pattern.length() - 3) + "/"))
                return true;
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(path));
        });
    }

    private static boolean fail(Map<String, Object> state, String reason) {
        state.remove("validation_checkpoint");
        state.put("verification_failure", reason);
        state.put("error", reason);
        return false;
    }

    private static boolean criteriaPassed(Map<String, Object> state, String identity) {
        if (!(state.get("goal_contract") instanceof Map<?, ?> goal)
                || !(state.get("criterion_evidence") instanceof Map<?, ?> matrix)) return false;
        List<Object> criteria = Engine.asList(goal.get("criteria"));
        if (criteria.isEmpty()) return false;
        for (Object raw : criteria) {
            if (!(raw instanceof Map<?, ?> criterion)) return false;
            Object row = matrix.get(Engine.asString(criterion.get("id")));
            if (!(row instanceof Map<?, ?> proof) || !"VERIFIED".equals(proof.get("status"))
                    || !identity.equals(proof.get("identity"))) return false;
        }
        return true;
    }
}
