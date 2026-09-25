package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Business-value tests for the pre-commit specialist review gate. They protect
 * the properties the feature must guarantee: relevant specialists run
 * sequentially, a blocking finding drives a bounded fix loop, the loop is capped
 * (never dead-ends), and diff-gating selects only relevant specialists.
 */
class InternalReviewGateTest {

    private static final String PASS = "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: clean\n";
    private static final String FAIL = "RESULT_STATUS: BLOCKED\nRESULT_BLOCKER: issue\n";

    private static InternalReviewGate.Specialist always(String name) {
        return new InternalReviewGate.Specialist(name, name + ".md", name + "-fix.md", List.of());
    }

    private static InternalReviewGate.Specialist gated(String name, String trigger) {
        return new InternalReviewGate.Specialist(name, name + ".md", name + "-fix.md", List.of(trigger));
    }

    @Test
    void allSpecialistsPassOnCleanDiff() {
        InternalReviewGate gate = new InternalReviewGate(
                List.of(always("general"), always("test")), 2);
        List<String> calls = new ArrayList<>();
        InternalReviewGate.Outcome outcome = gate.run(List.of("Foo.java"), prompt -> {
            calls.add(prompt);
            return PASS;
        });
        assertThat(outcome).isEqualTo(InternalReviewGate.Outcome.PASSED);
        // Each always-on specialist reviewed exactly once (no fix needed).
        assertThat(calls).containsExactly("general.md", "test.md");
    }

    @Test
    void blockingFindingDrivesBoundedFixThenPasses() {
        InternalReviewGate gate = new InternalReviewGate(List.of(always("security")), 2);
        AtomicInteger reviews = new AtomicInteger();
        List<String> calls = new ArrayList<>();
        InternalReviewGate.Outcome outcome = gate.run(List.of("Auth.java"), prompt -> {
            calls.add(prompt);
            if (prompt.endsWith("-fix.md")) {
                return PASS; // fix "runs"
            }
            // First review fails, second (after fix) passes.
            return reviews.getAndIncrement() == 0 ? FAIL : PASS;
        });
        assertThat(outcome).isEqualTo(InternalReviewGate.Outcome.PASSED);
        assertThat(calls).containsExactly("security.md", "security-fix.md", "security.md");
    }

    @Test
    void fixLoopIsCappedAndDeliversWithUnresolvedInsteadOfLoopingForever() {
        InternalReviewGate gate = new InternalReviewGate(List.of(always("security")), 2);
        List<String> calls = new ArrayList<>();
        // Reviewer NEVER passes — the pathological case that must not loop forever.
        InternalReviewGate.Outcome outcome = gate.run(List.of("Auth.java"), prompt -> {
            calls.add(prompt);
            return prompt.endsWith("-fix.md") ? PASS : FAIL;
        });
        assertThat(outcome).isEqualTo(InternalReviewGate.Outcome.DELIVER_WITH_UNRESOLVED);
        // review, (fix, review) x2 = 1 + 2*2 = 5 calls, then stop. Never unbounded.
        long reviewCalls = calls.stream().filter(c -> c.equals("security.md")).count();
        long fixCalls = calls.stream().filter(c -> c.equals("security-fix.md")).count();
        assertThat(reviewCalls).isEqualTo(3); // initial + 2 re-reviews
        assertThat(fixCalls).isEqualTo(2);    // capped at maxFixAttempts
    }

    @Test
    void diffGatingRunsOnlyRelevantSpecialists() {
        InternalReviewGate gate = new InternalReviewGate(
                List.of(always("general"), gated("security", "auth"),
                        gated("database", ".sql")), 2);
        List<String> calls = new ArrayList<>();
        // Diff touches only a .sql file: general (always) + database (.sql) run;
        // security (auth) is skipped.
        gate.run(List.of("db/migration/V1__init.sql"), prompt -> {
            calls.add(prompt);
            return PASS;
        });
        assertThat(calls).containsExactly("general.md", "database.md");
        assertThat(calls).doesNotContain("security.md");
    }

    @Test
    void noRelevantSpecialistsStillPasses() {
        InternalReviewGate gate = new InternalReviewGate(
                List.of(gated("security", "auth")), 2);
        List<String> calls = new ArrayList<>();
        InternalReviewGate.Outcome outcome = gate.run(List.of("README.md"), prompt -> {
            calls.add(prompt);
            return PASS;
        });
        assertThat(outcome).isEqualTo(InternalReviewGate.Outcome.PASSED);
        assertThat(calls).isEmpty();
    }
}
