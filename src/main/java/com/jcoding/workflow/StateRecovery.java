package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable-state recovery and reconciliation-decision application.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code repair_legacy_quota_state}, {@code clear_stale_story_selection_state},
 * {@code apply_reconciliation_decision}). All methods mutate the supplied state
 * map in place and fail closed, exactly as the reference implementation.</p>
 */
public final class StateRecovery {

    /** Outcome of applying a reconciliation decision: accepted flag plus detail. */
    public record ReconciliationOutcome(boolean accepted, String detail) {
    }

    private static final List<String> RESULT_KEYS =
            List.of("RESULT_STATUS", "RESULT_NEXT_PHASE", "RESULT_BLOCKER", "RESULT_EVIDENCE");

    private static final Map<String, String> ACTION_TO_TARGET = Map.ofEntries(
            Map.entry("RESUME_CURRENT_STORY", "IMPLEMENTING"),
            Map.entry("RESUME_IMPLEMENTATION", "IMPLEMENTING"),
            Map.entry("RESUME_VERIFICATION", "VERIFYING"),
            Map.entry("RESUME_REVIEW", "REVIEWING_CODE"),
            Map.entry("PROCESS_FEEDBACK", "FIXING_FEEDBACK"),
            Map.entry("WAIT_FOR_CI", "WAITING_FOR_CI"),
            Map.entry("WAIT_FOR_HUMAN_MERGE", "WAITING_FOR_MANUAL_MERGE"),
            Map.entry("PAUSE_QUOTA", "PAUSED_QUOTA"),
            Map.entry("BLOCK_REQUIREMENTS", "BLOCKED_REQUIREMENTS"),
            Map.entry("BLOCK_EXTERNAL", "BLOCKED_EXTERNAL"));

    private StateRecovery() {
    }

