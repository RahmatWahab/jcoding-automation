package com.jcoding.workflow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Normalize CheckRun and legacy StatusContext results; unknown is never success. */
public final class CiGate {
    private CiGate() { }
    public enum Verdict { PASSED, WAITING, FAILED }

    public static Verdict evaluate(Object rollup, List<String> required, boolean allowNoChecks) {
        if (!(rollup instanceof List<?> checks) || checks.isEmpty()) {
            return allowNoChecks && required.isEmpty() ? Verdict.PASSED : Verdict.WAITING;
        }
        Set<String> passed = new HashSet<>();
        boolean waiting = false;
        for (Object entry : checks) {
            if (!(entry instanceof Map<?, ?> check)) { waiting = true; continue; }
            String status = value(check, "status");
            String result = value(check, "conclusion");
            if (result.isEmpty()) result = value(check, "state");
            if (Set.of("FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "STALE").contains(result)) {
                return Verdict.FAILED;
            }
            String name = value(check, "name");
            if (name.isEmpty()) name = value(check, "context");
            if ("SUCCESS".equals(result) && (status.isEmpty() || "COMPLETED".equals(status))) {
                passed.add(name);
            } else if (("NEUTRAL".equals(result) || "SKIPPED".equals(result)) && !required.contains(name)) {
                // Optional neutral/skipped checks cannot satisfy a required check.
            } else { waiting = true; }
        }
        return waiting || !passed.containsAll(required) ? Verdict.WAITING : Verdict.PASSED;
    }

    private static String value(Map<?, ?> map, String key) {
        return map.get(key) == null ? "" : map.get(key).toString();
    }
}
