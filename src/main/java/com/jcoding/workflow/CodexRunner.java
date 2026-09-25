package com.jcoding.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded {@code codex exec} worker execution and output redaction.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code redact_sensitive}). Command construction and redaction are pure and
 * unit-tested directly. Execution streams worker output with a phase timeout;
 * because process-group signalling is not portable, abnormal
 * termination is reproduced behaviorally: timeout maps to exit 124, a missing
 * executable to 127, and a broken input pipe to a bounded failure — preserving
 * the controller's classification contract.</p>
 */
public class CodexRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexRunner.class);

    /** Worker result: process exit code plus captured (unredacted) output. */
    public record CodexResult(int exitCode, String output) {
    }

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
        if (approvalPolicy.equals("never") && !bypass) {
            command.add("--approve-for-me");
        }
        command.add("exec");
        if (bypass) {
            command.add("--dangerously-bypass-approvals-and-sandbox");
        }
        command.add("-");
        return command;
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

        StringBuilder output = new StringBuilder();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(repo.toFile())
                    .redirectErrorStream(true);
            process = builder.start();
        } catch (IOException exc) {
            log.info("CODEX END: unavailable executable=" + command.get(0));
            return new CodexResult(127, "Codex executable unavailable: " + command.get(0) + "\n");
        }

        try {
            byte[] promptBytes = Files.readString(prompt).getBytes(StandardCharsets.UTF_8);
            process.getOutputStream().write(promptBytes);
            process.getOutputStream().close();
        } catch (IOException brokenPipe) {
            process.destroyForcibly();
            waitQuietly(process);
            return new CodexResult(1, "Codex exited before accepting the prompt\n");
        }

        long deadline = monotonicMillis.get() + (long) timeout * 1000L;
        long lastProgress = monotonicMillis.get();
        // Persist the durable heartbeat at most once per progress interval. The
        // read loop spins every ~50 ms; calling the heartbeat (which writes the
        // state file) on every iteration produced hundreds of redundant state
        // writes per phase. Interval-gating keeps the liveness signal fresh for
        // the stale-heartbeat monitor without the per-loop I/O churn.
        long lastHeartbeat;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            // Initial heartbeat is inside the protected region so a lost-lock at
            // the first beat also cleans up the child process (see catch below).
            if (heartbeat != null) {
                heartbeat.run();
            }
            lastHeartbeat = monotonicMillis.get();
            while (true) {
                long nowMillis = monotonicMillis.get();
                if (heartbeat != null
                        && nowMillis - lastHeartbeat >= (long) progressInterval * 1000L) {
                    heartbeat.run();
                    lastHeartbeat = nowMillis;
                }
                if (nowMillis - lastProgress >= (long) progressInterval * 1000L) {
                    log.debug("CODEX HEARTBEAT: pid=" + process.pid()
                            + " elapsed_seconds=" + ((nowMillis - (deadline - (long) timeout * 1000L)) / 1000L));
                    lastProgress = nowMillis;
                }
                if (nowMillis >= deadline) {
                    process.destroyForcibly();
                    String remainder = drain(reader);
                    output.append(remainder);
                    log.info("CODEX END: timeout");
                    return new CodexResult(124, output + "\nCodex execution timed out\n");
                }
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        output.append(line).append('\n');
                        // Worker stdout is captured in `output` (and journaled via
                        // the phase result tail). Mirroring every line to the
                        // console previously flooded the cron log with full EPIC
                        // and PR/JSON payloads (hundreds of MB/day). Route it to
                        // the logger at DEBUG so `--verbose` still surfaces the
                        // live worker stream without bloating the default log.
                        if (log.isDebugEnabled()) {
                            log.debug("CODEX OUT: {}", redactSensitive(line));
                        }
                    }
                } else if (!process.isAlive()) {
                    output.append(drain(reader));
                    break;
                } else {
                    sleepQuietly(50);
                }
            }
        } catch (IOException exc) {
            // Stream closed; fall through to exit-code reporting.
        } catch (RuntimeException abort) {
            // The read loop was abandoned by a non-I/O runtime failure — most
            // importantly a LockOwnershipLost thrown by the heartbeat when this
            // run lost the lock mid-phase. Never orphan the child: force-destroy
            // it and reap it, then rethrow so the controller ends the run
            // cleanly (the takeover process is now the authority).
            process.destroyForcibly();
            waitQuietly(process);
            throw abort;
        }
        waitQuietly(process);
        int code = process.exitValue();
        log.info("CODEX END: exit_code=" + code);
        return new CodexResult(code, output.toString());
    }

    private static String drain(BufferedReader reader) {
        StringBuilder remainder = new StringBuilder();
        try {
            String line;
            while (reader.ready() && (line = reader.readLine()) != null) {
                remainder.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // best effort
        }
        return remainder.toString();
    }

    private static void waitQuietly(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }
}
