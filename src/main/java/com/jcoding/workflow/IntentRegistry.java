package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned closed capability registry; routing evidence never grants permissions. */
final class IntentRegistry {
    record Slot(String type, boolean required, String source) { }
    record Spec(String id, int version, String handler, String objectivePrefix,
                String riskTier, String owner, String whenNotToUse, Map<String, Slot> slots) {
        Set<String> requiredSlots() {
            Set<String> required = new LinkedHashSet<>();
            slots.forEach((key, value) -> { if (value.required()) required.add(key); });
            return Set.copyOf(required);
        }
        Map<String, Object> contract() {
            return Map.of("id", id, "version", version, "handler", handler,
                    "risk_tier", riskTier, "owner", owner, "when_not_to_use", whenNotToUse,
                    "slots", slots, "status", "ACTIVE");
        }
    }
    record Selection(String intent, String source, List<String> questions,
                     List<String> sequence, List<Map<String, Object>> candidates) { }
    private static final Map<String, Slot> STORY_SLOTS = Map.of(
            "objective", new Slot("nonempty_string", true, "issue.title"),
            "acceptance_criteria", new Slot("unique_id_text_array", true, "issue.body"),
            "constraints", new Slot("string_array", false, "issue.body"));
    private static final Map<String, Spec> SPECS = Map.of(
            "implement_story", new Spec("implement_story", 1, "story_delivery",
                    "Implement the requested story", "R2", "repository-maintainers",
                    "Use repair_story for correcting existing behavior; research or operations need a supported contract", STORY_SLOTS),
            "repair_story", new Spec("repair_story", 1, "story_delivery",
                    "Repair the behavior described by the story", "R2", "repository-maintainers",
                    "Use implement_story for new behavior; do not use for deployment or destructive operations", STORY_SLOTS));

    private IntentRegistry() { }
    static Spec find(String id) { return SPECS.get(id); }
    static Set<String> supported() { return Set.copyOf(SPECS.keySet()); }

    static Selection select(Map<String, Object> issue, Map<String, Object> declared, Map<String, Object> policy) {
        List<String> questions = new ArrayList<>();
        Set<String> labels = labelIntents(issue);
        String explicit = Engine.asString(declared.get("intent")).strip();
        List<String> sequence = declaredSequence(declared, explicit, questions);
        if (!sequence.isEmpty()) explicit = sequence.getFirst();
        validateDeclarations(labels, explicit, questions);
        String selected = !explicit.isBlank() ? explicit : labels.size() == 1 ? labels.iterator().next()
                : Engine.asString(policy.get("default_intent")).strip();
        String source = !explicit.isBlank() ? "ISSUE_CONTRACT" : labels.size() == 1 ? "ISSUE_LABEL"
                : !selected.isBlank() ? "CONTROLLER_DEFAULT" : "UNRESOLVED";
        Spec spec = find(selected);
        if (spec == null) questions.add("Declare one supported intent using an intent label or jcoding-intent block: " + supported());
        validateVersion(declared, spec, questions);
        List<Map<String, Object>> candidates = candidates(issue);
        if (selected.isBlank() && !candidates.isEmpty()) {
            questions.add("Intent is undeclared; candidate evidence is advisory only: " + candidates);
        }
        return new Selection(selected, source, List.copyOf(questions),
                sequence.isEmpty() ? selected.isBlank() ? List.of() : List.of(selected) : List.copyOf(sequence), candidates);
    }

    private static void validateDeclarations(Set<String> labels, String explicit, List<String> questions) {
        if (labels.size() > 1 || !explicit.isBlank() && !labels.isEmpty() && !labels.contains(explicit)) {
            questions.add("Resolve conflicting intent declarations in the issue body and intent labels");
        }
    }

    private static void validateVersion(Map<String, Object> declared, Spec spec, List<String> questions) {
        if (declared.containsKey("version") && (spec == null
                || !Integer.valueOf(spec.version()).equals(declared.get("version")))) {
            questions.add("Intent contract version is unsupported");
        }
    }

    private static List<String> declaredSequence(Map<String, Object> declared, String explicit, List<String> questions) {
        List<String> sequence = Engine.asList(declared.get("intents")).stream().map(String::valueOf).toList();
        if (sequence.isEmpty()) return sequence;
        if (sequence.size() < 2 || sequence.size() > SPECS.size() || sequence.stream().anyMatch(id -> find(id) == null)
                || sequence.stream().distinct().count() != sequence.size()) {
            questions.add("Compound intent sequence must contain distinct registered capabilities");
        }
        if (!explicit.isBlank() && !explicit.equals(sequence.getFirst())) {
            questions.add("Compound intent sequence must have a clear first intent");
        }
        return sequence;
    }

    /** Transparent advisory ranking; uncalibrated scores never authorize execution. */
    static List<Map<String, Object>> candidates(Map<String, Object> issue) {
        String title = Engine.asString(issue.get("title")).toLowerCase(java.util.Locale.ROOT);
        String body = Engine.asString(issue.get("body")).toLowerCase(java.util.Locale.ROOT);
        double repair = title.matches(".*\\b(fix|repair|bug|regression|broken)\\b.*") ? 0.8 : 0.0;
        double implement = title.matches(".*\\b(add|build|implement|create|support)\\b.*") ? 0.8 : 0.0;
        if (body.contains("reproduce")) repair += 0.1;
        if (body.contains("new feature")) implement += 0.1;
        List<Map<String, Object>> results = new ArrayList<>();
        if (repair > 0) results.add(Map.of("intent", "repair_story", "score", repair));
        if (implement > 0) results.add(Map.of("intent", "implement_story", "score", implement));
        results.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return List.copyOf(results);
    }

    static double outOfScopeScore(Map<String, Object> issue) {
        String title = Engine.asString(issue.get("title")).toLowerCase(java.util.Locale.ROOT);
        return title.matches(".*\\b(research|operate|deploy|delete|trade|purchase)\\b.*") ? 0.8 : 0.0;
    }
    private static Set<String> labelIntents(Map<String, Object> issue) {
        Set<String> labels = new LinkedHashSet<>();
        for (Object raw : Engine.asList(issue.get("labels"))) {
            String label = raw instanceof Map<?, ?> map ? Engine.asString(map.get("name")) : Engine.asString(raw);
            if (label.startsWith("intent:")) labels.add(label.substring(7).strip());
        }
        return labels;
    }

}
