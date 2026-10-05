package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Sequential, bounded specialist review; unresolved evidence must block downstream delivery. */
final class InternalReviewGate {
    enum Outcome {
        PASSED,
        /** Legacy name retained for source compatibility; this outcome MUST block delivery. */
        DELIVER_WITH_UNRESOLVED
    }

    record Specialist(String name, String reviewPrompt, String fixPrompt, List<String> triggers) {
        Specialist {
            triggers = List.copyOf(triggers);
        }

        boolean appliesTo(List<String> paths) {
            return triggers.isEmpty() || paths.stream().anyMatch(path ->
                    triggers.stream().anyMatch(trigger -> path.contains(trigger.toLowerCase(Locale.ROOT))));
        }
    }

    @FunctionalInterface
    interface ReviewRunner {
        String run(String promptFile);
    }

    record Request(Specialist specialist, boolean fix, ReviewEvidence findings) {
        String promptFile() {
            return fix ? specialist.fixPrompt() : specialist.reviewPrompt();
        }
    }

    @FunctionalInterface
    interface EvidenceRunner {
        ReviewEvidence run(Request request);
    }

    record Finding(String specialist, String snapshot, ReviewEvidence evidence, String reason) { }

    /** History preserves exact review/fix responses even when later responses supersede them. */
    record Report(Outcome outcome, String snapshot, List<Finding> unresolved, List<ReviewEvidence> history) {
        Report {
            unresolved = List.copyOf(unresolved);
            history = List.copyOf(history);
        }

