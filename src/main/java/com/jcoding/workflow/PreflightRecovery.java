package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Retry budget for diagnoses that must run before an implementation worktree is safe. */
final class PreflightRecovery {
    private PreflightRecovery() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> observe(Map<String, Object> state, String evidence, String detail) {
        Object previous = state.get("preflight_failure");
        if (previous instanceof Map<?, ?> record && evidence.equals(record.get("evidence"))) {
            return (Map<String, Object>) record;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("evidence", evidence);
        record.put("detail", detail);
        record.put("attempts", 0);
        record.put("stage", "DIAGNOSE");
        record.put("recorded_at", Controller.now());
        state.put("preflight_failure", record);
        return record;
    }

    static boolean reserve(Map<String, Object> failure, int cap) {
        int attempts = (int) Engine.asLongOrDefault(failure.get("attempts"), 0);
        if (attempts >= Math.max(1, cap)) {
            failure.put("stage", "HELD");
            return false;
        }
        failure.put("attempts", attempts + 1);
        failure.put("stage", "DIAGNOSING");
        return true;
    }
}
