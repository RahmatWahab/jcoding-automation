package com.rahmatwahab.codexautomation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the durable per-story plan/ledger artifacts (Requirement 9).
 *
 * <p>The worker commits a {@code plan.md} (ordered task list) and a
 * {@code ledger.md} (per-task status table) on the story branch. This reader
 * inspects only the ledger's status column to answer two controller questions
 * without ever parsing task bodies or scheduling tasks (the controller stays
 * free of task semantics):
 * <ul>
 *   <li>{@link #hasValidPlan}: is there a committed, well-formed plan to resume
 *       from (so PLANNING is skipped on resume)?</li>
 *   <li>{@link #read}: how many tasks are done vs total (a cheap progress read).</li>
 * </ul>
 *
 * <p><b>Fail closed:</b> a missing or malformed plan/ledger yields
 * {@code valid=false}; the caller routes an ambiguous plan to reconciliation
 * rather than silently re-planning (which could loop).</p>
 */
public final class PlanLedgerReader {

    private PlanLedgerReader() {
    }

    /** Immutable ledger view. */
    public record PlanLedger(int totalTasks, int doneTasks, boolean present, boolean valid) {
        static PlanLedger absent() {
            return new PlanLedger(0, 0, false, false);
        }
    }

    // A ledger task row: | # | step | status | commit | notes |, status is the
    // 3rd cell. We accept the four legal statuses case-insensitively.
    private static final Pattern STATUS_ROW = Pattern.compile(
            "^\\s*\\|\\s*\\d+\\s*\\|[^|]*\\|\\s*(pending|in-progress|done|blocked)\\s*\\|",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** Read the ledger under the given story-branch working-tree root. */
    public static PlanLedger read(Path repoRoot) {
        Path plan = repoRoot.resolve("plan.md");
        Path ledger = repoRoot.resolve("ledger.md");
        if (!Files.exists(plan) || !Files.exists(ledger)) {
            return PlanLedger.absent();
        }
        String ledgerText;
        try {
            ledgerText = Files.readString(ledger);
        } catch (IOException | RuntimeException exc) {
            return PlanLedger.absent(); // unreadable => fail closed
        }
        int total = 0;
        int done = 0;
        Matcher m = STATUS_ROW.matcher(ledgerText);
        while (m.find()) {
            total++;
            if ("done".equalsIgnoreCase(m.group(1))) {
                done++;
            }
        }
        // A valid ledger must have at least one recognizable task row; otherwise
        // it is malformed/empty and we fail closed.
        boolean valid = total > 0;
        return new PlanLedger(total, done, true, valid);
    }

    /**
     * Whether a valid committed plan exists to resume from. Fail-closed: absent
     * or malformed => false (the caller then either plans afresh, or, when a
     * plan is present-but-unparseable, routes to reconciliation).
     */
    public static boolean hasValidPlan(Path repoRoot) {
        return read(repoRoot).valid();
    }
}
