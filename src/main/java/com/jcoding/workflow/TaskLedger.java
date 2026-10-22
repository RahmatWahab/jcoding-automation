package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Controller-owned task checkpoints. Worker-written ledger.md is never authoritative. */
final class TaskLedger {
    private TaskLedger() { }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> bind(Map<String, Object> state, TaskPlan.Validated plan) {
        Object raw = state.get("task_ledger");
        if (raw instanceof Map<?, ?> old && plan.hash().equals(old.get("plan_hash"))
                && GoalContract.requirementsHash(state).equals(old.get("requirements_hash"))) {
            return (List<Map<String, Object>>) old.get("tasks");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        Map<String, Map<String, Object>> verifiedOld = new java.util.HashMap<>();
        if (raw instanceof Map<?, ?> old) {
            for (Object item : Engine.asList(old.get("tasks"))) {
                if (item instanceof Map<?, ?> previous && "VERIFIED".equals(previous.get("status"))) {
                    verifiedOld.put(Engine.asString(previous.get("id")), (Map<String, Object>) previous);
                }
            }
        }
        for (Map<String, Object> definition : plan.tasks()) {
            Map<String, Object> task = new LinkedHashMap<>(definition);
            task.put("status", "PENDING");
            task.put("attempts", 0);
            task.remove("evidence");
            Map<String, Object> previous = verifiedOld.get(Engine.asString(definition.get("id")));
            boolean dependenciesPreserved = Engine.asList(definition.get("depends_on")).stream().allMatch(dep ->
                    tasks.stream().anyMatch(earlier -> dep.equals(earlier.get("id"))
                            && "VERIFIED".equals(earlier.get("status"))));
            if (previous != null && dependenciesPreserved && sameDefinition(previous, definition)
                    && previous.get("evidence") instanceof Map<?, ?> proof
                    && GoalContract.requirementsHash(state).equals(proof.get("requirements_hash"))) {
                task.put("status", "VERIFIED");
                task.put("evidence", previous.get("evidence"));
                task.put("verified_at", previous.get("verified_at"));
                task.put("attempts", previous.get("attempts"));
            }
            tasks.add(task);
        }
        if (raw instanceof Map<?, ?>) {
            List<Object> history = new ArrayList<>(Engine.asList(state.get("task_ledger_history")));
            history.add(raw);
            state.put("task_ledger_history", new ArrayList<>(history.subList(Math.max(0, history.size() - 5), history.size())));
        }
        state.put("task_ledger", new LinkedHashMap<>(Map.of("plan_hash", plan.hash(),
                "requirements_hash", GoalContract.requirementsHash(state), "tasks", tasks)));
        return tasks;
    }

    private static boolean sameDefinition(Map<String, Object> prior, Map<String, Object> current) {
        for (String key : List.of("id", "description", "intent", "depends_on", "criteria", "checks")) {
            if (!java.util.Objects.equals(prior.get(key), current.get(key))) return false;
        }
        return true;
    }

    /** Reserve one bounded plan revision before asking the worker for a new strategy. */
    static boolean requestReplan(Map<String, Object> state, Map<String, Object> failed, int cap) {
        int used = (int) Engine.asLongOrDefault(state.get("plan_replans_used"), 0);
        if (used >= cap) return false;
        state.put("plan_replans_used", used + 1);
        Map<?, ?> ledger = (Map<?, ?>) state.get("task_ledger");
        state.put("replan_request", Map.of("failed_task", failed.get("id"),
                "previous_plan_hash", ledger.get("plan_hash"), "attempt", used + 1,
                "reason", Engine.asString(failed.getOrDefault("verification_failure", "Task retry budget exhausted")),
                "requested_at", Controller.now()));
        return true;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> tasks(Map<String, Object> state) {
        if (!(state.get("task_ledger") instanceof Map<?, ?> ledger)) return List.of();
        return (List<Map<String, Object>>) ledger.get("tasks");
    }

    static Map<String, Object> next(Map<String, Object> state) {
        var verified = new java.util.HashSet<String>();
        for (Map<String, Object> task : tasks(state)) {
            if ("VERIFIED".equals(task.get("status"))) verified.add(Engine.asString(task.get("id")));
        }
        for (Map<String, Object> task : tasks(state)) {
            if (!"VERIFIED".equals(task.get("status")) && Engine.asList(task.get("depends_on"))
                    .stream().allMatch(dep -> verified.contains(String.valueOf(dep)))) return task;
        }
        return null;
    }

    static boolean complete(Map<String, Object> state) {
        return !tasks(state).isEmpty() && tasks(state).stream().allMatch(t -> "VERIFIED".equals(t.get("status")));
    }

    /** A later edit or policy amendment makes old task proof stale, even across a restart. */
    static boolean invalidateStale(Map<String, Object> state, String source, String policyHash) {
        boolean changed = false;
        String goal = GoalContract.requirementsHash(state);
        for (Map<String, Object> task : tasks(state)) {
            if (!"VERIFIED".equals(task.get("status"))) continue;
            Object raw = task.get("evidence");
            if (!(raw instanceof Map<?, ?> proof) || !source.equals(proof.get("snapshot"))
                    || !goal.equals(proof.get("requirements_hash"))
                    || !policyHash.equals(proof.get("global_policy_hash"))) {
                task.put("status", "STALE");
                task.put("stale_at", Controller.now());
                changed = true;
            }
        }
        return changed;
    }

    static void start(Map<String, Object> task, int cap) {
        long attempts = Engine.asLongOrDefault(task.get("attempts"), 0);
        if (attempts >= cap) throw new ExecutionBudget.Exhausted("Task " + task.get("id") + " exhausted its retry budget");
        task.put("attempts", attempts + 1);
        task.put("status", "RUNNING");
        task.put("started_at", Controller.now());
    }

    static void result(Map<String, Object> task, boolean success) {
        task.put("status", success ? "AWAITING_VERIFICATION" : "BLOCKED");
        task.put("updated_at", Controller.now());
    }

    static void verified(Map<String, Object> task, Map<String, Object> proof) {
        task.put("status", "VERIFIED");
        task.put("evidence", new LinkedHashMap<>(proof));
        task.put("verified_at", Controller.now());
        task.remove("failure_signature");
        task.remove("failure_repeats");
        task.remove("stalled_at");
    }

    /** Stop a task when the same unmet-criteria evidence recurs after a repair attempt. */
    static boolean observeFailure(Map<String, Object> task, String signature) {
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("Task failure signature is required");
        String previous = Engine.asString(task.get("failure_signature"));
        int repeats = signature.equals(previous)
                ? (int) Engine.asLongOrDefault(task.get("failure_repeats"), 0) + 1 : 0;
        task.put("failure_signature", signature);
        task.put("failure_repeats", repeats);
        return repeats >= 1;
    }
}
