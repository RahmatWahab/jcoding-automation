package com.jcoding.workflow;

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
 * {@code workflow.auto_merge} merge policy is validated (default false =
 * manual merge; true = fully autonomous merge).</p>
 */
public final class Config {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private static final Set<String> VALID_EFFORT = Set.of("low", "medium", "high", "xhigh", "none");
    private static final Set<String> KNOWN_BUDGET_PHASES =
            Set.of("planning", "implementing", "verifying", "reviewing_code", "fixing_feedback", "reconciling");
    private static final List<String> MUTATION_FLAGS = List.of(
            "allow_push", "allow_pr_creation", "allow_comments",
            "allow_thread_resolution", "auto_merge");

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

        // Per-phase codex overrides (optional). Keys must be known workflow
        // phases; each value must be an object; a nested reasoning_effort (if
        // present) must be a valid effort. Model is a free string (not enumerable
        // here). Backward-compatible: absent phase_overrides skips all checks.
        Object phaseOverridesObj = codex.get("phase_overrides");
        if (phaseOverridesObj != null) {
            if (!(phaseOverridesObj instanceof Map<?, ?> phaseOverrides)) {
                missing.add("codex.phase_overrides(object)");
            } else {
                for (Map.Entry<?, ?> entry : phaseOverrides.entrySet()) {
                    String phase = String.valueOf(entry.getKey());
                    if (!WorkflowMachine.KNOWN_PHASES.contains(phase)) {
                        missing.add("codex.phase_overrides." + phase + "(unknown phase)");
                        continue;
                    }
                    if (!(entry.getValue() instanceof Map<?, ?> override)) {
                        missing.add("codex.phase_overrides." + phase + "(object)");
                        continue;
                    }
                    Object phaseEffort = override.get("reasoning_effort");
                    if (phaseEffort != null && !VALID_EFFORT.contains(phaseEffort)) {
                        missing.add("codex.phase_overrides." + phase + ".reasoning_effort(valid value)");
                    }
                }
            }
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
                "max_feedback_attempts", "max_feedback_batch_size", "max_resume_attempts",
                "resume_loop_multiplier")) {
            if (automation.containsKey(key) && !isPositiveInt(automation.get(key))) {
                missing.add("automation." + key + "(positive integer)");
            }
        }
        // wait_poll_seconds is a backoff cadence where 0 disables the feature,
        // so it must permit zero (non-negative) rather than strictly positive.
        if (automation.containsKey("wait_poll_seconds")
                && !isNonNegativeInt(automation.get("wait_poll_seconds"))) {
            missing.add("automation.wait_poll_seconds(non-negative integer)");
        }

        if (codex.containsKey("dangerously_bypass_sandbox")
                && !(codex.get("dangerously_bypass_sandbox") instanceof Boolean)) {
            missing.add("codex.dangerously_bypass_sandbox(boolean)");
        }
        if (codex.containsKey("resource_limits")) {
            if (!(codex.get("resource_limits") instanceof Map<?, ?> limits)) {
                missing.add("codex.resource_limits(object)");
            } else {
                for (String key : List.of("address_space_bytes", "cpu_seconds", "file_size_bytes")) {
                    if (limits.containsKey(key) && !isPositiveInt(limits.get(key))) {
                        missing.add("codex.resource_limits." + key + "(positive integer)");
                    }
                }
            }
        }

        for (String key : MUTATION_FLAGS) {
            if (workflow.containsKey(key) && !(workflow.get(key) instanceof Boolean)) {
                missing.add("workflow." + key + "(boolean)");
            }
        }

        // Merge policy is governed by the single boolean workflow.auto_merge.
        // Default false = manual merge (the automation posts READY TO MERGE and
        // never merges). true = fully autonomous merge by the controller. The
        // boolean type is validated with the other mutation flags above; no
        // further merge-policy checks are needed.

        if (Boolean.TRUE.equals(codex.get("dangerously_bypass_sandbox"))) missing.add("codex.dangerously_bypass_sandbox(must be false)");
        if (!Set.of("native", "bubblewrap").contains(codex.getOrDefault("isolation", "native"))) missing.add("codex.isolation(native|bubblewrap)");
        if (codex.containsKey("approval_policy") && !"never".equals(codex.get("approval_policy"))) missing.add("codex.approval_policy(never)");
        for (String key : List.of("max_story_worker_calls", "max_story_active_seconds", "max_wait_diagnoses", "wait_timeout_seconds",
                "max_preflight_diagnoses", "preflight_diagnosis_seconds", "max_delivery_recovery_attempts")) {
            if (automation.containsKey(key) && !isPositiveInt(automation.get(key))) missing.add("automation." + key + "(positive integer)");
        }
        for (String key : List.of("max_story_tokens", "recovery_epoch")) {
            if (automation.containsKey(key) && !isNonNegativeInt(automation.get(key))) missing.add("automation." + key + "(non-negative integer)");
        }
        Map<String, Object> verification = section(config, "verification");
        Map<String, Object> localDelivery = section(config, "local_delivery");
        if (!"artifact".equals(localDelivery.getOrDefault("mode", "artifact")))
            missing.add("local_delivery.mode(artifact)");
        if (verification.containsKey("required") && !(verification.get("required") instanceof Boolean)) missing.add("verification.required(boolean)");
        if (verification.containsKey("max_changed_files") && !isPositiveInt(verification.get("max_changed_files"))) missing.add("verification.max_changed_files(positive integer)");
        Set<String> checkIds = new java.util.HashSet<>();
        for (Object value : Engine.asList(verification.get("checks"))) {
            if (!(value instanceof Map<?, ?> check)) { missing.add("verification.checks(object array)"); continue; }
            String id = Engine.asString(check.get("id"));
            if (id.isBlank() || !checkIds.add(id)) missing.add("verification.checks(unique nonempty id)");
            List<Object> command = Engine.asList(check.get("command"));
            if (command.isEmpty() || command.stream().anyMatch(arg -> !(arg instanceof String str) || str.isBlank())) missing.add("verification.checks.command(nonempty string array)");
            if (check.containsKey("timeout_seconds") && !isPositiveInt(check.get("timeout_seconds"))) missing.add("verification.checks.timeout_seconds(positive integer)");
        }
        for (String key : List.of("allowed_paths", "protected_paths")) {
            for (Object value : Engine.asList(verification.get(key))) {
                try {
                    String glob = String.valueOf(value);
                    if (glob.startsWith("/") || glob.contains("..")) throw new IllegalArgumentException();
                    java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);
                } catch (RuntimeException exc) { missing.add("verification." + key + "(relative glob)"); }
            }
        }
        if (Boolean.TRUE.equals(workflow.get("auto_merge")) && !"bubblewrap".equals(codex.get("isolation"))) {
            missing.add("codex.isolation(bubblewrap required for automatic merge)");
        }

        Map<String, Object> intent = section(config, "intent");
        if (intent.containsKey("default_intent") && IntentRegistry.find(Engine.asString(intent.get("default_intent"))) == null) {
            missing.add("intent.default_intent(registered capability)");
        }
        if (intent.containsKey("require_acceptance_criteria") && !(intent.get("require_acceptance_criteria") instanceof Boolean)) {
            missing.add("intent.require_acceptance_criteria(boolean)");
        }
        if (intent.containsKey("delegated_responders") && (!(intent.get("delegated_responders") instanceof List<?> delegates)
                || delegates.size() > 20 || delegates.stream().anyMatch(value -> !(value instanceof String login)
                || !login.matches("[A-Za-z0-9-]{1,39}")))) {
            missing.add("intent.delegated_responders(GitHub login array)");
        }
        if (automation.containsKey("max_task_attempts") && !isPositiveInt(automation.get("max_task_attempts"))) missing.add("automation.max_task_attempts(positive integer)");
        if (automation.containsKey("max_plan_revisions") && !isPositiveInt(automation.get("max_plan_revisions"))) missing.add("automation.max_plan_revisions(positive integer)");
        validateDeployment(config, missing);

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("invalid configuration: " + String.join(", ", missing));
        }
    }

    private static void validateDeployment(Map<String, Object> config, List<String> missing) {
        Map<String, Object> deployment = section(config, "deployment");
        if (!Set.of("development", "canary", "autonomous").contains(deployment.getOrDefault("mode", "development"))) missing.add("deployment.mode(development|canary|autonomous)");
        for (String key : List.of("canary_story", "canary_valid_hours", "provider_canary_valid_hours")) {
            if (deployment.containsKey(key) && !isPositiveInt(deployment.get(key))) missing.add("deployment." + key + "(positive integer)");
        }
        Map<String, Object> gateway = section(section(config, "codex"), "gateway");
        for (String key : List.of("max_requests", "max_output_tokens")) {
            if (gateway.containsKey(key) && !isPositiveInt(gateway.get(key))) missing.add("codex.gateway." + key + "(positive integer)");
        }
        validateGatewayOrigin(gateway, missing);
        String credential = Engine.asString(gateway.getOrDefault("credential_environment_variable", "OPENAI_API_KEY"));
        if (!credential.matches("[A-Z_][A-Z0-9_]*")) missing.add("codex.gateway.credential_environment_variable(environment name)");
    }

    private static void validateGatewayOrigin(Map<String, Object> gateway, List<String> missing) {
        try {
            java.net.URI uri = java.net.URI.create(Engine.asString(gateway.getOrDefault("base_url", "https://api.openai.com/v1")));
            List<Object> hosts = Engine.asList(gateway.getOrDefault("allowed_hosts", List.of("api.openai.com")));
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !hosts.contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || hosts.stream().anyMatch(h -> !(h instanceof String str) || str.contains("*") || str.isBlank())) {
                missing.add("codex.gateway(HTTPS origin with exact allowed host required)");
            }
        } catch (RuntimeException exc) { missing.add("codex.gateway.base_url(valid HTTPS URL)"); }
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

    /** Integer-typed and >= 0. Used for knobs where zero is a valid "disabled" value. */
    static boolean isNonNegativeInt(Object value) {
        return isInt(value) && ((Number) value).longValue() >= 0;
    }

    /** True only for integral numeric types (booleans are excluded). */
    static boolean isInt(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short;
    }
}
