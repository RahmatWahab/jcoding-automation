package com.rahmatwahab.codexautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration loading and validation.
 *
 * <p>Configuration loading and validation
 * Loads and validates the JSON configuration. The
 * keys, defaults, and validation
 * rules are preserved exactly so behavior parity holds. The mandatory
 * {@code workflow.require_manual_merge=true} safety invariant is enforced.</p>
 */
public final class Config {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private static final Set<String> VALID_EFFORT = Set.of("low", "medium", "high", "xhigh", "none");
    private static final Set<String> KNOWN_BUDGET_PHASES =
            Set.of("implementing", "verifying", "reviewing_code", "fixing_feedback", "reconciling");
    private static final List<String> MUTATION_FLAGS = List.of(
            "allow_push", "allow_pr_creation", "allow_comments",
            "allow_thread_resolution", "require_manual_merge");

    private Config() {
    }

    /** Load a JSON configuration file into a nested map. */
    public static Map<String, Object> load(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path), MAP_TYPE);
    }

    /**
     * Validate configuration, throwing {@link IllegalArgumentException} that
     * lists every offending key. Mirrors {@code validate_config} exactly,
     * including default handling and the mandatory manual-merge gate.
     */
    public static void validate(Map<String, Object> config) {
        Map<String, Object> repository = section(config, "repository");
        Map<String, Object> epic = section(config, "epic");
        Map<String, Object> codex = section(config, "codex");
        Map<String, Object> workflow = section(config, "workflow");
        Map<String, Object> automation = section(config, "automation");
        Map<String, Object> github = section(config, "github");
        Map<String, Object> budgets = section(config, "budgets");

        List<String> missing = new ArrayList<>();

        Map<String, Object> required = new LinkedHashMap<>();
        required.put("repository.local_path", repository.get("local_path"));
        required.put("repository.owner", repository.get("owner"));
        required.put("repository.name", repository.get("name"));
        required.put("epic.number", epic.get("number"));
        required.put("codex.executable", codex.get("executable"));
        required.put("codex.model", codex.get("model"));
        for (Map.Entry<String, Object> entry : required.entrySet()) {
            Object value = entry.getValue();
            if (value == null || "".equals(value)) {
                missing.add(entry.getKey());
            }
        }

        Object effort = codex.getOrDefault("reasoning_effort", "medium");
        if (!VALID_EFFORT.contains(effort)) {
            missing.add("codex.reasoning_effort(valid value)");
        }

        Object timeout = codex.getOrDefault("execution_timeout_seconds", 3600);
        if (!isPositiveInt(timeout)) {
            missing.add("codex.execution_timeout_seconds(positive integer)");
        }
        Object progressInterval = codex.getOrDefault("progress_interval_seconds", 30);
        if (!isPositiveInt(progressInterval)) {
            missing.add("codex.progress_interval_seconds(positive integer)");
        }
        Object phaseTimeout = codex.getOrDefault("phase_timeout_seconds", timeout);
        if (!isPositiveInt(phaseTimeout)) {
            missing.add("codex.phase_timeout_seconds(positive integer)");
        }

        for (Map.Entry<String, Object> entry : budgets.entrySet()) {
            String phase = entry.getKey();
            if (!KNOWN_BUDGET_PHASES.contains(phase)) {
                missing.add("budgets." + phase + "(unknown phase)");
            } else if (!isPositiveInt(entry.getValue())) {
                missing.add("budgets." + phase + "(positive integer)");
            }
        }

        if (!isInt(epic.get("number"))) {
            missing.add("epic.number(integer)");
        }

        Object ghTimeout = github.getOrDefault("timeout_seconds", 30);
        if (!isPositiveInt(ghTimeout)) {
            missing.add("github.timeout_seconds(positive integer)");
        }

        for (String key : List.of("stale_lock_seconds", "watchdog_observations", "watchdog_grace_seconds",
                "max_feedback_attempts")) {
            if (automation.containsKey(key) && !isPositiveInt(automation.get(key))) {
                missing.add("automation." + key + "(positive integer)");
            }
        }

        if (codex.containsKey("dangerously_bypass_sandbox")
                && !(codex.get("dangerously_bypass_sandbox") instanceof Boolean)) {
            missing.add("codex.dangerously_bypass_sandbox(boolean)");
        }

        for (String key : MUTATION_FLAGS) {
            if (workflow.containsKey(key) && !(workflow.get(key) instanceof Boolean)) {
                missing.add("workflow." + key + "(boolean)");
            }
        }

        Object manualMerge = workflow.getOrDefault("require_manual_merge", Boolean.TRUE);
        if (!Boolean.TRUE.equals(manualMerge)) {
            missing.add("workflow.require_manual_merge(must be true)");
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("invalid configuration: " + String.join(", ", missing));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** Integer-typed and > 0. JSON numbers arrive as Integer/Long; reject booleans and floats. */
    static boolean isPositiveInt(Object value) {
        return isInt(value) && ((Number) value).longValue() > 0;
    }

    /** True only for integral numeric types (booleans are excluded). */
    static boolean isInt(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short;
    }
}
