package com.jcoding.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bounded {@code codex exec --json} execution. Only completed agent messages
 * supply authoritative output; command failures are separate redacted evidence.
 * Timeout maps to 124, interruption to 130, and launch failure to 127.
 */
public class CodexRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexRunner.class);

    /**
     * Totals across completed turns. A counter is null if unavailable in any
     * completed turn (or no turn completed); a reported zero remains zero.
     */
    public record TokenUsage(Long inputTokens, Long cachedInputTokens, Long outputTokens) {
        public static TokenUsage unavailable() {
            return new TokenUsage(null, null, null);
        }
    }

    /**
     * Output is exclusively the final completed agent message, never tool text.
     * Diagnostics is redacted command failure evidence, capped at 16 KiB of
     * characters; truncation may cut the final JSON line. Never parse it as a footer.
     */
    public record CodexResult(int exitCode, String output, TokenUsage usage, String diagnostics) {
        public CodexResult(int exitCode, String output) {
            this(exitCode, output, TokenUsage.unavailable(), "");
        }

        public CodexResult(int exitCode, String output, TokenUsage usage) {
            this(exitCode, output, usage, "");
        }
    }

    private static final int MAX_EVENT_CHARS = 1_048_576;
    private static final int MAX_OUTPUT_CHARS = 262_144;
    private static final int MAX_DESCENDANTS = 4096;
    private static final int MAX_DIAGNOSTIC_CHARS = 16_384;

    private static final Pattern AUTH_BEARER = Pattern.compile(
            "(authorization\\s*:\\s*bearer\\s+)[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(\\b(?:gh_token|github_token|password|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s'\"]+",
            Pattern.CASE_INSENSITIVE);

    private final Supplier<Long> monotonicMillis;
    /**
     * Sensitive environment values to redact, precomputed once in the same
     * {@code entrySet()} iteration order the original per-call scan used, so
     * redaction output is byte-identical while avoiding a full environment scan
     * (and case-folding of every key) on every {@code redactSensitive} call.
     */
    private final List<String> sensitiveValues;

    public CodexRunner() {
        this(System.getenv(), System::nanoTime);
    }

    public CodexRunner(Map<String, String> environment, Supplier<Long> nanoTime) {
        this.monotonicMillis = () -> nanoTime.get() / 1_000_000L;
        this.sensitiveValues = computeSensitiveValues(environment);
    }

    /** Select the credential values to redact, preserving environment order. */
    private static List<String> computeSensitiveValues(Map<String, String> environment) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey().toUpperCase();
            String value = entry.getValue();
            boolean sensitive = value != null && !value.isEmpty()
                    && (name.endsWith("TOKEN") || name.endsWith("SECRET")
                        || name.endsWith("PASSWORD") || name.endsWith("API_KEY")
                        || name.equals("GH_TOKEN") || name.equals("GITHUB_TOKEN"));
            if (sensitive) {
                values.add(value);
            }
        }
        return values;
    }

    /** Redact known credential values before writing worker output to disk. */
    public String redactSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (String value : sensitiveValues) {
            redacted = redacted.replace(value, "[REDACTED]");
        }
        redacted = AUTH_BEARER.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1[REDACTED]");
        return redacted;
    }

    /** Build the codex command line from configuration (pure, testable). */
    public static List<String> buildCommand(Map<String, Object> codex, Path repo) {
        List<String> command = new ArrayList<>();
        command.add(Engine.asString(codex.getOrDefault("executable", "codex")));
        command.add("--cd");
        command.add(repo.toString());
        String model = Engine.asString(codex.get("model"));
        if (!model.isEmpty()) {
            command.add("--model");
            command.add(model);
        }
        String effort = Engine.asString(codex.get("reasoning_effort"));
        if (!effort.isEmpty()) {
            command.add("-c");
            command.add("model_reasoning_effort=" + effort);
        }
        String approvalPolicy = Engine.asString(codex.getOrDefault("approval_policy", "never"));
        boolean bypass = Engine.asBool(codex.get("dangerously_bypass_sandbox"), false);
        boolean readOnly = Engine.asBool(codex.get("diagnostic_read_only"), false);
        if (bypass && !readOnly) throw new IllegalArgumentException("Sandbox bypass is not supported for autonomous workers");
        command.addAll(List.of("--sandbox", readOnly ? "read-only" : "workspace-write"));
        // The outer bubblewrap shares networking only so the Codex process can
        // reach its model endpoint. Tool subprocesses get an explicit no-network
        // sandbox policy; do not inherit a permissive user-level Codex setting.
        if (!readOnly) command.addAll(List.of("-c", "sandbox_workspace_write.network_access=false"));
        command.addAll(List.of("--ask-for-approval", "never"));
        if ("bubblewrap".equals(codex.get("isolation"))) {
            command.addAll(List.of("-c", "model_provider=jcoding_scoped",
                    "-c", "model_providers.jcoding_scoped.name=Jcoding",
                    "-c", "model_providers.jcoding_scoped.base_url=http://127.0.0.1:18443/v1",
                    "-c", "model_providers.jcoding_scoped.env_key=JCODING_RUN_TOKEN",
                    "-c", "model_providers.jcoding_scoped.wire_api=responses",
                    "-c", "model_providers.jcoding_scoped.requires_openai_auth=false",
                    "-c", "model_providers.jcoding_scoped.request_max_retries=0",
                    "-c", "model_providers.jcoding_scoped.supports_websockets=false"));
        }
        command.add("exec");
        command.add("--json");
        command.add("-");
        return command;
    }

    /**
     * Resolve the effective {@code codex} settings for a phase by overlaying the
     * optional per-phase override on the base section. Backward-compatible: when
     * {@code phase} is null/blank or no {@code codex.phase_overrides.<PHASE>}
     * entry exists, the base section is returned unchanged. Only keys present in
     * the override replace the base value (partial overrides are supported), so a
     * phase may override just {@code reasoning_effort} and inherit {@code model}.
     * The {@code phase_overrides} key itself is never propagated into the command.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> effectiveCodexConfig(Map<String, Object> codex, String phase) {
        Map<String, Object> base = new java.util.LinkedHashMap<>(codex);
        base.remove("phase_overrides");
        if (phase == null || phase.isEmpty()) {
            return base;
        }
        Object overridesObj = codex.get("phase_overrides");
        if (!(overridesObj instanceof Map<?, ?> overrides)) {
            return base;
        }
        Object phaseOverrideObj = overrides.get(phase);
        if (!(phaseOverrideObj instanceof Map<?, ?> phaseOverride)) {
            return base;
        }
        for (Map.Entry<?, ?> e : phaseOverride.entrySet()) {
            base.put(String.valueOf(e.getKey()), e.getValue());
        }
        return base;
    }

    /**
     * Run the bounded Codex worker. Returns exit 0 and empty output in dry-run.
     * Streams and captures output, enforces a timeout (exit 124), and classifies
     * a missing executable (exit 127).
     */
    public CodexResult run(Map<String, Object> config, Path prompt, Path repo, boolean dryRun,
                           Runnable heartbeat, Integer timeoutSeconds) {
        @SuppressWarnings("unchecked")
        Map<String, Object> codex = (Map<String, Object>) config.get("codex");
        List<String> command = buildCommand(codex, repo);

        if (dryRun) {
            log.info("DRY RUN: Codex invocation suppressed");
            log.info("DRY RUN COMMAND: " + String.join(" ", command) + " < " + prompt);
            return new CodexResult(0, "");
        }

        log.info("CODEX START: model=" + Engine.asString(codex.getOrDefault("model", "default"))
                + " reasoning=" + Engine.asString(codex.getOrDefault("reasoning_effort", "default"))
                + " prompt=" + prompt);

        int timeout = timeoutSeconds != null ? timeoutSeconds
                : (int) Engine.asLongOrDefault(codex.get("execution_timeout_seconds"), 3600);
        int progressInterval = Math.max(5, (int) Engine.asLongOrDefault(
                codex.get("progress_interval_seconds"), 30));

        long deadline = monotonicMillis.get() + Math.max(0L, timeout) * 1000L;
        Process process;
        WorkerIsolation isolation = null;
        try {
            if ("bubblewrap".equals(codex.get("isolation"))) {
                isolation = WorkerIsolation.create(true);
                isolation.startGateway(codex, timeout);
                command = isolation.wrap(command, repo, codex, true, Boolean.TRUE.equals(codex.get("diagnostic_read_only")));
            }
            // A regular-file stdin cannot block this thread on a child that never reads.
            ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile()).redirectInput(prompt.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Map<String, String> cleanEnvironment = isolation == null
                    ? WorkerIsolation.environment(System.getenv(), true) : isolation.modelEnvironment();
            if (isolation == null) {
                // Native sandbox is available for local development; production uses an isolated home.
                cleanEnvironment.put("HOME", System.getProperty("user.home"));
                if (System.getenv("CODEX_HOME") != null) cleanEnvironment.put("CODEX_HOME", System.getenv("CODEX_HOME"));
            }
            builder.environment().clear();
            builder.environment().putAll(cleanEnvironment);
            process = builder.start();
        } catch (IOException | RuntimeException exc) {
            if (isolation != null) isolation.close();
            log.info("CODEX END: unable to start executable={}", command.get(0));
            return new CodexResult(127, "", TokenUsage.unavailable(), redactSensitive(exc.getMessage()));
        }

        EventReader events = new EventReader(process, this::redactSensitive);
        Thread reader = Thread.ofPlatform().daemon().name("codex-events-" + process.pid()).unstarted(events);
        Set<ProcessHandle> owned = new LinkedHashSet<>();
        try {
            reader.start();
            if (heartbeat != null) {
                heartbeat.run();
            }
            long lastHeartbeat = monotonicMillis.get();
            while (true) {
                rememberDescendants(process, owned);
                long now = monotonicMillis.get();
                if (!process.isAlive() && !reader.isAlive()) {
                    int code = process.exitValue();
                    return events.result(code);
                }
                if (now >= deadline) {
                    log.info("CODEX END: timeout");
                    return events.result(124);
                }
                if (heartbeat != null && now - lastHeartbeat >= (long) progressInterval * 1000L) {
                    heartbeat.run();
                    lastHeartbeat = now;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return events.result(130);
        } catch (RuntimeException failure) {
            if (failure instanceof ActionPolicy.Cancelled cancelled) throw cancelled;
            log.warn("CODEX END: runner failed while supervising process", failure);
            CodexResult partial = events.result(125);
            return new CodexResult(125, partial.output(), partial.usage(),
                    redactSensitive("Codex runner supervision failed: " + failure.getMessage()));
        } finally {
            cleanup(process, owned);
            if (isolation != null) isolation.close();
            // Never close a reader from the control thread: close can wait on its read lock.
            reader.interrupt();
        }
    }

    private static void rememberDescendants(Process process, Set<ProcessHandle> owned) {
        owned.removeIf(child -> !child.isAlive());
        try (var descendants = process.descendants()) {
            var iterator = descendants.iterator();
            while (iterator.hasNext()) {
                ProcessHandle child = iterator.next();
                if (owned.size() >= MAX_DESCENDANTS && !owned.contains(child)) {
                    throw new IllegalStateException("Codex descendant limit exceeded");
                }
                owned.add(child);
            }
        }
    }

    private static void cleanup(Process process, Set<ProcessHandle> owned) {
        boolean interrupted = Thread.interrupted();
        try {
            rememberDescendants(process, owned);
        } catch (RuntimeException failure) {
            log.warn("Unable to finish Codex descendant discovery", failure);
        }
        // Children first gives their still-running parent an opportunity to reap them.
        List<ProcessHandle> children = new ArrayList<>(owned);
        java.util.Collections.reverse(children);
        long reapDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        try {
            for (ProcessHandle child : children) {
                child.destroyForcibly();
                // Allow each parent to reap its child before killing that parent.
                long childDeadline = Math.min(reapDeadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
                while (child.isAlive() && System.nanoTime() < childDeadline) {
                    Thread.sleep(5);
                }
            }
            while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < reapDeadline) {
                Thread.sleep(10);
            }
            process.destroyForcibly();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                log.warn("Codex process did not terminate after forced cleanup: {}", process.pid());
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One bounded JSON line at a time; no queue and no captured command output. */
    private static final class EventReader implements Runnable {
        private final Process process;
        private final ObjectMapper mapper = new ObjectMapper();
        private final java.util.function.UnaryOperator<String> redact;
        private String output = "";
        private String diagnostics = "";
        private TokenUsage usage = TokenUsage.unavailable();
        private boolean completed;
        private boolean failed;

        private EventReader(Process process, java.util.function.UnaryOperator<String> redact) {
            this.process = process;
            this.redact = redact;
        }

        @Override
        public void run() {
            try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[8192];
                StringBuilder line = new StringBuilder();
                boolean oversized = false;
                int count;
                while ((count = reader.read(chunk)) != -1) {
                    for (int i = 0; i < count; i++) {
                        char ch = chunk[i];
                        if (ch == '\n') {
                            if (!oversized && !line.isEmpty()) {
                                accept(line.toString());
                            }
                            line.setLength(0);
                            oversized = false;
                        } else if (!oversized) {
                            if (line.length() == MAX_EVENT_CHARS) {
                                markFailed();
                                line.setLength(0);
                                oversized = true;
                            } else {
                                line.append(ch);
                            }
                        }
                    }
                }
                if (!oversized && !line.isEmpty()) {
                    accept(line.toString());
                }
            } catch (IOException failure) {
                markFailed();
            }
        }

        private synchronized void markFailed() {
            failed = true;
        }

        private synchronized void accept(String line) {
            try {
                JsonNode event = mapper.readTree(line);
                if (event == null || !event.isObject()) {
                    failed = true;
                    return;
                }
                switch (event.path("type").asText()) {
                    case "item.completed" -> {
                        JsonNode item = event.path("item");
                        if ("command_execution".equals(item.path("type").asText())
                                && (item.path("exit_code").asInt(0) != 0
                                    || "failed".equals(item.path("status").asText()))) {
                            commandFailure(item);
                        }
                        if ("agent_message".equals(item.path("type").asText())) {
                            JsonNode text = item.path("text");
                            if (!text.isTextual() || text.textValue().length() > MAX_OUTPUT_CHARS) {
                                output = "";
                                failed = true;
                            } else {
                                output = text.textValue();
                            }
                        }
                    }
                    case "turn.completed" -> {
                        JsonNode counters = event.path("usage");
                        usage = new TokenUsage(
                                add(usage.inputTokens(), counters.get("input_tokens")),
                                add(usage.cachedInputTokens(), counters.get("cached_input_tokens")),
                                add(usage.outputTokens(), counters.get("output_tokens")));
                        completed = true;
                    }
                    case "turn.failed", "error" -> {
                        failed = true;
                        String message = event.path("error").path("message")
                                .asText(event.path("message").asText());
                        diagnostics = bounded(diagnostics + redact.apply(message) + "\n",
                                MAX_DIAGNOSTIC_CHARS);
                    }
                    default -> { /* Other event types are deliberately not output. */ }
                }
            } catch (IOException | ArithmeticException invalid) {
                failed = true;
            }
        }

        private Long add(Long previous, JsonNode value) {
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                    || value.longValue() < 0 || (completed && previous == null)) {
                return null;
            }
            return Math.addExact(previous == null ? 0 : previous, value.longValue());
        }

        private void commandFailure(JsonNode item) throws IOException {
            // Redact before truncating so a boundary cannot expose a partial credential.
            String command = redact.apply(item.path("command").asText());
            String detail = redact.apply(item.path("aggregated_output").asText());
            var evidence = mapper.createObjectNode();
            evidence.put("command", bounded(command, 2048));
            evidence.set("exit_code", item.path("exit_code"));
            evidence.put("output", bounded(detail, 8192));
            String entry = mapper.writeValueAsString(evidence) + "\n";
            diagnostics = bounded(diagnostics + entry, MAX_DIAGNOSTIC_CHARS);
        }

        private static String bounded(String value, int limit) {
            return value.length() <= limit ? value : value.substring(0, limit - 14) + "...[truncated]";
        }

        private synchronized CodexResult result(int exitCode) {
            int code = exitCode == 0 && (failed || !completed) ? 1 : exitCode;
            return new CodexResult(code, output, usage, diagnostics);
        }
    }
}
