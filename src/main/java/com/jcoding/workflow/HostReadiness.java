package com.jcoding.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only checks of the host prerequisites used by unattended workers. */
final class HostReadiness {
    private HostReadiness() { }

    static List<String> diagnose(Map<String, Object> config) {
        List<String> gaps = new ArrayList<>();
        Map<String, Object> codex = Config.section(config, "codex");
        Map<String, Object> gateway = Config.section(codex, "gateway");
        String credential = Engine.asString(gateway.getOrDefault("credential_environment_variable", "OPENAI_API_KEY"));
        if (System.getenv(credential) == null || System.getenv(credential).isBlank()) {
            gaps.add("Provider credential is absent: " + credential);
        }
        String githubCredential = Engine.asString(Config.section(config, "github")
                .getOrDefault("token_environment_variable", "GH_TOKEN"));
        if (System.getenv(githubCredential) == null || System.getenv(githubCredential).isBlank()) {
            gaps.add("GitHub credential is absent: " + githubCredential);
        }
        for (String command : List.of("bwrap", "prlimit", "python3", Engine.asString(codex.get("executable")),
                Engine.asString(Config.section(config, "github").getOrDefault("cli", "gh")), "git")) {
            if (!executable(command)) gaps.add("Executable is unavailable: " + command);
        }
        Path repo = Path.of(Engine.asString(Config.section(config, "repository").get("local_path")));
        if (!Files.isDirectory(repo.resolve(".git")) && !Files.isRegularFile(repo.resolve(".git"))) {
            gaps.add("Target repository has no .git metadata: " + repo);
        }
        if (executable("bwrap") && executable("python3") && !privateNetworkWorks()) {
            gaps.add("Bubblewrap cannot create a private network namespace with working loopback");
        }
        return gaps;
    }

    private static boolean executable(String command) {
        if (command.isBlank()) return false;
        if (command.contains("/")) return Files.isExecutable(Path.of(command));
        for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
            if (Files.isExecutable(Path.of(dir, command))) return true;
        }
        return false;
    }

    /**
     * Probe command mirroring the real sandbox's system mounts. Binding only /usr is not enough
     * on merged-/usr distros (Ubuntu, Debian 12+): the dynamic loader lives at /lib64/..., so
     * python3 would fail to exec with a misleading "No such file or directory".
     */
    static List<String> privateNetworkProbeCommand(java.util.function.Predicate<String> exists) {
        List<String> command = new ArrayList<>(List.of("bwrap", "--die-with-parent", "--unshare-all"));
        for (String path : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64")) {
            if (exists.test(path)) command.addAll(List.of("--ro-bind", path, path));
        }
        command.addAll(List.of("--dev", "/dev", "--proc", "/proc", "--",
                "/usr/bin/python3", "-c", "import socket; s=socket.socket(); "
                        + "s.bind(('127.0.0.1',0)); s.listen(1); c=socket.socket(); "
                        + "c.connect(s.getsockname()); c.close(); s.close()"));
        return command;
    }

    private static boolean privateNetworkWorks() {
        // Probe the exact capability the sandbox bridge needs without contacting a network service.
        Process process = null;
        try {
            process = new ProcessBuilder(privateNetworkProbeCommand(path -> Files.exists(Path.of(path))))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exc) { return false; }
        catch (InterruptedException exc) { Thread.currentThread().interrupt(); return false; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
