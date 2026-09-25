package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-commit sequential specialist review gate.
 *
 * <p>Runs a bounded, sequential panel of specialist reviewers against the
 * <em>local, uncommitted</em> diff before the controller commits and opens/
 * updates the pull request. Each specialist runs review -&gt; (bounded fix loop)
 * -&gt; re-review -&gt; pass, one at a time (no parallelism — the controller is
 * single-threaded per tick). Only specialists whose trigger patterns match the
 * current diff run; {@code general} and {@code test} always run.
 *
 * <p>The gate is fully autonomous and never dead-ends: it gates only on a
 * non-SUCCESS specialist verdict, bounds each specialist's fix loop with
 * {@code internal_review_max_fix_attempts}, and on exhaustion carries the
 * finding forward as advisory (the post-PR Codex review remains the final
 * authority). It is opt-in via {@code workflow.internal_review} (default false),
 * so legacy behavior is unchanged unless explicitly enabled.
 *
 * <p>This class is deliberately kept out of the orchestrator: it has a single
 * responsibility (drive the specialist loop) and an injectable review runner so
 * it is unit-testable without launching {@code codex}.
 */
final class InternalReviewGate {

    private static final Logger log = LoggerFactory.getLogger(InternalReviewGate.class);

    /** Overall outcome of the gate. Both outcomes allow the controller to proceed to delivery. */
    enum Outcome {
        /** All relevant specialists passed (no unresolved blocking finding). */
        PASSED,
        /**
         * One or more specialists could not be satisfied within the fix-attempt
         * cap. The controller delivers anyway and defers to the post-PR Codex
         * review — the gate never blocks the autonomous loop on a human.
         */
        DELIVER_WITH_UNRESOLVED
    }

