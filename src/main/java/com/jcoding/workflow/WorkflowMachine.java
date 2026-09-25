package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one-page workflow state machine (Requirement 1).
 *
 * <p>A single declarative table is the authoritative source for:
 * <ul>
 *   <li><b>phase → prompt</b>: which worker prompt a phase dispatches
 *       (delegated to by {@link Controller#phasePromptFor}); and</li>
 *   <li><b>phase → legal next phases</b>: the transitions the workflow is
 *       designed to allow, with an explicit detectable error for an undeclared
 *       transition ({@link #requireLegalTransition}).</li>
 * </ul>
 * This replaces the phase/prompt/transition knowledge that was previously
 * reverse-engineerable only from the 1,156-line orchestrator method.</p>
 *
 * <p><b>Behavior-preservation note:</b> the legacy dispatch path did not enforce
 * a transition graph, and the codebase sets a few phases (e.g.
 * {@code BLOCKED_CONFIGURATION}, {@code BLOCKED_REPOSITORY}) that are not in
 * {@link PhaseModel#PHASES}. This table therefore (a) is the authoritative
 * prompt map wired into the live path, and (b) exposes transition legality as an
 * opt-in guard for the new phase-handler boundaries (Requirement 2) — it does
 * not retroactively reject phases the existing code already produces.</p>
 */
public final class WorkflowMachine {

    private WorkflowMachine() {
    }

    /** Raised when an undeclared phase transition is attempted (detectable, not silent). */
    public static final class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(String from, String to) {
            super("Illegal workflow transition: " + from + " -> " + to);
        }
    }

    /** Every phase the controller can occupy, including the non-PhaseModel operational phases. */
    public static final Set<String> KNOWN_PHASES = Set.of(
            "IDLE", "PLANNING", "IMPLEMENTING", "VERIFYING", "REVIEWING_CODE", "WAITING_FOR_CI",
            "WAITING_FOR_CODEX_REVIEW", "WAITING_FOR_HUMAN_REVIEW", "CHANGES_REQUESTED",
            "FIXING_FEEDBACK", "READY_TO_MERGE", "WAITING_FOR_MANUAL_MERGE", "PAUSED_QUOTA",
            "BLOCKED_REQUIREMENTS", "BLOCKED_EXTERNAL", "BLOCKED_REPOSITORY",
            "BLOCKED_CONFIGURATION", "CONFLICT_DETECTED", "RECONCILING", "EPIC_COMPLETE",
            "FAILED_RECOVERABLE", "CODEX_FAILURE", "MERGED");

    /**
     * Authoritative phase → prompt-file map. Mirrors the historical
     * {@code Controller.phasePromptFor} switch exactly so the live dispatch is
     * unchanged; the default (unknown/implementation phases) is
     * {@code prompts/implement.md}.
     */
    private static final Map<String, String> PROMPT_BY_PHASE = buildPromptMap();

    private static Map<String, String> buildPromptMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PLANNING", "prompts/plan.md");
        m.put("CHANGES_REQUESTED", "prompts/feedback.md");
        m.put("FIXING_FEEDBACK", "prompts/feedback.md");
        m.put("VERIFYING", "prompts/verify.md");
        m.put("REVIEWING_CODE", "prompts/review.md");
        m.put("BLOCKED_REQUIREMENTS", "prompts/reconcile.md");
        m.put("BLOCKED_REPOSITORY", "prompts/reconcile.md");
        m.put("FAILED_RECOVERABLE", "prompts/reconcile.md");
        m.put("CODEX_FAILURE", "prompts/reconcile.md");
        m.put("READY_TO_MERGE", "prompts/final-review.md");
        m.put("IDLE", "prompts/discover.md");
        return m;
    }

    /** The prompt file a phase dispatches (default {@code prompts/implement.md}). */
    public static String promptFor(String phase) {
        return PROMPT_BY_PHASE.getOrDefault(phase == null ? "" : phase, "prompts/implement.md");
    }

    /**
     * Declared legal next phases for a phase. A phase absent from this map has no
     * declared successors (used only by the opt-in transition guard, not the
     * legacy path). Grounded in the transitions the current engine produces:
     * an active worker phase can move to any waiting/blocked/terminal phase or
     * reconciliation; reconciliation can resume any active phase; terminal
     * phases can advance to IDLE/EPIC_COMPLETE or be re-diagnosed.
     */
    private static final Map<String, Set<String>> LEGAL_NEXT = buildLegalNext();

    private static Map<String, Set<String>> buildLegalNext() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        Set<String> anyActive = Set.of(
                "IMPLEMENTING", "VERIFYING", "REVIEWING_CODE", "FIXING_FEEDBACK");
        Set<String> waitingOrTerminal = Set.of(
                "WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW", "WAITING_FOR_HUMAN_REVIEW",
                "WAITING_FOR_MANUAL_MERGE", "READY_TO_MERGE", "CHANGES_REQUESTED",
                "PAUSED_QUOTA", "BLOCKED_REQUIREMENTS", "BLOCKED_EXTERNAL",
                "BLOCKED_REPOSITORY", "BLOCKED_CONFIGURATION", "CONFLICT_DETECTED",
                "FAILED_RECOVERABLE", "CODEX_FAILURE", "RECONCILING", "MERGED");
        for (String active : anyActive) {
            m.put(active, union(anyActive, waitingOrTerminal));
        }
        // Reconciliation and blocked/recoverable phases resume any active phase
        // or move to a waiting/terminal phase.
        for (String recover : Set.of("RECONCILING", "BLOCKED_REQUIREMENTS",
                "BLOCKED_REPOSITORY", "BLOCKED_EXTERNAL", "BLOCKED_CONFIGURATION",
                "FAILED_RECOVERABLE", "CODEX_FAILURE",
                "CONFLICT_DETECTED")) {
            m.put(recover, union(anyActive, waitingOrTerminal, Set.of("IDLE", "EPIC_COMPLETE")));
        }
        // Idle selects a story and begins implementation (or planning), or completes the EPIC.
        m.put("IDLE", union(anyActive, Set.of("PLANNING", "EPIC_COMPLETE", "IDLE", "BLOCKED_REQUIREMENTS")));
        // Planning produces the plan then proceeds to implementation (or reconcile on ambiguity).
        m.put("PLANNING", union(anyActive, Set.of("RECONCILING", "BLOCKED_REQUIREMENTS")));
        // Waiting phases resolve forward to review/feedback/merge or a block.
        for (String wait : Set.of("WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW",
                "WAITING_FOR_HUMAN_REVIEW", "WAITING_FOR_MANUAL_MERGE", "READY_TO_MERGE",
                "CHANGES_REQUESTED", "PAUSED_QUOTA")) {
            m.put(wait, union(anyActive, waitingOrTerminal, Set.of("EPIC_COMPLETE", "IDLE")));
        }
        // Merged advances to the next story (IDLE) or completes the EPIC.
        m.put("MERGED", Set.of("IDLE", "EPIC_COMPLETE"));
        m.put("EPIC_COMPLETE", Set.of("EPIC_COMPLETE", "IDLE"));
        return m;
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new java.util.LinkedHashSet<>();
        for (Set<String> s : sets) {
            result.addAll(s);
        }
        return Set.copyOf(result);
    }

    /** Whether {@code to} is a declared legal successor of {@code from}. */
    public static boolean isLegalTransition(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.equals(to)) {
            return true; // re-entering the same phase (retry) is always legal
        }
        Set<String> legal = LEGAL_NEXT.get(from);
        return legal != null && legal.contains(to);
    }

    /**
     * Enforce a legal transition at a handler boundary (Requirement 2). Throws a
     * detectable {@link IllegalTransitionException} rather than transitioning
     * silently. Opt-in: the legacy dispatch path does not call this.
     */
    public static void requireLegalTransition(String from, String to) {
        if (!isLegalTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }
}
