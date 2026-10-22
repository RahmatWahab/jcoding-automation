package com.jcoding.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoding.github.GitHub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Local request intake using the same story contract without GitHub side effects. */
final class LocalRequestGitHub extends GitHub {
    private final Path request;
    private final int epic;
    private final int story;
    LocalRequestGitHub(Path request, int epic) {
        super("local", "request", "GH_TOKEN", "gh", 1,
                (command, timeout) -> { throw new IllegalStateException("Local request forbids GitHub CLI"); },
                name -> null);
        this.request = request.toAbsolutePath().normalize();
        this.epic = epic;
        this.story = epic + 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request() {
        try {
            if (!Files.isRegularFile(request) || Files.isSymbolicLink(request) || Files.size(request) > 65536) {
                throw new IllegalArgumentException("Unsafe local request file");
            }
            Map<String, Object> value = new ObjectMapper().readValue(Files.readString(request), Map.class);
            if (Engine.asString(value.get("title")).isBlank() || !(value.get("criteria") instanceof List<?> criteria)
                    || criteria.isEmpty() || criteria.stream().anyMatch(c -> !(c instanceof Map<?, ?> m)
                    || Engine.asString(m.get("id")).isBlank() || Engine.asString(m.get("text")).isBlank())) {
                throw new IllegalArgumentException("Local request requires title and criteria");
            }
            return value;
        } catch (java.io.IOException exc) { throw new IllegalArgumentException("Cannot read local request", exc); }
    }

    @Override public Object issue(int number) {
        if (number == epic) return Map.of("number", epic, "title", "Local coding requests",
                "state", "OPEN", "body", "Local request #" + story,
                "story_numbers", List.of(story), "url", request.toUri().toString());
        if (number != story) throw new IllegalArgumentException("Unknown local story");
        Map<String, Object> value = request();
        String contract;
        try { contract = new ObjectMapper().writeValueAsString(Map.of(
                "intent", value.getOrDefault("intent", "implement_story"),
                "criteria", value.get("criteria"),
                "constraints", value.getOrDefault("constraints", List.of()),
                "requirements", value.getOrDefault("requirements", List.of()),
                "supersedes", value.getOrDefault("supersedes", List.of()))); }
        catch (java.io.IOException exc) { throw new IllegalArgumentException("Invalid local request", exc); }
        return Map.of("number", story, "title", value.get("title"), "state", "OPEN",
                "body", Engine.asString(value.getOrDefault("description", ""))
                        + "\n```jcoding-intent\n" + contract + "\n```",
                "labels", List.of(), "url", request.toUri().toString(),
                "source_revision", GoalContract.hashData(value),
                "author", Map.of("login", Engine.asString(value.getOrDefault("actor", "local"))));
    }
    @Override public Object prs() { return List.of(); }
    @Override public Object run(String... args) {
        throw new IllegalStateException("Local request forbids GitHub CLI");
    }
    @Override public List<Integer> childStoryNumbers(int ignored) { return List.of(story); }
    @Override public Object issueComments(int ignored) { return List.of(); }
}
