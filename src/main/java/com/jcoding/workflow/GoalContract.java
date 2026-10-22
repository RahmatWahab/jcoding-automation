package com.jcoding.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Versioned intent derived from issue evidence, never from a worker's completion claim. */
final class GoalContract {
    private GoalContract() { }
    private static final ObjectMapper JSON = new ObjectMapper();

    static String hashData(Object value) {
        try {
            return hash(new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value));
        } catch (java.io.IOException exc) { throw new IllegalArgumentException("Cannot fingerprint contract", exc); }
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exc) { throw new IllegalStateException(exc); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> capture(Map<String, Object> issue, Map<String, Object> epic,
                                       Map<String, Object> config) {
        return capture(issue, epic, config, Map.of());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> capture(Map<String, Object> issue, Map<String, Object> epic,
                                       Map<String, Object> config, Map<String, Object> reply) {
        String issueBody = Engine.asString(issue.get("body"));
        String body = issueBody;
        if (!reply.isEmpty()) body += "\n\nAuthorized clarification reply:\n" + Engine.asString(reply.get("body"));
        if (body.length() > 60_000) throw new IllegalArgumentException("Story requirements exceed the 60000-character contract limit");
        Map<String, Object> structured = Map.of();
        var block = Pattern.compile("(?s)```jcoding-intent\\s*\\n(.*?)```").matcher(issueBody);
        if (block.find()) {
            try { structured = JSON.readValue(block.group(1), Map.class); }
            catch (Exception exc) { throw new IllegalArgumentException("Invalid jcoding-intent JSON", exc); }
            if (block.find()) throw new IllegalArgumentException("Multiple intent contracts are ambiguous");
        }
        if (!reply.isEmpty()) {
            Map<String, Object> amendment = (Map<String, Object>) reply.get("contract");
            Map<String, Object> merged = new LinkedHashMap<>(structured);
            merged.putAll(amendment);
            structured = merged;
        }
        IntentRegistry.Selection selection = IntentRegistry.select(issue, structured, Config.section(config, "intent"));
        String requestedIntent = selection.intent();
        // The registry is deliberately closed: unimplemented intent types are
        // surfaced for clarification instead of silently routed to a worker.
        List<String> questions = new ArrayList<>(selection.questions());
        if (Engine.asString(issue.get("title")).isBlank()) questions.add("Provide a concise story title describing the intended outcome");
        IntentRegistry.Spec intentSpec = IntentRegistry.find(requestedIntent);
        if (intentSpec == null) {
            questions.add("Unsupported intent '" + requestedIntent + "'; supported intents: " + IntentRegistry.supported());
        }
        List<Map<String, String>> criteria = new ArrayList<>();
        if (Engine.asList(structured.get("criteria")).size() > 50) throw new IllegalArgumentException("A story may define at most 50 acceptance criteria");
        for (Object value : Engine.asList(structured.get("criteria"))) {
            if (!(value instanceof Map<?, ?> c) || Engine.asString(c.get("id")).isBlank()
                    || Engine.asString(c.get("text")).isBlank()) {
                throw new IllegalArgumentException("Every criterion requires id and text");
            }
            criteria.add(Map.of("id", c.get("id").toString(), "text", c.get("text").toString()));
        }
        if (criteria.isEmpty()) {
            boolean inCriteria = false;
            for (String line : body.split("\\R")) {
                if (line.matches("(?i)^#{1,6}\\s+.*acceptance.*")) { inCriteria = true; continue; }
                if (line.matches("^#{1,6}\\s+.*")) inCriteria = false;
                if (inCriteria && line.matches("^\\s*[-*]\\s+.+")) {
                    String text = line.replaceFirst("^\\s*[-*]\\s+(?:\\[[ xX]\\]\\s*)?", "").strip();
                    criteria.add(Map.of("id", "AC-" + (criteria.size() + 1), "text", text));
                }
            }
        }
        if (criteria.stream().map(c -> c.get("id")).distinct().count() != criteria.size()) {
            throw new IllegalArgumentException("Duplicate criterion IDs");
        }
        List<String> suppliedQuestions = Engine.asList(structured.get("questions")).stream()
                .map(String::valueOf).map(String::strip).filter(q -> !q.isBlank()).toList();
        if (suppliedQuestions.size() > 20 || suppliedQuestions.stream().anyMatch(q -> q.length() > 500)) {
            throw new IllegalArgumentException("Clarification requests are limited to 20 questions of 500 characters each");
        }
        questions.addAll(suppliedQuestions);
        if (criteria.isEmpty()) {
            questions.add("Provide explicit acceptance criteria in the story");
        }
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("schema_version", 2);
        goal.put("intent", requestedIntent);
        goal.put("intent_source", selection.source());
        goal.put("intent_sequence", selection.sequence());
        goal.put("intent_candidates", selection.candidates());
        double firstScore = selection.candidates().isEmpty() ? 0.0
                : (double) selection.candidates().getFirst().get("score");
        double secondScore = selection.candidates().size() < 2 ? 0.0
                : (double) selection.candidates().get(1).get("score");
        goal.put("intent_detection", Map.of("margin", firstScore - secondScore,
                "out_of_scope_score", IntentRegistry.outOfScopeScore(issue),
                "decision", selection.source().equals("UNRESOLVED") ? "ABSTAIN" : "DECLARED"));
        goal.put("intent_contract", intentSpec == null ? Map.of() : intentSpec.contract());
        goal.put("intent_version", intentSpec == null ? 0 : intentSpec.version());
        goal.put("intent_handler", intentSpec == null ? "UNSUPPORTED" : intentSpec.handler());
        goal.put("intent_objective", intentSpec == null ? "" : intentSpec.objectivePrefix());
        goal.put("story", Engine.asInt(issue.get("number")));
        goal.put("repository", Config.section(config, "repository").getOrDefault("owner", "") + "/"
                + Config.section(config, "repository").getOrDefault("name", ""));
        goal.put("objective", issue.getOrDefault("title", ""));
        goal.put("source_url", issue.getOrDefault("url", ""));
        goal.put("source_revision", issue.getOrDefault("source_revision",
                issue.getOrDefault("updatedAt", issue.getOrDefault("updated_at", hash(issueBody)))));
        goal.put("requester", issue.get("author") instanceof Map<?, ?> actor
                ? Engine.asString(actor.get("login")) : "");
        goal.put("requirements", body);
        List<Map<String, String>> requirementItems = new ArrayList<>();
        for (Object value : Engine.asList(structured.get("requirements"))) {
            if (!(value instanceof Map<?, ?> item) || Engine.asString(item.get("id")).isBlank()
                    || Engine.asString(item.get("text")).isBlank()) {
                throw new IllegalArgumentException("Every structured requirement requires id and text");
            }
            requirementItems.add(Map.of("id", Engine.asString(item.get("id")),
                    "text", Engine.asString(item.get("text")), "source", reply.isEmpty()
                            ? Engine.asString(issue.get("url")) : Engine.asString(reply.get("id")),
                    "status", "ACTIVE"));
        }
        if (requirementItems.isEmpty() && !issueBody.isBlank()) {
            requirementItems.add(Map.of("id", "REQ-" + hash(issueBody).substring(0, 12),
                    "text", issueBody, "source", Engine.asString(issue.get("url")), "status", "ACTIVE"));
        }
        if (requirementItems.stream().map(item -> item.get("id")).distinct().count() != requirementItems.size()) {
            throw new IllegalArgumentException("Duplicate requirement IDs");
        }
        goal.put("requirement_items", requirementItems);
        goal.put("supersedes", Engine.asList(structured.get("supersedes")));
        goal.put("supersession_history", new ArrayList<>());
        goal.put("assumptions", Engine.asList(structured.get("assumptions")));
        goal.put("non_goals", Engine.asList(structured.get("non_goals")));
        if (!reply.isEmpty()) goal.put("clarification_source", reply.get("id"));
        goal.put("criteria", criteria);
        goal.put("questions", questions);
        Object constraints = structured.getOrDefault("constraints", List.of());
        if (!(constraints instanceof List<?> list) || list.size() > 50
                || list.stream().anyMatch(v -> !(v instanceof String text) || text.isBlank() || text.length() > 2000)) {
            throw new IllegalArgumentException("Constraints must be at most 50 nonempty strings of 2000 characters");
        }
        goal.put("constraints", constraints);
        List<Map<String, String>> constraintItems = new ArrayList<>();
        for (Object value : (List<?>) constraints) {
            String text = String.valueOf(value);
            constraintItems.add(Map.of("id", "CONSTRAINT-" + hash(text).substring(0, 12),
                    "text", text, "source", reply.isEmpty() ? Engine.asString(issue.get("url"))
                            : Engine.asString(reply.get("id")), "status", "ACTIVE"));
        }
        if (constraintItems.stream().map(item -> item.get("id")).distinct().count() != constraintItems.size())
            throw new IllegalArgumentException("Duplicate constraints");
        goal.put("constraint_items", constraintItems);
        // Authority comes exclusively from controller config, never ticket text.
        goal.put("permissions", new LinkedHashMap<>(Config.section(config, "workflow")));
        List<String> allowedActions = new ArrayList<>(List.of("implement", "verify"));
        Map<String, Object> workflow = Config.section(config, "workflow");
        if (Boolean.TRUE.equals(workflow.get("allow_push"))) allowedActions.add("commit_and_push");
        if (Boolean.TRUE.equals(workflow.get("allow_pr_creation"))) allowedActions.add("create_pull_request");
        if (Boolean.TRUE.equals(workflow.get("allow_comments"))) allowedActions.add("comment");
        if (Boolean.TRUE.equals(workflow.get("allow_thread_resolution"))) allowedActions.add("resolve_review_thread");
        if (Boolean.TRUE.equals(workflow.get("auto_merge")) && Boolean.TRUE.equals(workflow.get("allow_push"))) {
            allowedActions.add("merge_verified_head");
        }
        goal.put("allowed_actions", allowedActions);
        goal.put("autonomy_ceiling", "controller_policy");
        goal.put("scope", new LinkedHashMap<>(Config.section(config, "verification")));
        goal.put("requirements_hash", hashData(Map.of("goal", goal, "epic_requirements", Engine.asString(epic.get("body")))));
        goal.put("status", questions.isEmpty() ? "ACTIVE" : "NEEDS_CLARIFICATION");
        return goal;
    }

    static boolean observe(Map<String, Object> state, Map<String, Object> goal) {
        Object previous = state.get("goal_contract");
        int revision = 1;
        if (previous instanceof Map<?, ?> old) {
            revision = (int) Engine.asLongOrDefault(old.get("revision"), 1);
            java.util.Set<String> superseded = new java.util.HashSet<>(Engine.asList(goal.get("supersedes"))
                    .stream().map(String::valueOf).toList());
            List<Map<String, Object>> supersessionHistory = new ArrayList<>();
            for (Object prior : Engine.asList(old.get("supersession_history"))) {
                if (prior instanceof Map<?, ?> item) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    item.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    supersessionHistory.add(copy);
                }
            }
            @SuppressWarnings("unchecked") List<Map<String, String>> current =
                    (List<Map<String, String>>) goal.get("requirement_items");
            java.util.Set<String> currentIds = new java.util.HashSet<>();
            current.forEach(item -> currentIds.add(item.get("id")));
            for (Object prior : Engine.asList(old.get("requirement_items"))) {
                if (!(prior instanceof Map<?, ?> item)) continue;
                String id = Engine.asString(item.get("id"));
                if (superseded.contains(id)) {
                    supersessionHistory.add(Map.of("id", id, "text", Engine.asString(item.get("text")),
                            "source", Engine.asString(item.get("source")), "status", "SUPERSEDED",
                            "at", Controller.now()));
                    continue;
                }
                if (!currentIds.contains(id)) {
                    current.add(Map.of("id", id, "text", Engine.asString(item.get("text")),
                            "source", Engine.asString(item.get("source")), "status", "ACTIVE"));
                } else if (current.stream().anyMatch(now -> id.equals(now.get("id"))
                        && !Engine.asString(item.get("text")).equals(now.get("text")))) {
                    @SuppressWarnings("unchecked") List<String> questions = (List<String>) goal.get("questions");
                    questions.add("Requirement " + id + " changed without explicit supersession");
                    goal.put("status", "NEEDS_CLARIFICATION");
                }
            }
            goal.put("supersession_history", supersessionHistory);
            @SuppressWarnings("unchecked") List<Map<String, String>> currentCriteria =
                    (List<Map<String, String>>) goal.get("criteria");
            java.util.Set<String> criterionIds = new java.util.HashSet<>();
            currentCriteria.forEach(item -> criterionIds.add(item.get("id")));
            for (Object prior : Engine.asList(old.get("criteria"))) {
                if (!(prior instanceof Map<?, ?> item)) continue;
                String id = Engine.asString(item.get("id"));
                if (superseded.contains(id)) continue;
                if (!criterionIds.contains(id)) {
                    currentCriteria.add(Map.of("id", id, "text", Engine.asString(item.get("text"))));
                } else if (currentCriteria.stream().anyMatch(now -> id.equals(now.get("id"))
                        && !Engine.asString(item.get("text")).equals(now.get("text")))) {
                    @SuppressWarnings("unchecked") List<String> questions = (List<String>) goal.get("questions");
                    questions.add("Acceptance criterion " + id + " changed without explicit supersession");
                    goal.put("status", "NEEDS_CLARIFICATION");
                }
            }
            @SuppressWarnings("unchecked") List<Map<String, String>> currentConstraints =
                    (List<Map<String, String>>) goal.get("constraint_items");
            java.util.Set<String> constraintIds = new java.util.HashSet<>();
            currentConstraints.forEach(item -> constraintIds.add(item.get("id")));
            for (Object prior : Engine.asList(old.get("constraint_items"))) {
                if (!(prior instanceof Map<?, ?> item)) continue;
                String id = Engine.asString(item.get("id"));
                if (superseded.contains(id)) {
                    supersessionHistory.add(Map.of("kind", "constraint", "id", id,
                            "text", Engine.asString(item.get("text")), "source", Engine.asString(item.get("source")),
                            "status", "SUPERSEDED", "at", Controller.now()));
                } else if (!constraintIds.contains(id)) {
                    currentConstraints.add(Map.of("id", id, "text", Engine.asString(item.get("text")),
                            "source", Engine.asString(item.get("source")), "status", "ACTIVE"));
                    constraintIds.add(id);
                }
            }
            goal.put("constraints", currentConstraints.stream().map(item -> item.get("text")).toList());
            goal.put("supersession_history", supersessionHistory);
            if (!Engine.asString(old.get("objective")).equals(Engine.asString(goal.get("objective")))
                    && superseded.isEmpty()) {
                @SuppressWarnings("unchecked") List<String> questions = (List<String>) goal.get("questions");
                questions.add("Objective changed without an explicit supersession decision");
                goal.put("status", "NEEDS_CLARIFICATION");
            }
            if (!currentCriteria.isEmpty()) {
                @SuppressWarnings("unchecked") List<String> questions = (List<String>) goal.get("questions");
                questions.remove("Provide explicit acceptance criteria in the story");
                if (questions.isEmpty()) goal.put("status", "ACTIVE");
            }
        }
        Map<String, Object> effective = new LinkedHashMap<>(goal);
        for (String key : List.of("requirements_hash", "status", "revision", "requirements",
                "source_url", "source_revision", "intent_candidates", "intent_detection", "requester",
                "questions", "clarification_source")) effective.remove(key);
        goal.put("requirements_hash", hashData(effective));
        if (previous instanceof Map<?, ?> old && "CANCELLED".equals(old.get("status"))
                && goal.get("requirements_hash").equals(old.get("requirements_hash"))) {
            goal.put("status", "CANCELLED");
            @SuppressWarnings("unchecked") List<String> questions = (List<String>) goal.get("questions");
            questions.add("Task was explicitly cancelled; create a revised request to resume");
        }
        if (previous instanceof Map<?, ?> old && Set.of("SUPERSEDED", "ABANDONED", "FAILED")
                .contains(Engine.asString(old.get("status")))
                && goal.get("requirements_hash").equals(old.get("requirements_hash"))) {
            goal.put("status", old.get("status"));
            goal.put("terminal_at", old.containsKey("terminal_at") ? old.get("terminal_at") : Controller.now());
            @SuppressWarnings("unchecked") List<String> questions = (List<String>) goal.get("questions");
            questions.add("Task is terminal; create a revised request to resume");
        }
        if (previous instanceof Map<?, ?> old && Set.of("CANCELLED", "INTERRUPTED", "SUPERSEDED", "ABANDONED")
                .contains(Engine.asString(old.get("status")))
                && !goal.get("requirements_hash").equals(old.get("requirements_hash"))) {
            goal.put("resumed_from_status", old.get("status"));
        }
        if (previous instanceof Map<?, ?> old && Set.of("VERIFIED", "FAILED")
                .contains(Engine.asString(old.get("status")))) {
            if (goal.get("requirements_hash").equals(old.get("requirements_hash"))) {
                goal.put("status", old.get("status"));
                if (old.containsKey("terminal_at")) goal.put("terminal_at", old.get("terminal_at"));
            } else {
                goal.put("resumed_from_status", old.get("status"));
            }
        }
        if (previous instanceof Map<?, ?> old
                && !goal.get("requirements_hash").equals(old.get("requirements_hash"))) revision++;
        goal.put("revision", revision);
        boolean changed = previous instanceof Map<?, ?> old
                && !goal.get("requirements_hash").equals(old.get("requirements_hash"));
        if (changed) {
            List<Object> history = new ArrayList<>(Engine.asList(state.get("goal_history")));
            Map<String, Object> supersededRevision = new LinkedHashMap<>();
            ((Map<?, ?>) previous).forEach((key, value) -> supersededRevision.put(String.valueOf(key), value));
            supersededRevision.put("status", "SUPERSEDED");
            supersededRevision.put("superseded_at", Controller.now());
            supersededRevision.put("superseded_by_revision", revision);
            history.add(supersededRevision);
            state.put("goal_history", history.subList(Math.max(0, history.size() - 20), history.size()));
            for (String key : List.of("validation_checkpoint", "plan_binding", "review_cache", "held_failure",
                    "task_ledger", "plan_replans_used", "replan_request", "local_delivery_result",
                    "local_outcome")) state.remove(key);
            if (state.get("criterion_evidence") instanceof Map<?, ?> priorEvidence) {
                Map<String, Object> stale = new LinkedHashMap<>();
                priorEvidence.forEach((key, value) -> {
                    if (value instanceof Map<?, ?> item) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        item.forEach((field, data) -> record.put(String.valueOf(field), data));
                        record.put("status", "STALE");
                        stale.put(String.valueOf(key), record);
                    }
                });
                state.put("criterion_evidence", stale);
            }
            state.put("requirements_changed", true);
            state.remove("clarification_delivery");
        }
        state.put("goal_contract", goal);
        state.put("clarification", goal.get("questions"));
        return changed;
    }

    static String requirementsHash(Map<String, Object> state) {
        return state.get("goal_contract") instanceof Map<?, ?> goal
                ? Engine.asString(goal.get("requirements_hash")) : "";
    }
}
