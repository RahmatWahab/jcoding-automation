package com.jcoding.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A worker may propose a DAG, but cannot assert its ownership or completion. */
final class TaskPlan {
    private TaskPlan() { }
    record Validated(String hash, List<Map<String, Object>> tasks) { }

    @SuppressWarnings("unchecked")
    static Validated validate(Path repo, Map<String, Object> state, Map<String, Object> config) {
        try {
            Path file = repo.resolve("plan.json");
            if (Files.isSymbolicLink(file) || Files.size(file) > 262144) throw new IllegalArgumentException("Unsafe plan artifact");
            String text = Files.readString(file);
            Map<String, Object> plan = new ObjectMapper().readValue(text, Map.class);
            if (!Integer.valueOf(1).equals(plan.get("schema_version"))
                    || Engine.asInt(plan.get("story")) != Engine.asInt(state.get("current_story"))
                    || !GoalContract.requirementsHash(state).equals(plan.get("requirements_hash"))
                    || !Engine.asString(state.get("base_commit")).equals(plan.get("base_sha"))) {
                throw new IllegalArgumentException("Plan does not match story, requirements or base revision");
            }
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) plan.get("tasks");
            if (tasks == null || tasks.isEmpty() || tasks.size() > 200) throw new IllegalArgumentException("Plan requires 1..200 tasks");
            Set<String> criteria = new HashSet<>();
            if (state.get("goal_contract") instanceof Map<?, ?> goal) {
                for (Object c : Engine.asList(goal.get("criteria"))) criteria.add(((Map<?, ?>) c).get("id").toString());
            }
            Set<String> checks = new HashSet<>();
            Map<String, Set<String>> coverage = new java.util.HashMap<>();
            for (Object c : Engine.asList(Config.section(config, "verification").get("checks"))) {
                if (c instanceof Map<?, ?> check) {
                    String id = Engine.asString(check.get("id"));
                    checks.add(id);
                    coverage.put(id, new HashSet<>(Engine.asList(check.get("criteria")).stream().map(String::valueOf).toList()));
                }
            }
            Set<String> seen = new HashSet<>();
            Set<String> covered = new HashSet<>();
            List<String> intentSequence = state.get("goal_contract") instanceof Map<?, ?> goal
                    ? Engine.asList(goal.get("intent_sequence")).stream().map(String::valueOf).toList() : List.of();
            int intentPosition = -1;
            for (Map<String, Object> task : tasks) {
                String id = Engine.asString(task.get("id"));
                if (id.isBlank() || seen.contains(id) || Engine.asString(task.get("description")).isBlank()) {
                    throw new IllegalArgumentException("Missing or duplicate task identity");
                }
                // Requiring topological order also detects cycles, missing edges and self-dependencies.
                for (Object dep : Engine.asList(task.get("depends_on"))) {
                    if (!seen.contains(String.valueOf(dep))) throw new IllegalArgumentException("Task dependency is not an earlier task: " + dep);
                }
                seen.add(id);
                if (intentSequence.size() > 1) {
                    String taskIntent = Engine.asString(task.get("intent"));
                    int position = intentSequence.indexOf(taskIntent);
                    if (position < 0 || position > intentPosition + 1 || position < intentPosition) {
                        throw new IllegalArgumentException("Compound task intents must follow the declared sequence");
                    }
                    intentPosition = position;
                }
                List<Object> mapped = Engine.asList(task.get("criteria"));
                if (mapped.isEmpty()) throw new IllegalArgumentException("Task must map to acceptance criteria");
                for (Object c : mapped) {
                    if (!criteria.contains(String.valueOf(c))) throw new IllegalArgumentException("Unknown criterion: " + c);
                    covered.add(String.valueOf(c));
                }
                List<Object> taskChecks = Engine.asList(task.get("checks"));
                if (taskChecks.isEmpty()) throw new IllegalArgumentException("Task must name a controller-owned check");
                Set<String> taskCoverage = new HashSet<>();
                for (Object c : taskChecks) {
                    if (!checks.contains(String.valueOf(c))) throw new IllegalArgumentException("Unknown check: " + c);
                    taskCoverage.addAll(coverage.get(String.valueOf(c)));
                }
                if (!taskCoverage.containsAll(mapped.stream().map(String::valueOf).toList())) {
                    throw new IllegalArgumentException("Task checks do not cover its acceptance criteria: " + id);
                }
            }
            if (!covered.containsAll(criteria)) throw new IllegalArgumentException("Plan omits acceptance criteria");
            if (intentSequence.size() > 1 && intentPosition != intentSequence.size() - 1) {
                throw new IllegalArgumentException("Plan omits a declared compound intent");
            }
            return new Validated(GoalContract.hash(text), List.copyOf(tasks));
        } catch (java.io.IOException | ClassCastException | NullPointerException exc) {
            throw new IllegalArgumentException("Plan is missing or malformed", exc);
        }
    }

    static boolean bound(Path repo, Map<String, Object> state, Map<String, Object> config) {
        try {
            Validated plan = validate(repo, state, config);
            return state.get("plan_binding") instanceof Map<?, ?> binding
                    && plan.hash().equals(binding.get("artifact_hash"))
                    && GoalContract.requirementsHash(state).equals(binding.get("requirements_hash"));
        } catch (IllegalArgumentException exc) { return false; }
    }
}
