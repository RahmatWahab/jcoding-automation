package com.rahmatwahab.codexautomation;

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

    /** Worker result: process exit code plus captured (unredacted) output. */
    public record CodexResult(int exitCode, String output) {
    }

    private static final Pattern AUTH_BEARER = Pattern.compile(
            "(authorization\\s*:\\s*bearer\\s+)[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(\\b(?:gh_token|github_token|password|secret|api[_-]?key)\\s*[=:]\\s*)[^\\s'\"]+",
            Pattern.CASE_INSENSITIVE);

    private final Map<String, String> environment;
    private final Supplier<Long> monotonicMillis;

    public CodexRunner() {
        this(System.getenv(), System::nanoTime);
    }

    public CodexRunner(Map<String, String> environment, Supplier<Long> nanoTime) {
        this.environment = environment;
        this.monotonicMillis = () -> nanoTime.get() / 1_000_000L;
    }

    /** Redact known credential values before writing worker output to disk. */
    public String redactSensitive(String text) {
        String redacted = text;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey().toUpperCase();
            String value = entry.getValue();
            boolean sensitive = value != null && !value.isEmpty()
                    && (name.endsWith("TOKEN") || name.endsWith("SECRET")
                        || name.endsWith("PASSWORD") || name.endsWith("API_KEY")
                        || name.equals("GH_TOKEN") || name.equals("GITHUB_TOKEN"));
            if (sensitive) {
                redacted = redacted.replace(value, "[REDACTED]");
            }
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
            System.out.println("DRY RUN: Codex invocation suppressed");
            System.out.println("DRY RUN COMMAND: " + String.join(" ", command) + " < " + prompt);
            return new CodexResult(0, "");
        }

        System.out.println("CODEX START: model=" + Engine.asString(codex.getOrDefault("model", "default"))
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
            System.out.println("CODEX END: unavailable executable=" + command.get(0));
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
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                if (heartbeat != null) {
                    heartbeat.run();
                }
                long nowMillis = monotonicMillis.get();
                if (nowMillis - lastProgress >= (long) progressInterval * 1000L) {
                    System.out.println("CODEX HEARTBEAT: pid=" + process.pid()
                            + " elapsed_seconds=" + ((nowMillis - (deadline - (long) timeout * 1000L)) / 1000L));
                    lastProgress = nowMillis;
                }
                if (nowMillis >= deadline) {
                    process.destroyForcibly();
                    String remainder = drain(reader);
                    output.append(remainder);
                    System.out.println("CODEX END: timeout");
                    return new CodexResult(124, output + "\nCodex execution timed out\n");
                }
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        output.append(line).append('\n');
                        System.out.print(redactSensitive(line + "\n"));
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
        }
        waitQuietly(process);
        int code = process.exitValue();
        System.out.println("CODEX END: exit_code=" + code);
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
