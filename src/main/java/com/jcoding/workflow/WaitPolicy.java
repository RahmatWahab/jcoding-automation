package com.jcoding.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent deadlines for passive waits; repeated polling is not progress. */
final class WaitPolicy {
    enum Decision { WAIT, DIAGNOSE, HOLD }
    private WaitPolicy() { }

    @SuppressWarnings("unchecked")
    static Decision observe(Map<String, Object> state, String phase, Map<String, Object> pr,
                            Map<String, Object> options, Instant now) {
        String signature = GoalContract.hash(phase + ":" + pr.get("headRefOid") + ":"
                + pr.get("statusCheckRollup") + ":" + pr.get("reviews") + ":" + pr.get("mergeStateStatus"));
        Map<String, Object> wait = state.get("wait_observation") instanceof Map<?, ?> old
                ? (Map<String, Object>) old : Map.of();
        if (!signature.equals(wait.get("signature"))) {
            wait = new LinkedHashMap<>();
            wait.put("signature", signature);
            wait.put("started_at", now.toString());
            wait.put("attempts", 0);
            state.put("wait_observation", wait);
        }
        long timeout = Engine.asLongOrDefault(options.get("wait_timeout_seconds"), 3600);
        Instant since;
        try { since = Instant.parse(wait.get("started_at").toString()); }
        catch (RuntimeException exc) { since = Instant.EPOCH; }
        if (now.isBefore(since.plusSeconds(Math.max(1, timeout)))) return Decision.WAIT;
        int attempts = Engine.asInt(wait.get("attempts"));
        int cap = (int) Engine.asLongOrDefault(options.get("max_wait_diagnoses"), 2);
        if (attempts >= cap) return Decision.HOLD;
        wait.put("attempts", attempts + 1);
        wait.put("started_at", now.toString());
        return Decision.DIAGNOSE;
    }
}
