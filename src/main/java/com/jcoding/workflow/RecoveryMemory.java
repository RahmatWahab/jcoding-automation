package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded observations, not self-editing policy. Reuse requires matching requirements. */
final class RecoveryMemory {
    private RecoveryMemory() { }

    static void observe(Map<String, Object> state, String phase, String action, String evidence, boolean verified) {
        List<Object> history = new ArrayList<>(Engine.asList(state.get("recovery_memory")));
        String requirements = GoalContract.requirementsHash(state);
        String signature = GoalContract.hash(phase + ":" + evidence);
        long repetitions = history.stream().filter(item -> item instanceof Map<?, ?> m
                && signature.equals(m.get("signature")) && action.equals(m.get("action"))
                && requirements.equals(m.get("requirements_hash")) && !Boolean.TRUE.equals(m.get("verified"))).count();
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("story", state.getOrDefault("current_story", ""));
        observation.put("requirements_hash", requirements);
        observation.put("signature", signature);
        observation.put("phase", phase);
        observation.put("action", action);
        observation.put("evidence", evidence.substring(Math.max(0, evidence.length() - 2000)));
        observation.put("verified", verified);
        observation.put("at", Controller.now());
        history.add(observation);
        state.put("recovery_memory", new ArrayList<>(history.subList(Math.max(0, history.size() - 50), history.size())));
        state.put("recovery_strategy", verified ? "REUSE_VERIFIED_EVIDENCE"
                : repetitions == 0 ? "REPAIR_FROM_EVIDENCE"
                : repetitions == 1 ? "TEST_ALTERNATIVE_HYPOTHESIS" : "REPLAN_OR_ESCALATE");
        @SuppressWarnings("unchecked") Map<String, Object> metrics = (Map<String, Object>) state.computeIfAbsent("metrics", k -> new LinkedHashMap<>());
        String metric = verified ? "verified_outcomes" : "recovery_observations";
        metrics.put(metric, Engine.asLongOrDefault(metrics.get(metric), 0) + 1);
    }

    static List<Object> relevant(Map<String, Object> state) {
        String requirements = GoalContract.requirementsHash(state);
        List<Object> matching = Engine.asList(state.get("recovery_memory")).stream()
                .filter(item -> item instanceof Map<?, ?> m && requirements.equals(m.get("requirements_hash"))).toList();
        return matching.subList(Math.max(0, matching.size() - 3), matching.size());
    }
}