    /**
     * A specialist reviewer: the prompt file that reviews the diff, the prompt
     * file that fixes findings, and the lower-cased path substrings that make it
     * relevant to a diff. An empty trigger list means "always run".
     */
    record Specialist(String name, String reviewPrompt, String fixPrompt, List<String> triggers) {
        boolean appliesTo(List<String> changedPathsLower) {
            if (triggers.isEmpty()) {
                return true; // always-on (general, test)
            }
            for (String path : changedPathsLower) {
                for (String trigger : triggers) {
                    if (path.contains(trigger)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Runs one specialist prompt against the current worktree and returns the
     * raw worker output. Injected so tests can simulate reviewer verdicts without
     * launching {@code codex}. The controller wires this to
     * {@link CodexRunner#run} with the rendered specialist prompt.
     */
    @FunctionalInterface
    interface ReviewRunner {
        /** @return raw structured output (should contain a RESULT_STATUS footer). */
        String run(String promptFile);
    }

    /**
     * The default specialist panel, in cheap/structural -&gt; expensive order.
     * {@code general} and {@code test} always run; the rest are diff-gated.
     */
    static final List<Specialist> DEFAULT_PANEL = List.of(
            new Specialist("general", "prompts/review-general.md",
                    "prompts/review-general-fix.md", List.of()),
            new Specialist("simplification", "prompts/review-simplification.md",
                    "prompts/review-simplification-fix.md", List.of(".java", ".py", ".ts", ".go")),
            new Specialist("architecture", "prompts/review-architecture.md",
                    "prompts/review-architecture-fix.md", List.of(".java", ".py", ".ts", ".go")),
            new Specialist("test", "prompts/review-test.md",
                    "prompts/review-test-fix.md", List.of()),
            new Specialist("security", "prompts/review-security.md",
                    "prompts/review-security-fix.md",
                    List.of("auth", "login", "token", "crypto", "secret", "password",
                            "security", "jwt", "oauth", "session", "input", "sql")),
            new Specialist("performance", "prompts/review-performance.md",
                    "prompts/review-performance-fix.md",
                    List.of("repository", "query", "cache", "loop", "stream", "batch", "pool")),
            new Specialist("database", "prompts/review-database.md",
                    "prompts/review-database-fix.md",
                    List.of("migration", "liquibase", "flyway", "entity", "schema", ".sql", "repository")),
            new Specialist("deployment", "prompts/review-deployment.md",
                    "prompts/review-deployment-fix.md",
                    List.of("dockerfile", "docker", "helm", "chart", ".yml", ".yaml",
                            "k8s", "kubernetes", ".sh", "ci")),
            new Specialist("resilience", "prompts/review-resilience.md",
                    "prompts/review-resilience-fix.md",
                    List.of("retry", "timeout", "circuit", "bulkhead", "fallback", "resilience", "client")));

    private final List<Specialist> panel;
    private final int maxFixAttempts;

    InternalReviewGate(List<Specialist> panel, int maxFixAttempts) {
        this.panel = panel;
        this.maxFixAttempts = Math.max(1, maxFixAttempts);
    }

    /**
     * Run the sequential specialist gate. For each relevant specialist: review,
     * and while it reports a blocking finding, run its fix prompt and re-review,
     * up to {@code maxFixAttempts} times. A specialist that cannot be satisfied
     * within the cap is recorded and the gate proceeds (deliver-with-unresolved).
     *
     * @param changedPaths changed source paths (for diff-gating)
     * @param reviewRunner runs a prompt and returns raw structured output
     * @return the overall outcome (both outcomes permit delivery)
     */
    Outcome run(List<String> changedPaths, ReviewRunner reviewRunner) {
        List<String> changedLower = new ArrayList<>();
        for (String p : changedPaths) {
            changedLower.add(p.toLowerCase(Locale.ROOT));
        }

        List<String> unresolved = new ArrayList<>();
        for (Specialist s : panel) {
            if (!s.appliesTo(changedLower)) {
                log.info("INTERNAL REVIEW: skipping {} (diff does not match its triggers)", s.name());
                continue;
            }
            log.info("INTERNAL REVIEW: running {} specialist", s.name());
            boolean passed = reviewPassed(reviewRunner.run(s.reviewPrompt()));
            int attempt = 0;
            while (!passed && attempt < maxFixAttempts) {
                attempt++;
                log.info("INTERNAL REVIEW: {} found blocking issue(s); fix attempt {}/{}",
                        s.name(), attempt, maxFixAttempts);
                reviewRunner.run(s.fixPrompt());
                passed = reviewPassed(reviewRunner.run(s.reviewPrompt()));
            }
            if (passed) {
                log.info("INTERNAL REVIEW: {} PASSED{}", s.name(),
                        attempt > 0 ? " after " + attempt + " fix attempt(s)" : "");
            } else {
                unresolved.add(s.name());
                log.warn("INTERNAL REVIEW: {} could not be satisfied within {} fix attempt(s); "
                        + "carrying finding forward as advisory (post-PR Codex review remains the "
                        + "authority) — not blocking the autonomous loop", s.name(), maxFixAttempts);
            }
        }

        if (unresolved.isEmpty()) {
            log.info("INTERNAL REVIEW: all relevant specialists passed; proceeding to delivery");
            return Outcome.PASSED;
        }
        log.info("INTERNAL REVIEW: delivering with {} unresolved specialist finding(s): {}",
                unresolved.size(), String.join(", ", unresolved));
        return Outcome.DELIVER_WITH_UNRESOLVED;
    }

    /**
     * A review passes when its structured output declares
     * {@code RESULT_STATUS: SUCCESS}. Any other status (including {@code BLOCKED}
     * or a missing footer) is treated as a blocking finding — fail closed,
     * consistent with the controller's worker-result contract.
     */
    private static boolean reviewPassed(String output) {
        Map<String, String> parsed = StateRecovery.parseResult(output == null ? "" : output);
        return "SUCCESS".equals(parsed.getOrDefault("result_status", ""));
    }
}
