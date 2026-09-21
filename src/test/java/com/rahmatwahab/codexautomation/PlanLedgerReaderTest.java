package com.rahmatwahab.codexautomation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business-value tests for the plan/ledger reader (Requirement 9). They protect
 * the resume-safety properties: a valid committed plan is detected (so PLANNING
 * is skipped on resume), and a missing/malformed plan fails closed (so the
 * controller never silently trusts a broken ledger).
 */
class PlanLedgerReaderTest {

    private static void writePlan(Path root, String ledger) throws Exception {
        Files.writeString(root.resolve("plan.md"), "# Plan\n- [ ] Task 1\n");
        Files.writeString(root.resolve("ledger.md"), ledger);
    }

    @Test
    void detectsValidCommittedPlanAndCountsDone(@TempDir Path root) throws Exception {
        writePlan(root,
                "| # | Step | Status | Commit | Notes |\n"
                + "|---|------|--------|--------|-------|\n"
                + "| 1 | Add API | done | abc123 | shipped |\n"
                + "| 2 | Add tests | in-progress | | |\n"
                + "| 3 | Docs | pending | | |\n");

        PlanLedgerReader.PlanLedger l = PlanLedgerReader.read(root);
        assertThat(l.present()).isTrue();
        assertThat(l.valid()).isTrue();
        assertThat(l.totalTasks()).isEqualTo(3);
        assertThat(l.doneTasks()).isEqualTo(1);
        assertThat(PlanLedgerReader.hasValidPlan(root)).isTrue();
    }

    @Test
    void missingPlanFailsClosed(@TempDir Path root) {
        // No plan.md/ledger.md => not valid => the controller plans afresh, it
        // never assumes a plan exists.
        assertThat(PlanLedgerReader.hasValidPlan(root)).isFalse();
        assertThat(PlanLedgerReader.read(root).present()).isFalse();
    }

    @Test
    void malformedLedgerWithNoTaskRowsFailsClosed(@TempDir Path root) throws Exception {
        // plan/ledger files exist but the ledger has no recognizable status row:
        // present but NOT valid, so the caller routes to reconciliation rather
        // than trusting a broken ledger or silently re-planning.
        writePlan(root, "# Ledger\n\n(nothing structured here)\n");
        PlanLedgerReader.PlanLedger l = PlanLedgerReader.read(root);
        assertThat(l.present()).isTrue();
        assertThat(l.valid()).isFalse();
        assertThat(PlanLedgerReader.hasValidPlan(root)).isFalse();
    }

    @Test
    void ledgerPresentButPlanMissingFailsClosed(@TempDir Path root) throws Exception {
        // Only ledger.md without plan.md is not a valid plan.
        Files.writeString(root.resolve("ledger.md"),
                "| 1 | X | done | c | n |\n");
        assertThat(PlanLedgerReader.hasValidPlan(root)).isFalse();
    }
}
