package com.jcoding.workflow;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded output, deadline and descendant cleanup for non-model commands. */
public final class BoundedProcess {
    private BoundedProcess() { }
    public record Result(int exitCode, String output, boolean truncated) { }

    public static Result run(List<String> command, Path cwd, Map<String, String> environment,
                              int seconds, Runnable heartbeat) {
        if (command.isEmpty() || seconds < 1) throw new IllegalArgumentException("Command and positive timeout required");
        Process process = null;
        java.util.Set<ProcessHandle> children = new java.util.LinkedHashSet<>();
        var bytes = new ByteArrayOutputStream();
        var truncated = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            if (environment != null) { builder.environment().clear(); builder.environment().putAll(environment); }
            process = builder.start();
            Process child = process;
            reader = Thread.ofPlatform().daemon().start(() -> {
                try (var input = child.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = input.read(buffer)) >= 0;) {
                        synchronized (bytes) {
                            int keep = Math.min(n, Math.max(0, 8 * 1024 * 1024 - bytes.size()));
                            bytes.write(buffer, 0, keep);
                            if (keep < n) truncated.set(true);
                        }
                    }
                } catch (java.io.IOException ignored) { }
            });
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            int exit = 124;
            while (System.nanoTime() < deadline) {
                if (heartbeat != null) heartbeat.run();
                try (var descendants = process.descendants()) { descendants.forEach(children::add); }
                if (children.size() > 4096) throw new IllegalStateException("Process descendant limit exceeded");
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) { exit = process.exitValue(); break; }
            }
            reader.join(1000);
            synchronized (bytes) { return new Result(exit, bytes.toString(StandardCharsets.UTF_8), truncated.get()); }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new ActionPolicy.Cancelled();
        } catch (java.io.IOException exc) {
            throw new IllegalStateException("Unable to start command: " + command.getFirst(), exc);
        } finally {
            if (process != null) {
                try (var descendants = process.descendants()) { descendants.forEach(children::add); }
                children.forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
            }
            if (reader != null) reader.interrupt();
        }
    }
}