    private static String lastMatch(String key, String output) {
        Matcher matcher = Pattern.compile("^" + key + ":[\\t ]*([^\\r\\n]*)$", Pattern.MULTILINE)
                .matcher(output == null ? "" : output);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1).strip();
        }
        return last;
    }

    /** Parse raw worker fields. This is not an acceptance or delivery verdict. */
    public static Map<String, String> parseResult(String output) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String key : RESULT_KEYS) {
            String match = lastMatch(key, output);
            if (match != null) {
                values.put(key.toLowerCase(), match);
            }
        }
        return values;
    }

    /** Repair the old false-positive quota state caused by signal exits. */
    public static boolean repairLegacyQuotaState(Map<String, Object> state) {
        boolean signalExit;
        try {
            Object exitCode = state.getOrDefault("exit_code", 0);
            signalExit = Long.parseLong(String.valueOf(exitCode)) < 0;
        } catch (NumberFormatException exc) {
            signalExit = false;
        }
        if (!"PAUSED_QUOTA".equals(state.get("phase")) || !signalExit) {
            return false;
        }
        state.put("phase", "FAILED_RECOVERABLE");
        state.put("error", "previous worker was terminated by signal; quota pause was invalid");
        state.put("recovery_action", "reconcile the preserved worktree before retrying");
        state.remove("retry_at");
        state.put("quota_state_repaired", true);
        return true;
    }

    /** Resume an active story after a stale no-eligible-stories decision. */
    public static boolean clearStaleStorySelectionState(Map<String, Object> state) {
        if (!"BLOCKED_REQUIREMENTS".equals(state.get("phase"))) {
            return false;
        }
        StringBuilder joined = new StringBuilder();
        for (String key : List.of("message", "error", "decision_reason", "blockers")) {
            Object value = state.get(key);
            joined.append(value == null ? "" : String.valueOf(value)).append(' ');
        }
        String text = joined.toString().toLowerCase();
        boolean stale = text.contains("no eligible incomplete stories remain")
                || text.contains("controller eligibility");
        if (!stale) {
            return false;
        }
        Object previousBlockers = state.getOrDefault("blockers", new ArrayList<>());
        state.put("phase", "IMPLEMENTING");
        state.put("blocker_class", "NONE");
        state.put("blockers", new ArrayList<>());
        state.put("error", null);
        state.put("message", null);
        state.put("decision_action", null);
        state.put("decision_reason", null);
        state.put("decision_rejection", null);
        state.put("recovery_action", null);
        state.put("result_status", null);
        state.put("result_evidence", null);
        state.put("last_resolved_blockers", previousBlockers);
        return true;
    }

    /**
     * Apply a bounded reconciliation decision to state. Returns whether the
     * decision was accepted plus a human-readable detail. Fails closed on
     * invalid/missing action, missing evidence, invalid blocker class, or a
     * resume without a current story and branch. This overload assumes the
     * caller has checked exit zero; prefer the exit-aware overload for new code.
     */
    public static ReconciliationOutcome applyReconciliationDecision(Map<String, Object> state, String output) {
        return applyReconciliationDecision(state, 0, output);
    }

    /**
     * Exit-aware decision gate. Rejections leave state untouched, including
     * quota/backoff classification already applied by {@link Engine}.
     */
    @SuppressWarnings("unchecked")
    public static ReconciliationOutcome applyReconciliationDecision(Map<String, Object> state,
                                                                     int exitCode, String output) {
        Map<String, String> result = parseResult(output);
        if (exitCode != 0 || !"SUCCESS".equals(result.get("result_status"))) {
            return new ReconciliationOutcome(false,
                    "Codex returned no valid SUCCESS reconciliation result");
        }
        if (!"COMPLETE".equals(result.get("result_evidence"))) {
            return new ReconciliationOutcome(false,
                    "Codex returned a reconciliation decision without complete evidence");
        }
        Map<String, String> decision = Engine.parseDecision(output);
        String action = decision.getOrDefault("decision_action", "");
        String reason = decision.getOrDefault("decision_reason", "");
        String blockerClass = decision.getOrDefault("blocker_class", "");

        if (!PhaseModel.DECISION_ACTIONS.contains(action)) {
            return new ReconciliationOutcome(false, "Codex returned an invalid or missing reconciliation action");
        }
        if (reason.isBlank()) {
            return new ReconciliationOutcome(false, "Codex returned a reconciliation action without evidence");
        }
        if (!PhaseModel.BLOCKER_CLASSES.contains(blockerClass)) {
            return new ReconciliationOutcome(false, "Codex returned an invalid blocker class: " + blockerClass);
        }
        String recoveryAction = decision.getOrDefault("recovery_action", "");
        if (recoveryAction.isBlank()) {
            return new ReconciliationOutcome(false, "Codex returned no reconciliation recovery action");
        }

        String target = ACTION_TO_TARGET.get(action);
        if (action.startsWith("RESUME") || action.equals("PROCESS_FEEDBACK")) {
            if (isBlank(state.get("current_story")) || isBlank(state.get("branch"))) {
                return new ReconciliationOutcome(false,
                        "reconciliation cannot resume without a current story and branch");
            }
        }

        int attempts = intValue(state.getOrDefault("reconciliation_attempts", 0));
        state.put("worker_result_status", result.get("result_status"));
        state.put("result_status", "RECONCILIATION_ACCEPTED");
        state.put("result_evidence", "DECISION_COMPLETE");
        state.remove("delivery_mode");
        if (action.equals("BLOCK_REQUIREMENTS") || action.equals("BLOCK_EXTERNAL")) {
            attempts += 1;
            if (ProgressGuard.reconcileCapReached(attempts)) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("reconciliation_attempts", attempts);
                state.put("error", "reconciliation selected blocking action three consecutive times");
                return new ReconciliationOutcome(true, reason);
            }
        } else {
            attempts = 0;
        }

        state.put("phase", target);
        state.put("decision_action", action);
        state.put("decision_reason", reason);
        state.put("reconciliation_attempts", attempts);
        state.put("blocker_class", blockerClass);
        state.put("recovery_action", recoveryAction);

        if (!action.equals("BLOCK_REQUIREMENTS") && !action.equals("BLOCK_EXTERNAL")
                && !action.equals("PAUSE_QUOTA")) {
            Object blockers = state.get("blockers");
            if (blockers instanceof List<?> list && !list.isEmpty()) {
                state.put("last_resolved_blockers", new ArrayList<>((List<Object>) list));
            }
            state.put("blockers", new ArrayList<>());
            state.put("blocker_class", "NONE");
        }
        state.remove("error");
        return new ReconciliationOutcome(true, reason);
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exc) {
            return 0;
        }
    }
}
