package com.rahmatwahab.codexautomation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable, per-review-finding progress tracking.
 *
 * <p>A PR may contain several independent findings. Keeping their progress in
 * the workflow state prevents one slow or timed-out finding from consuming the
 * retry budget for every other finding.</p>
 */
final class ReviewFindingQueue {

    static final String STATE_KEY = "review_findings";
    static final String ACTIVE_KEY = "active_review_finding";
    static final String ACTIVE_BATCH_KEY = "active_review_findings";
    static final int DEFAULT_BATCH_SIZE = 3;

    private ReviewFindingQueue() {
    }

    /** Synchronize the durable queue with the authoritative unresolved feedback. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> synchronize(Map<String, Object> state,
                                                  List<Map<String, Object>> feedback) {
        return synchronize(state, feedback, 1);
    }

    /** Synchronize feedback and select a bounded batch of pending findings. */
    static List<Map<String, Object>> synchronize(Map<String, Object> state,
                                                  List<Map<String, Object>> feedback,
                                                  int batchSize) {
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        Object stored = state.get(STATE_KEY);
        if (stored instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> finding = new LinkedHashMap<>();
                    map.forEach((key, value) -> finding.put(String.valueOf(key), value));
                    String key = key(finding);
                    if (!key.isEmpty()) {
                        existing.put(key, finding);
                    }
                }
            }
        }

        Set<String> currentKeys = new LinkedHashSet<>();
        for (Map<String, Object> item : feedback) {
            String itemKey = key(item);
            if (itemKey.isEmpty()) {
                continue;
            }
            currentKeys.add(itemKey);
            Map<String, Object> finding = existing.computeIfAbsent(itemKey,
                    ignored -> newFinding(item));
            copyAuthoritativeFields(finding, item);
            if ("VERIFIED".equals(finding.get("status"))
                    && !value(finding.get("verified_commit")).equals(value(item.get("commit")))) {
                finding.put("status", "PENDING");
            }
        }

        // A finding disappears from the authoritative unresolved snapshot only
        // after GitHub reports it resolved. Preserve the record for auditability.
        for (Map.Entry<String, Map<String, Object>> entry : existing.entrySet()) {
            Map<String, Object> finding = entry.getValue();
            if (!currentKeys.contains(entry.getKey())
                    && !"BLOCKED".equals(finding.get("status"))) {
                finding.put("status", "VERIFIED");
                finding.put("verified_at", now());
            }
        }

        List<Map<String, Object>> ordered = new ArrayList<>(existing.values());
        state.put(STATE_KEY, ordered);
        List<Map<String, Object>> activeBatch = nextBatch(ordered, batchSize);
        if (activeBatch.isEmpty()) {
            state.remove(ACTIVE_KEY);
            state.remove(ACTIVE_BATCH_KEY);
        } else {
            state.put(ACTIVE_KEY, new LinkedHashMap<>(activeBatch.get(0)));
            state.put(ACTIVE_BATCH_KEY, copies(activeBatch));
        }
        return ordered;
    }

    static Map<String, Object> next(List<Map<String, Object>> findings) {
        for (Map<String, Object> finding : findings) {
            String status = String.valueOf(finding.getOrDefault("status", "PENDING"));
            if ("PENDING".equals(status) || "IN_PROGRESS".equals(status)) {
                return finding;
            }
        }
        return null;
    }

    static List<Map<String, Object>> nextBatch(List<Map<String, Object>> findings, int batchSize) {
        int limit = Math.max(1, batchSize);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            String status = String.valueOf(finding.getOrDefault("status", "PENDING"));
            if ("PENDING".equals(status) || "IN_PROGRESS".equals(status)) {
                result.add(finding);
                if (result.size() == limit) {
                    break;
                }
            }
        }
        return result;
    }

    static List<Map<String, Object>> activeBatch(Map<String, Object> state) {
        Object value = state.get(ACTIVE_BATCH_KEY);
        if (!(value instanceof List<?> list)) {
            Map<String, Object> active = active(state);
            return active == null ? List.of() : List.of(active);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> finding = new LinkedHashMap<>();
                raw.forEach((key, valueItem) -> finding.put(String.valueOf(key), valueItem));
                result.add(finding);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> active(Map<String, Object> state) {
        Object value = state.get(ACTIVE_KEY);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> findings(Map<String, Object> state) {
        Object value = state.get(STATE_KEY);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> finding = new LinkedHashMap<>();
                raw.forEach((key, valueItem) -> finding.put(String.valueOf(key), valueItem));
                result.add(finding);
            }
        }
        return result;
    }

    /** Mark the active finding as dispatched only when a worker is actually launched. */
    static void markActiveDispatched(Map<String, Object> state) {
        for (Map<String, Object> finding : activeBatch(state)) {
            markDispatched(state, finding);
        }
    }

    static String key(Map<String, Object> finding) {
        String thread = value(finding.get("thread_id"));
        if (!thread.isEmpty()) {
            return "thread:" + thread;
        }
        return "event:" + value(finding.get("id"));
    }

    static void markDispatched(Map<String, Object> state, Map<String, Object> finding) {
        String activeKey = key(finding);
        Object stored = state.get(STATE_KEY);
        if (!(stored instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> candidate =
                        (Map<String, Object>) raw;
                if (activeKey.equals(key(candidate))) {
                    int attempts = ((Number) candidate.getOrDefault("attempts", 0)).intValue() + 1;
                    candidate.put("attempts", attempts);
                    candidate.put("status", "IN_PROGRESS");
                    candidate.put("last_started_at", now());
                    state.put(ACTIVE_KEY, new LinkedHashMap<>(candidate));
                    return;
                }
            }
        }
    }

    static void recordWorkerResult(Map<String, Object> state, int exitCode,
                                   String resultStatus, String detail) {
        Set<String> activeKeys = new LinkedHashSet<>();
        for (Map<String, Object> finding : activeBatch(state)) {
            activeKeys.add(key(finding));
        }
        if (activeKeys.isEmpty()) {
            return;
        }
        Object stored = state.get(STATE_KEY);
        if (!(stored instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> rawItem) {
                @SuppressWarnings("unchecked") Map<String, Object> finding =
                        (Map<String, Object>) rawItem;
                if (activeKeys.contains(key(finding))) {
                    finding.put("last_exit_code", exitCode);
                    finding.put("last_result_status", resultStatus);
                    finding.put("last_detail", detail == null ? "" : detail);
                    if ("SUCCESS".equals(resultStatus)) {
                        finding.put("status", "IN_PROGRESS");
                    } else {
                        finding.put("status", "PENDING");
                    }
                    finding.put("last_finished_at", now());
                }
            }
        }
        refreshActiveBatch(state);
    }

    /** Mark only worker-declared findings as complete after delivery verification. */
    static void markVerified(Map<String, Object> state, Set<String> threadIds,
                             String commit, String status) {
        if (threadIds == null || threadIds.isEmpty()) {
            return;
        }
        Object stored = state.get(STATE_KEY);
        if (!(stored instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> finding =
                        (Map<String, Object>) raw;
                if (threadIds.contains(value(finding.get("thread_id")))) {
                    finding.put("status", status);
                    finding.put("verified_commit", commit == null ? "" : commit);
                    finding.put("verified_at", now());
                }
            }
        }
        refreshActiveBatch(state);
    }

    static void refreshActiveBatch(Map<String, Object> state) {
        List<Map<String, Object>> findings = findings(state);
        int batchSize = ((Number) state.getOrDefault("feedback_batch_size", DEFAULT_BATCH_SIZE)).intValue();
        List<Map<String, Object>> batch = nextBatch(findings, batchSize);
        if (batch.isEmpty()) {
            state.remove(ACTIVE_KEY);
            state.remove(ACTIVE_BATCH_KEY);
        } else {
            state.put(ACTIVE_KEY, new LinkedHashMap<>(batch.get(0)));
            state.put(ACTIVE_BATCH_KEY, copies(batch));
        }
    }

    static void markActiveBlocked(Map<String, Object> state, String detail) {
        Map<String, Object> active = active(state);
        if (active == null) {
            return;
        }
        String activeKey = key(active);
        Object stored = state.get(STATE_KEY);
        if (!(stored instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> finding =
                        (Map<String, Object>) raw;
                if (activeKey.equals(key(finding))) {
                    finding.put("status", "BLOCKED");
                    finding.put("last_detail", detail == null ? "" : detail);
                    finding.put("last_finished_at", now());
                    state.put(ACTIVE_KEY, new LinkedHashMap<>(finding));
                    return;
                }
            }
        }
    }

    private static Map<String, Object> newFinding(Map<String, Object> item) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("status", "PENDING");
        finding.put("attempts", 0);
        copyAuthoritativeFields(finding, item);
        return finding;
    }

    private static void copyAuthoritativeFields(Map<String, Object> target,
                                                Map<String, Object> source) {
        for (String field : List.of("id", "thread_id", "author", "body", "submittedAt", "commit")) {
            if (source.containsKey(field)) {
                target.put(field, source.get(field));
            }
        }
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static List<Map<String, Object>> copies(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : source) {
            result.add(new LinkedHashMap<>(item));
        }
        return result;
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }
}
