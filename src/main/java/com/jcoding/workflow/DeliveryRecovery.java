package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable, story-scoped recovery budget independent of PR feedback retries. */
final class DeliveryRecovery {
    static final String KEY = "delivery_failure";

    private DeliveryRecovery() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> failure(Map<String, Object> state) {
        Object value = state.get(KEY);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    static void record(Map<String, Object> state, RuntimeException error,
                       String operation, String message) {
        Map<String, Object> previous = failure(state);
        Map<String, Object> failure = new LinkedHashMap<>();
        boolean policy = error instanceof GitOperations.ProtectedPathsException;
        failure.put("code", policy ? "PROTECTED_PATH" : "DELIVERY_OPERATION_FAILED");
        failure.put("operation", operation);
        failure.put("detail", message);
        failure.put("attempts", previous == null ? 0 : previous.getOrDefault("attempts", 0));
        failure.put("stage", "DIAGNOSE");
        failure.put("recorded_at", Controller.now());
        if (error instanceof GitOperations.ProtectedPathsException paths) {
            failure.put("paths", paths.paths());
        }
        state.put(KEY, failure);
        appendHistory(state, failure);
        state.put("phase", policy ? "BLOCKED_REPOSITORY" : "BLOCKED_EXTERNAL");
        state.put("blocker_class", policy ? "PROCESS" : "EXTERNAL");
        state.put("error", message);
        state.put("result_status", "BLOCKED");
        state.put("next_action", "RECOVER_DELIVERY");
        state.put("exit_code", 1);
        state.put("run_finished_at", Controller.now());
    }

    static void recordWorkerFailure(Map<String, Object> state, String phase, int exitCode,
                                    String output, String diagnostics) {
        Map<String, Object> previous = failure(state);
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("code", "WORKER_FAILED");
        failure.put("operation", phase);
        failure.put("worker_phase", phase);
        failure.put("exit_code", exitCode);
        failure.put("detail", "Worker did not produce an accepted result for " + phase);
        failure.put("output", output == null ? "" : output);
        failure.put("diagnostics", diagnostics == null ? "" : diagnostics);
        failure.put("attempts", previous == null ? 0 : previous.getOrDefault("attempts", 0));
        failure.put("stage", "DIAGNOSE");
        failure.put("recorded_at", Controller.now());
        state.put(KEY, failure);
        appendHistory(state, failure);
        state.put("phase", "FAILED_RECOVERABLE");
        state.put("blocker_class", "PROCESS");
        state.put("error", failure.get("detail"));
        state.put("next_action", "RECOVER_WORKER");
        state.put("exit_code", exitCode);
        state.put("run_finished_at", Controller.now());
    }

    private static void appendHistory(Map<String, Object> state, Map<String, Object> failure) {
        Object existing = state.get("failure_history");
        java.util.List<Object> history = existing instanceof java.util.List<?> list
                ? new java.util.ArrayList<>(list) : new java.util.ArrayList<>();
        history.add(new LinkedHashMap<>(failure));
        if (history.size() > 12) history = new java.util.ArrayList<>(history.subList(history.size() - 12, history.size()));
        state.put("failure_history", history);
    }

    /** Null means held; otherwise returns the one permitted worker prompt. */
    static String nextPrompt(Map<String, Object> failure, String evidence, int maxAttempts) {
        String stage = Engine.asString(failure.get("stage"));
        if ("HELD".equals(stage) && evidence.equals(failure.get("evidence"))) {
            return null;
        }
        if ("HELD".equals(stage)) {
            failure.put("attempts", 0);
            failure.put("stage", "DIAGNOSE");
        }
        int attempts = (int) Engine.asLongOrDefault(failure.get("attempts"), 0);
        if (attempts >= Math.max(1, maxAttempts)) {
            failure.put("stage", "HELD");
            failure.put("evidence", evidence);
            return null;
        }
        failure.put("attempts", attempts + 1);
        failure.put("evidence", evidence);
        if (!"REPAIR".equals(failure.get("stage"))) return "prompts/reconcile.md";
        if ("WORKER_FAILED".equals(failure.get("code"))) {
            String decidedPrompt = Engine.asString(failure.get("repair_prompt"));
            if (!decidedPrompt.isEmpty()) return decidedPrompt;
            String phase = Engine.asString(failure.get("worker_phase"));
            String prompt = switch (phase) {
                case "FIXING_FEEDBACK" -> "prompts/feedback.md";
                case "PLANNING" -> "prompts/plan.md";
                default -> "prompts/implement.md";
            };
            return prompt;
        }
        return "prompts/delivery-repair.md";
    }

    static void applyDecision(Map<String, Object> state, boolean accepted) {
        applyDecision(state, accepted, "");
    }

    static void applyDecision(Map<String, Object> state, boolean accepted, String evidence) {
        Map<String, Object> failure = failure(state);
        if (failure == null) return;
        String action = Engine.asString(state.get("decision_action"));
        if (accepted && (action.startsWith("RESUME") || "PROCESS_FEEDBACK".equals(action))) {
            failure.put("stage", "REPAIR");
            if ("WORKER_FAILED".equals(failure.get("code"))) {
                failure.put("repair_prompt", switch (action) {
                    case "RESUME_VERIFICATION" -> "prompts/verify.md";
                    case "RESUME_REVIEW" -> "prompts/review.md";
                    case "PROCESS_FEEDBACK" -> "prompts/feedback.md";
                    case "RESUME_CURRENT_STORY", "RESUME_IMPLEMENTATION" -> "prompts/implement.md";
                    default -> "prompts/implement.md";
                });
            }
        } else if (accepted) {
            failure.put("stage", "HELD");
            if ("WORKER_FAILED".equals(failure.get("code"))) {
                state.put("worker_failure", new LinkedHashMap<>(failure));
                state.remove(KEY);
                if (action.equals("BLOCK_REQUIREMENTS") || action.equals("BLOCK_EXTERNAL")) {
                    state.put("held_failure", Map.of("head", Engine.asString(state.get("current_pr_head")),
                            "evidence", evidence == null ? "" : evidence, "reason", failure.get("detail"),
                            "blocker_class", Engine.asString(state.get("blocker_class"))));
                }
            }
        } else {
            failure.put("stage", "DIAGNOSE");
        }
    }
}
