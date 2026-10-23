package com.jcoding.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal bubblewrap filesystem for production workers and executable verification. */
final class WorkerIsolation implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkerIsolation.class);
    private final Path home;
    private Process gateway;
    private String runToken;
    private boolean loginMode;
    private Path loginSource;
    private byte[] loginOriginal;
    private WorkerIsolation(Path home) { this.home = home; }

    /** Throwaway worker home; exposed for tests. */
    Path homeDirectory() { return home; }

    /**
     * Opt-in codex_login mode: place a copy of the operator's Codex login in the worker's
     * isolated CODEX_HOME. No gateway or provider override is used; the sandbox shares the host
     * network so Codex can reach its model endpoint. Callers must have accepted this trade-off.
     */
    void useCodexLogin() throws java.io.IOException {
        Path source = CodexLogin.authFile();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Codex login not found: " + source + " (run 'codex login' as the controller user)");
        }
        byte[] original = Files.readAllBytes(source);
        if (!CodexLogin.plausible(original)) throw new IllegalArgumentException("Codex login file is invalid: " + source);
        Path target = home.resolve(".codex").resolve("auth.json");
        Files.write(target, original);
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        loginMode = true;
        loginSource = source;
        loginOriginal = original;
    }

    /**
     * Codex refreshes its token inside the worker's throwaway home. Persist a refreshed login back
     * so the host login keeps working, but only if the host copy was not changed meanwhile.
     */
    private void persistRefreshedLogin() {
        if (!loginMode || loginSource == null) return;
        try {
            Path current = home.resolve(".codex").resolve("auth.json");
            if (!Files.isRegularFile(current)) return;
            byte[] updated = Files.readAllBytes(current);
            if (java.util.Arrays.equals(updated, loginOriginal) || !CodexLogin.plausible(updated)) return;
            if (!java.util.Arrays.equals(Files.readAllBytes(loginSource), loginOriginal)) {
                LOG.warn("CODEX LOGIN: host login changed during the run; not overwriting it with the worker's refreshed copy");
                return;
            }
            Path temporary = Files.createTempFile(loginSource.getParent(), ".auth-", ".tmp");
            try {
                Files.write(temporary, updated);
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.move(temporary, loginSource, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            LOG.info("CODEX LOGIN: refreshed login persisted to the host login file");
        } catch (java.io.IOException | RuntimeException exc) {
            LOG.warn("CODEX LOGIN: could not persist the refreshed login ({}); re-run 'codex login' if it expires",
                    exc.getClass().getSimpleName());
        }
    }

    static WorkerIsolation create(boolean model) throws java.io.IOException {
        Path home = Files.createTempDirectory("jcoding-worker-");
        Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(home.resolve(".codex"));
        return new WorkerIsolation(home);
    }

    static Map<String, String> environment(Map<String, String> source, boolean model) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (String name : List.of("PATH", "LANG", "LC_ALL", "TZ", "JAVA_HOME")) {
            if (source.containsKey(name)) clean.put(name, source.get(name));
        }

        clean.put("GIT_TERMINAL_PROMPT", "0");
        clean.put("GIT_CONFIG_NOSYSTEM", "1");
        return clean;
    }

    List<String> wrap(List<String> command, Path repo, Map<String, Object> options, boolean model, boolean readOnly) {
        List<String> args = new ArrayList<>(List.of("bwrap", "--die-with-parent", "--new-session",
                "--unshare-all"));
        // By default every worker has a private network namespace and model traffic crosses only
        // the scoped Unix-socket gateway. In opt-in codex_login mode the model worker shares the
        // host network so the Codex CLI can reach its endpoint directly (tool commands stay
        // network-less through Codex's own sandbox policy).
        if (model && loginMode) args.add("--share-net");
        for (String path : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc/ssl", "/etc/resolv.conf",
                "/etc/hosts", "/etc/passwd", "/etc/group", "/etc/nsswitch.conf")) {
            if (Files.exists(Path.of(path))) args.addAll(List.of("--ro-bind", path, path));
        }
        for (Object raw : Engine.asList(options.get("runtime_read_paths"))) {
            Path path = runtimePath(raw.toString());
            args.addAll(List.of("--ro-bind", path.toString(), path.toString()));
        }
        if (model) {
            Path executable = resolveExecutable(command.getFirst());
            args.addAll(List.of("--ro-bind", executable.getParent().toString(), executable.getParent().toString()));
            command = new ArrayList<>(command);
            command.set(0, executable.toString());
        }
        String root = repo.toAbsolutePath().normalize().toString();
        args.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
                "--bind", home.toString(), "/home/worker",
                readOnly ? "--ro-bind" : "--bind", root, root));
        Path git = repo.resolve(".git");
        if (Files.exists(git)) args.addAll(List.of("--ro-bind", git.toString(), git.toString()));
        args.addAll(List.of("--setenv", "HOME", "/home/worker", "--setenv", "CODEX_HOME", "/home/worker/.codex",
                "--chdir", root, "--"));
        if (model && !loginMode) {
            if (gateway == null || !gateway.isAlive()) throw new IllegalStateException("Model gateway is not running");
            args.addAll(List.of("/usr/bin/python3", "/home/worker/model_bridge.py"));
        }
        args.addAll(command);
        { // Apply resource limits to verifiers as well as model workers.
            Map<?, ?> limits = options.get("resource_limits") instanceof Map<?, ?> configured
                    ? configured : Map.of();
            long addressSpace = positiveLimit(limits, "address_space_bytes", 8L * 1024 * 1024 * 1024);
            long cpuSeconds = positiveLimit(limits, "cpu_seconds", 14_400);
            long fileSize = positiveLimit(limits, "file_size_bytes", 2L * 1024 * 1024 * 1024);
            Path prlimit = resolveExecutable("prlimit");
            List<String> limited = new ArrayList<>(List.of(prlimit.toString(), "--as=" + addressSpace,
                    "--cpu=" + cpuSeconds, "--fsize=" + fileSize, "--"));
            limited.addAll(args);
            return limited;
        }
    }

    private static Path runtimePath(String raw) {
        Path path;
        try { path = Path.of(raw).toRealPath(); }
        catch (java.io.IOException exc) { throw new IllegalArgumentException("Missing runtime mount", exc); }
        if (Path.of(System.getProperty("user.home")).startsWith(path)
                || path.toString().matches(".*/[.](codex|ssh|aws)(/.*)?")) {
            throw new IllegalArgumentException("Runtime mounts must not expose root or the controller home");
        }
        return path;
    }

    private static long positiveLimit(Map<?, ?> limits, String key, long fallback) {
        Object value = limits.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() <= 0 || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("codex.resource_limits." + key + " must be a positive integer");
        }
        return number.longValue();
    }

    private static Path resolveExecutable(String command) {
        try {
            if (command.contains("/")) return Path.of(command).toRealPath();
            for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
                Path candidate = Path.of(dir, command);
                if (Files.isExecutable(candidate)) return candidate.toRealPath();
            }
        } catch (java.io.IOException exc) { throw new IllegalStateException("Unable to resolve worker executable", exc); }
        throw new IllegalStateException("Worker executable is not available: " + command);
    }

    /** Short-lived run token is the only credential passed to the sandbox (gateway mode). */
    Map<String, String> modelEnvironment() {
        Map<String, String> values = environment(System.getenv(), false);
        if (!loginMode) values.put("JCODING_RUN_TOKEN", runToken);
        return values;
    }

    void startGateway(Map<String, Object> options, int timeoutSeconds) throws java.io.IOException {
        Map<String, Object> policy = Config.section(options, "gateway");
        String secretName = Engine.asString(policy.getOrDefault("credential_environment_variable", "OPENAI_API_KEY"));
        String secret = System.getenv(secretName);
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Missing controller provider credential: " + secretName);
        String model = Engine.asString(options.get("model"));
        if (model.isBlank()) throw new IllegalArgumentException("Gateway requires an explicit model");
        runToken = java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();
        for (String resource : List.of("model_gateway.py", "model_bridge.py")) {
            try (var input = WorkerIsolation.class.getResourceAsStream("/isolation/" + resource)) {
                if (input == null) throw new java.io.IOException("Missing packaged isolation resource");
                Files.copy(input, home.resolve(resource));
            }
        }
        Map<String, String> env = environment(System.getenv(), false);
        env.put("JCODING_PROVIDER_SECRET", secret);
        env.put("JCODING_GATEWAY_TOKEN", runToken);
        env.put("JCODING_GATEWAY_MODEL", model);
        env.put("JCODING_GATEWAY_UPSTREAM", Engine.asString(policy.getOrDefault("base_url", "https://api.openai.com/v1")));
        env.put("JCODING_GATEWAY_HOSTS", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                policy.getOrDefault("allowed_hosts", List.of("api.openai.com"))));
        env.put("JCODING_GATEWAY_TTL", String.valueOf(Math.max(1, timeoutSeconds)));
        env.put("JCODING_GATEWAY_REQUESTS", String.valueOf(positiveLimit(policy, "max_requests", 128)));
        env.put("JCODING_GATEWAY_OUTPUT", String.valueOf(positiveLimit(policy, "max_output_tokens", 16384)));
        env.put("JCODING_GATEWAY_SOCKET", home.resolve("provider.sock").toString());
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/python3", home.resolve("model_gateway.py").toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().putAll(env);
        gateway = builder.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!Files.exists(home.resolve("provider.sock")) && gateway.isAlive() && System.nanoTime() < deadline) {
            try { Thread.sleep(20); }
            catch (InterruptedException exc) { Thread.currentThread().interrupt(); throw new java.io.IOException("Gateway start interrupted", exc); }
        }
        if (!gateway.isAlive() || !Files.exists(home.resolve("provider.sock"))) {
            throw new java.io.IOException("Scoped model gateway failed to initialize; check provider configuration");
        }
    }

    @Override public void close() {
        persistRefreshedLogin();
        if (gateway != null) {
            gateway.destroyForcibly();
            try { gateway.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exc) { Thread.currentThread().interrupt(); }
        }
        runToken = null;
        try (var paths = Files.walk(home)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
            });
        } catch (java.io.IOException ignored) { }
    }
}
