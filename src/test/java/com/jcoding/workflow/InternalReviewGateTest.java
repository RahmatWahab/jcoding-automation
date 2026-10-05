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

    private static class Context implements ReviewContext {
        String snapshot = "tree-0";
        String signature = "prompt-content:model:effort";
        List<String> paths = List.of("Auth.java");
        final java.util.Map<String, Object> cache = new java.util.LinkedHashMap<>();
        final List<InternalReviewGate.Report> saved = new ArrayList<>();

        public String snapshot() { return snapshot; }
        public String signature(InternalReviewGate.Specialist specialist) { return signature; }
        public List<String> changedPaths() { return paths; }
        public java.util.Map<String, Object> cache() { return cache; }
        public void persist(InternalReviewGate.Report report) { saved.add(report); }
    }

    @Test
    void exactFindingsArePersistedBeforeFixAndPassedWithoutReconstruction() {
        Context context = new Context();
        String original = "[CRITICAL] File: Auth.java | Line: 7 | Issue: broken\r\n" + FAIL;
        InternalReviewGate.Report report = new InternalReviewGate(List.of(always("security")), 2)
                .run(context, request -> {
                    if (request.fix()) {
                        assertThat(request.findings().output()).isEqualTo(original);
                        assertThat(context.saved.getLast().unresolved().getFirst().evidence().output())
                                .isEqualTo(original);
                        context.snapshot = "tree-fixed";
                        return new ReviewEvidence(0, PASS);
                    }
                    return new ReviewEvidence(0, context.snapshot.equals("tree-0") ? original : PASS);
                });
        assertThat(report.passed()).isTrue();
        assertThat(report.history().getFirst().output()).isEqualTo(original);
        assertThat(context.saved.getLast()).isEqualTo(report);
    }

    @Test
    void laterFixRevalidatesEarlierSpecialistEvenIfItsTriggerDisappears() {
        Context context = new Context();
        List<String> calls = new ArrayList<>();
        InternalReviewGate.Report report = new InternalReviewGate(
                List.of(gated("security", "auth"), always("test")), 1).run(context, request -> {
                    calls.add(request.promptFile() + ":" + context.snapshot);
                    if (request.fix()) {
                        context.snapshot = "tree-1";
                        context.paths = List.of("README.md");
                        return new ReviewEvidence(0, PASS);
                    }
                    boolean bad = request.specialist().name().equals("test") && context.snapshot.equals("tree-0");
                    return new ReviewEvidence(0, bad ? FAIL : PASS);
                });
        assertThat(report.passed()).isTrue();
        assertThat(calls).containsExactly("security.md:tree-0", "test.md:tree-0", "test-fix.md:tree-0",
                "security.md:tree-1", "test.md:tree-1");
        assertThat(report.snapshot()).isEqualTo("tree-1");
    }

    @Test
    void noProgressStopsAfterOneFixAndRetainsOriginalFinding() {
        Context context = new Context();
        AtomicInteger calls = new AtomicInteger();
        InternalReviewGate.Report report = new InternalReviewGate(List.of(always("security")), 100)
                .run(context, request -> {
                    calls.incrementAndGet();
                    return new ReviewEvidence(0, request.fix() ? PASS : FAIL);
                });
        assertThat(calls.get()).isEqualTo(2);
        assertThat(report.passed()).isFalse();
        assertThat(report.unresolved().getFirst().evidence().output()).isEqualTo(FAIL);
        assertThat(report.unresolved().getFirst().reason()).contains("no snapshot progress");
    }

    @Test
    void restoredFindingsSurviveUntilTheirSpecialistPassesIncludingChangedTriggers() {
        var prior = new InternalReviewGate.Finding("security", "old-tree", new ReviewEvidence(0, FAIL), "issue");
        Context context = new Context() {
            public List<InternalReviewGate.Finding> unresolved() { return List.of(prior); }
        };
        context.paths = List.of("README.md");
        List<String> calls = new ArrayList<>();
        InternalReviewGate.Report report = new InternalReviewGate(
                List.of(always("general"), gated("security", "auth")), 0).run(context, request -> {
                    calls.add(request.specialist().name());
                    assertThat(context.saved.getLast().passed()).isFalse();
                    assertThat(context.saved.getLast().unresolved()).contains(prior);
                    return new ReviewEvidence(0, PASS);
                });
        assertThat(calls).containsExactly("general", "security");
        assertThat(report.passed()).isTrue();
        assertThat(report.unresolved()).isEmpty();
    }

    @Test
    void durableCacheRequiresExactSnapshotPromptAndReviewerSignature() {
        Context context = new Context();
        AtomicInteger calls = new AtomicInteger();
        InternalReviewGate.EvidenceRunner runner = request -> {
            calls.incrementAndGet();
            return new ReviewEvidence(0, PASS);
        };
        for (int i = 0; i < 2; i++) {
            assertThat(new InternalReviewGate(List.of(always("general")), 0).run(context, runner).passed()).isTrue();
        }
        assertThat(calls.get()).isEqualTo(1);
        context.signature = "different-model";
        new InternalReviewGate(List.of(always("general")), 0).run(context, runner);
        context.snapshot = "different-tree";
        new InternalReviewGate(List.of(always("general")), 0).run(context, runner);
        var changedPrompt = new InternalReviewGate.Specialist("general", "other.md", "fix.md", List.of());
        new InternalReviewGate(List.of(changedPrompt), 0).run(context, runner);
        assertThat(calls.get()).isEqualTo(4);
        context.snapshot = "unavailable";
        assertThat(new InternalReviewGate(List.of(always("general")), 0).run(context, runner).passed()).isFalse();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void missingSignatureDisablesCacheAndLateSignatureChangeRevalidates() {
        Context context = new Context();
        context.signature = "";
        AtomicInteger calls = new AtomicInteger();
        InternalReviewGate.EvidenceRunner runner = request -> {
            calls.incrementAndGet();
            return new ReviewEvidence(0, PASS);
        };
        InternalReviewGate gate = new InternalReviewGate(List.of(always("general")), 0);
        gate.run(context, runner);
        gate.run(context, runner);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(context.cache).isEmpty();

        context.signature = "initial";
        List<String> reviewed = new ArrayList<>();
        InternalReviewGate.Report report = new InternalReviewGate(
                List.of(always("general"), always("test")), 0).run(context, request -> {
                    reviewed.add(request.specialist().name() + ":" + context.signature);
                    if (request.specialist().name().equals("test")) {
                        context.signature = "updated";
                    }
                    return new ReviewEvidence(0, PASS);
                });
        assertThat(report.passed()).isTrue();
        assertThat(reviewed).containsExactly("general:initial", "test:initial", "general:updated", "test:updated");
    }

    @Test
    void unsuccessfulOrMissingEvidenceCannotPass() {
        List<ReviewEvidence> bad = List.of(new ReviewEvidence(1, PASS), new ReviewEvidence(0, null),
                new ReviewEvidence(0, "RESULT_STATUS: SUCCESS\n"),
                new ReviewEvidence(0, PASS + "[CRITICAL] File: Auth.java | Issue: broken\n"),
                new ReviewEvidence(0, PASS + "RESULT_BLOCKER: unsafe\n"),
                new ReviewEvidence(0, "RESULT_STATUS: ERROR\nRESULT_EVIDENCE: checked\n"),
                new ReviewEvidence(0, "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: PROMPT_ABSENT\n"),
                new ReviewEvidence(0, PASS + "RESULT_EVIDENCE: another answer\n"),
                new ReviewEvidence(0, "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: [CRITICAL] broken\n"),
                new ReviewEvidence(0, "RESULT_STATUS: SUCCESS\nRESULT_EVIDENCE: RENDER_FAILED\n"));
        for (ReviewEvidence evidence : bad) {
            assertThat(new InternalReviewGate(List.of(always("general")), 0)
                    .run(new Context(), request -> evidence).passed()).as(evidence.toString()).isFalse();
        }
        assertThat(new ReviewEvidence(0, PASS).passed()).isTrue();
    }

    @Test
    void failedFixCannotBeClearedBySubsequentSuccess() {
        Context context = new Context();
        InternalReviewGate.Report report = new InternalReviewGate(List.of(always("general")), 2)
                .run(context, request -> {
                    if (request.fix()) {
                        context.snapshot = "changed";
                        return new ReviewEvidence(2, PASS);
                    }
                    return new ReviewEvidence(0, context.snapshot.equals("tree-0") ? FAIL : PASS);
                });
        assertThat(report.passed()).isFalse();
        assertThat(report.unresolved().getFirst().reason()).contains("Fix failed");
        assertThat(report.history()).contains(new ReviewEvidence(2, PASS));
    }

    @Test
    void reviewerMutationsAreBoundedAndPersistenceFailuresPropagate() {
        Context context = new Context();
        AtomicInteger calls = new AtomicInteger();
        InternalReviewGate.Report report = new InternalReviewGate(List.of(always("general")), 1)
                .run(context, request -> {
                    context.snapshot = "mutation-" + calls.incrementAndGet();
                    return new ReviewEvidence(0, PASS);
                });
        assertThat(report.passed()).isFalse();
        assertThat(calls.get()).isEqualTo(3);
        Context brokenStore = new Context() {
            public void persist(InternalReviewGate.Report ignored) { throw new IllegalStateException("disk full"); }
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new InternalReviewGate(List.of(always("general")), 1)
                        .run(brokenStore, request -> new ReviewEvidence(0, FAIL)))
                .hasMessage("disk full");
    }

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