        boolean passed() {
            return outcome == Outcome.PASSED && unresolved.isEmpty();
        }
    }

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
        this.panel = List.copyOf(panel);
        this.maxFixAttempts = Math.max(0, maxFixAttempts);
    }

    /**
     * Compatibility adapter: cannot forward finding text or validate process exit codes.
     * Production callers must use the context/evidence overload. Synthetic epochs disable
     * cache reuse across fixes, conservatively revalidating every specialist.
     */
    Outcome run(List<String> changedPaths, ReviewRunner runner) {
        int[] epoch = {0};
        ReviewContext context = new ReviewContext() {
            public String snapshot() { return "legacy-" + epoch[0]; }
            public List<String> changedPaths() { return changedPaths; }
            public String signature(Specialist specialist) { return ""; }
            public void persist(Report report) { }
        };
        return run(context, request -> {
            String output = runner.run(request.promptFile());
            if (request.fix()) {
                epoch[0]++;
            }
            return new ReviewEvidence(0, output);
        }).outcome();
    }

    /**
     * Cache is caller-owned. Only exact available snapshot + rendered prompt/config
     * identities are reusable. Every mutation restarts the panel, with per-specialist fix
     * budgets shared across restarts. A final read-only pass validates the final snapshot.
     */
    Report run(ReviewContext context, EvidenceRunner runner) {
        Map<String, Object> cache = context.cache();
        Map<Specialist, Integer> attempts = new LinkedHashMap<>();
        Map<Specialist, Finding> unresolved = new LinkedHashMap<>();
        for (Finding finding : context.unresolved()) {
            // Gate infrastructure failures are re-evaluated below on every invocation.
            if (finding.specialist().equals("gate")) {
                continue;
            }
            Specialist specialist = panel.stream().filter(s -> s.name().equals(finding.specialist()))
                    .findFirst().orElse(new Specialist(finding.specialist(), "", "", List.of()));
            unresolved.put(specialist, finding);
        }
        Map<Specialist, Finding> fixErrors = new LinkedHashMap<>();
        java.util.Set<Specialist> selected = new java.util.LinkedHashSet<>();
        List<ReviewEvidence> history = new ArrayList<>();
        persist(context, context.snapshot(), unresolved, history, false);
        // Even a misbehaving reviewer that writes files cannot cause an infinite restart.
        long rounds = 1L + panel.size() * ((long) maxFixAttempts + 1);
        for (long round = 0; round < rounds; round++) {
            String snapshot = context.snapshot();
            if (!available(snapshot)) {
                unresolved.put(null, new Finding("gate", snapshot, new ReviewEvidence(-1, ""),
                        "Exact snapshot unavailable"));
                return persist(context, snapshot, unresolved, history, true);
            }
            List<String> paths = context.changedPaths().stream()
                    .map(path -> path.toLowerCase(Locale.ROOT)).toList();
            boolean mutated = false;
            Map<Specialist, String> reviewedSignatures = new LinkedHashMap<>();
            for (Specialist specialist : panel) {
                // Keep previously unresolved specialists in scope even if a fix changes paths.
                if (!specialist.appliesTo(paths) && !selected.contains(specialist)
                        && !unresolved.containsKey(specialist)) {
                    continue;
                }
                selected.add(specialist);
                String signature = context.signature(specialist);
                reviewedSignatures.put(specialist, signature);
                String key = cacheKey(snapshot, specialist, signature);
                boolean cacheable = available(signature);
                ReviewEvidence evidence = cacheable ? cached(cache.get(key)) : null;
                if (evidence == null) {
                    evidence = invoke(runner, new Request(specialist, false, null));
                    history.add(evidence);
                    if (cacheable && snapshot.equals(context.snapshot())
                            && Objects.equals(signature, context.signature(specialist))) {
                        if (evidence.exitCode() == 0 && !evidence.output().isBlank()) {
                            cache.put(key, Map.of("exit_code", evidence.exitCode(), "output", evidence.output()));
                        }
                    }
                }
                if (!snapshot.equals(context.snapshot())
                        || !Objects.equals(signature, context.signature(specialist))) {
                    unresolved.put(specialist, new Finding(specialist.name(), snapshot, evidence,
                            "Review changed snapshot or reviewer signature"));
                    persist(context, snapshot, unresolved, history, false);
                    mutated = true;
                    break;
                }
                if (evidence.passed()) {
                    if (fixErrors.containsKey(specialist)) {
                        unresolved.put(specialist, fixErrors.get(specialist));
                    } else {
                        unresolved.remove(specialist);
                    }
                    persist(context, snapshot, unresolved, history, false);
                    continue;
                }
                unresolved.put(specialist, new Finding(specialist.name(), snapshot, evidence,
                        "Review did not pass"));
                persist(context, snapshot, unresolved, history, false);
                int count = attempts.getOrDefault(specialist, 0);
                if (count >= maxFixAttempts) {
                    continue;
                }
                attempts.put(specialist, count + 1);
                ReviewEvidence fix = invoke(runner, new Request(specialist, true, evidence));
                history.add(fix);
                String after = context.snapshot();
                if (!fix.passed()) {
                    // Preserve the original finding; failed fixes must never be treated as clearance.
                    unresolved.put(specialist, new Finding(specialist.name(), snapshot, evidence,
                            "Fix failed; see history for exact fix output"));
                    fixErrors.put(specialist, unresolved.get(specialist));
                    attempts.put(specialist, maxFixAttempts);
                }
                if (Objects.equals(after, snapshot)) {
                    unresolved.put(specialist, new Finding(specialist.name(), snapshot, evidence,
                            "Fix made no snapshot progress"));
                    attempts.put(specialist, maxFixAttempts);
                    persist(context, after, unresolved, history, false);
                    continue;
                }
                persist(context, after, unresolved, history, false);
                mutated = true;
                break;
            }
            boolean signaturesStable = reviewedSignatures.entrySet().stream()
                    .allMatch(entry -> Objects.equals(entry.getValue(), context.signature(entry.getKey())));
            if (!mutated && snapshot.equals(context.snapshot()) && signaturesStable) {
                return persist(context, snapshot, unresolved, history, true);
            }
        }
        String finalSnapshot = context.snapshot();
        unresolved.put(null, new Finding("gate", finalSnapshot, new ReviewEvidence(-1, ""),
                "Snapshot did not stabilize within the review budget"));
        return persist(context, finalSnapshot, unresolved, history, true);
    }

    private static boolean available(String value) {
        return value != null && !value.isBlank() && !value.equalsIgnoreCase("unavailable")
                && !value.equalsIgnoreCase("unknown");
    }

    private static String cacheKey(String snapshot, Specialist specialist, String signature) {
        // Length-prefixing avoids delimiter collisions without relying on weak hashes.
        return List.of("review-v1", snapshot, specialist.name(), specialist.reviewPrompt(),
                Objects.toString(signature, "")).stream()
                .map(value -> value.length() + ":" + value).collect(java.util.stream.Collectors.joining());
    }

    private static ReviewEvidence cached(Object entry) {
        if (entry instanceof Map<?, ?> map && map.get("exit_code") instanceof Number exit
                && exit.intValue() == 0 && map.get("output") instanceof String output) {
            return new ReviewEvidence(0, output);
        }
        return null;
    }

    private static ReviewEvidence invoke(EvidenceRunner runner, Request request) {
        try {
            ReviewEvidence result = runner.run(request);
            return result == null ? new ReviewEvidence(-1, "") : result;
        } catch (RuntimeException failure) {
            return new ReviewEvidence(-1, failure.toString());
        }
    }

    private static Report persist(ReviewContext context, String snapshot,
                                  Map<Specialist, Finding> unresolved,
                                  List<ReviewEvidence> history, boolean complete) {
        Report report = new Report(complete && unresolved.isEmpty()
                ? Outcome.PASSED : Outcome.DELIVER_WITH_UNRESOLVED,
                snapshot, new ArrayList<>(unresolved.values()), history);
        context.persist(report);
        return report;
    }
}
