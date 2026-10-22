package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent story budget shared by implementation, recovery and every specialist call. */
final class ExecutionBudget {
    static final String KEY = "story_budgets";

    private ExecutionBudget() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ledger(Map<String, Object> state) {
        Map<String, Object> stories = (Map<String, Object>) state.computeIfAbsent(KEY, k -> new LinkedHashMap<>());
        String story = Engine.asString(state.get("current_story"));
        return (Map<String, Object>) stories.computeIfAbsent(story, k -> new LinkedHashMap<>());
    }

    /** Reserve BEFORE launching. A crash retains the reservation, preventing free retries. */
    static int reserve(Map<String, Object> state, Map<String, Object> options, int requestedSeconds) {
        Map<String, Object> ledger = ledger(state);
        long epoch = Engine.asLongOrDefault(options.get("recovery_epoch"), 0);
        if (epoch > Engine.asLongOrDefault(ledger.get("epoch"), 0)) {
            Map<String, Object> previous = new LinkedHashMap<>(ledger);
            previous.remove("previous_epoch");
            ledger.clear();
            ledger.put("previous_epoch", previous);
            ledger.put("epoch", epoch);
        }
        long calls = Engine.asLongOrDefault(ledger.get("calls"), 0);
        long spent = Engine.asLongOrDefault(ledger.get("elapsed_ms"), 0)
                + Engine.asLongOrDefault(ledger.remove("reserved_ms"), 0)
                + Engine.asLongOrDefault(ledger.remove("reserved_tool_ms"), 0);
        ledger.put("elapsed_ms", spent);
        long maxMillis = Engine.asLongOrDefault(options.get("max_story_active_seconds"), 7200) * 1000L;
        long tokenLimit = Engine.asLongOrDefault(options.get("max_story_tokens"), 0);
        long usedTokens = Engine.asLongOrDefault(ledger.get("input_tokens"), 0)
                + Engine.asLongOrDefault(ledger.get("output_tokens"), 0)
                + Engine.asLongOrDefault(ledger.get("unverified_token_reservation"), 0);
        long remainingSeconds = (maxMillis - spent) / 1000;
        if (calls >= Engine.asLongOrDefault(options.get("max_story_worker_calls"), 24)
                || remainingSeconds < 1 || requestedSeconds < 1
                || (tokenLimit > 0 && usedTokens >= tokenLimit)) {
            throw new Exhausted("Story execution budget exhausted: calls=" + calls
                    + ", active_ms=" + spent + ", reported_tokens=" + usedTokens);
        }
        int seconds = (int) Math.min(requestedSeconds, Math.min(Integer.MAX_VALUE, remainingSeconds));
        ledger.put("calls", calls + 1);
        ledger.put("reserved_ms", seconds * 1000L);
        if (tokenLimit > 0) ledger.put("unverified_token_reservation", Math.max(0, tokenLimit - usedTokens));
        return seconds;
    }

    static void finish(Map<String, Object> state, long durationMillis, Map<String, Long> usage) {
        Map<String, Object> ledger = ledger(state);
        ledger.remove("reserved_ms");
        ledger.put("elapsed_ms", Engine.asLongOrDefault(ledger.get("elapsed_ms"), 0) + Math.max(0, durationMillis));
        if (usage == null || usage.isEmpty()) {
            ledger.put("calls_without_usage", Engine.asLongOrDefault(ledger.get("calls_without_usage"), 0) + 1);
        } else {
            ledger.remove("unverified_token_reservation");
            usage.forEach((key, value) -> ledger.put(key, Engine.asLongOrDefault(ledger.get(key), 0) + value));
        }
    }

    static int reserveTool(Map<String, Object> state, Map<String, Object> options, int seconds) {
        Map<String, Object> ledger = ledger(state);
        long spent = Engine.asLongOrDefault(ledger.get("elapsed_ms"), 0)
                + Engine.asLongOrDefault(ledger.remove("reserved_tool_ms"), 0);
        ledger.put("elapsed_ms", spent);
        long remaining = (Engine.asLongOrDefault(options.get("max_story_active_seconds"), 7200) * 1000L - spent) / 1000;
        if (remaining < 1 || seconds < 1) throw new Exhausted("Story verification time budget exhausted");
        int reserved = (int) Math.min(seconds, remaining);
        ledger.put("reserved_tool_ms", reserved * 1000L);
        return reserved;
    }

    static void finishTool(Map<String, Object> state, long milliseconds) {
        Map<String, Object> ledger = ledger(state);
        ledger.remove("reserved_tool_ms");
        ledger.put("elapsed_ms", Engine.asLongOrDefault(ledger.get("elapsed_ms"), 0) + Math.max(0, milliseconds));
    }

    static final class Exhausted extends IllegalStateException {
        Exhausted(String reason) { super(reason); }
    }
}
