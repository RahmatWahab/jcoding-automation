package com.rahmatwahab.codexautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level workflow orchestration.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code now}, {@code due}, {@code read_job_control}). The controller owns
 * scheduling, locking, GitHub reconciliation, phase selection, and the manual-
 * merge gate, composing the focused collaborators ({@link Config},
 * {@link WorkflowLock}, {@link StateStore}, {@link GitHub}, {@link GitOperations},
 * {@link CodexRunner}, {@link ReviewCoordinator}, {@link StateRecovery},
 * {@link PromptRenderer}). It composes the workflow collaborators and is
 * preserved; the job-control gate is JSON per the migration decision.</p>
 */
public final class Controller {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private Controller() {
    }

    /** Current UTC timestamp in ISO-8601, the standard controller timestamp. */
    public static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    /** Whether a retry timestamp is due (absent/blank/invalid counts as due). */
    public static boolean due(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        try {
            return !OffsetDateTime.now(ZoneOffset.UTC).isBefore(OffsetDateTime.parse(value));
        } catch (RuntimeException exc) {
            return true;
        }
    }

    /** The job-start gate: only a boolean {@code true} enables execution. */
    public static boolean readJobControl(Path path) {
        try {
            Map<String, Object> values = MAPPER.readValue(Files.readString(path), MAP_TYPE);
            return Boolean.TRUE.equals(values.get("job_start"));
        } catch (IOException | RuntimeException exc) {
            return false;
        }
    }

    /**
     * Select the phase prompt for the current workflow phase, mirroring the
     * workflow dispatch table (default: implement.md).
     */
    public static String phasePromptFor(String phase) {
        return switch (phase == null ? "" : phase) {
            case "CHANGES_REQUESTED", "FIXING_FEEDBACK" -> "prompts/feedback.md";
            case "VERIFYING" -> "prompts/verify.md";
            case "REVIEWING_CODE" -> "prompts/review.md";
            case "BLOCKED_REQUIREMENTS", "BLOCKED_REPOSITORY",
                 "FAILED_RECOVERABLE", "CODEX_FAILURE" -> "prompts/reconcile.md";
            case "READY_TO_MERGE" -> "prompts/final-review.md";
            case "IDLE" -> "prompts/discover.md";
            default -> "prompts/implement.md";
        };
    }

    /** Command-line options for the controller entry point. */
    public record Options(Path configPath, Path jobControlPath, String prompt, boolean forceRun) {
    }

    static Options parseArgs(String[] args) {
        Path config = Path.of("config/config.json");
        Path jobControl = Path.of("config/job-control.json");
        String prompt = null;
        boolean forceRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = Path.of(args[++i]);
                case "--job-control" -> jobControl = Path.of(args[++i]);
                case "--prompt" -> prompt = args[++i];
                case "--force-run" -> forceRun = true;
                default -> { }
            }
        }
        return new Options(config, jobControl, prompt, forceRun);
    }

    /**
     * Entry point. Returns a process exit code. This is the JSON/JVM analogue of
     * the process entry flow; it performs configuration validation, the job
     * gate, lock acquisition, GitHub reconciliation, phase dispatch, bounded
     * Codex execution, and durable state persistence.
     */
    public static int run(String[] args) {
        Options options = parseArgs(args);
        Path root = repositoryRoot();

        Map<String, Object> config;
        try {
            config = Config.load(root.resolve(options.configPath()));
            Config.validate(config);
        } catch (IOException | RuntimeException exc) {
            System.err.println("BLOCKED_CONFIGURATION: " + exc.getMessage());
            return 1;
        }

        int epicNumber = Engine.asInt(Config.section(config, "epic").get("number"));

        if (!options.forceRun() && !readJobControl(root.resolve(options.jobControlPath()))) {
            System.out.println("SKIPPED: job_start is not true");
            return 0;
        }

        Map<String, Object> automation = Config.section(config, "automation");
        boolean dryRun = Engine.asBool(automation.getOrDefault("dry_run", true), true);
        Path stateDir = root.resolve("state");
        Path statePath = stateDir.resolve("epic-" + epicNumber + ".json");
        Path lockPath = stateDir.resolve("epic-" + epicNumber + ".lock");

        WorkflowLock lock = WorkflowLock.acquire(lockPath,
                (int) Engine.asLongOrDefault(automation.get("stale_lock_seconds"), 900),
                (int) Engine.asLongOrDefault(automation.get("watchdog_observations"), 3),
                (int) Engine.asLongOrDefault(automation.get("watchdog_grace_seconds"), 10));
        if (lock == null) {
            String detail = "";
            try {
                Map<String, Object> active = new StateStore(statePath).load();
                detail = " pid=" + active.getOrDefault("pid", "unknown")
                        + " story=#" + active.getOrDefault("current_story", "unknown")
                        + " pr=#" + active.getOrDefault("current_pr", "unknown")
                        + " phase=" + active.getOrDefault("phase", "unknown")
                        + " heartbeat=" + active.getOrDefault("heartbeat_at",
                                active.getOrDefault("updated_at", "unknown"));
            } catch (RuntimeException ignored) {
                // If state cannot be read, still report the skip without detail.
            }
            System.out.println("SKIPPED: another automation run is active;" + detail);
            return 0;
        }

        String lockToken = lock.ownerToken();
        StateStore store = new StateStore(statePath);
        try {
            Map<String, Object> state = store.load();

            if (StateRecovery.repairLegacyQuotaState(state)) {
                store.save(state);
                System.out.println("RECOVERY: repaired false PAUSED_QUOTA state from signal termination");
            }
            int abandoned = store.recoverAbandonedJobs();
            if (abandoned > 0) {
                System.out.println("RECOVERY: marked " + abandoned + " abandoned worker job(s) cancelled");
            }

            // Recover a watchdog forced-termination event, if one was recorded.
            Path watchdogEvent = Path.of(lockPath + ".watchdog.json");
            if (Files.exists(watchdogEvent)) {
                try {
                    state.put("watchdog_event", MAPPER.readValue(Files.readString(watchdogEvent), MAP_TYPE));
                    System.out.println("WATCHDOG: forced termination recovered: " + state.get("watchdog_event"));
                    Files.deleteIfExists(watchdogEvent);
                } catch (IOException | RuntimeException exc) {
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("action", "FORCED_TERMINATION");
                    fallback.put("details", "event unreadable");
                    state.put("watchdog_event", fallback);
                }
            }

            state.put("phase", state.getOrDefault("phase", "IDLE"));
            state.put("pid", ProcessHandle.current().pid());
            state.put("run_started_at", now());
            state.put("execution_id", ProcessHandle.current().pid() + "-" + System.nanoTime());
            state.put("worker_status", "IDLE");
            store.save(state);
            lock.refresh();

            if ("PAUSED_QUOTA".equals(state.get("phase")) && !due(Engine.asString(state.get("retry_at")))) {
                System.out.println("PAUSED_QUOTA: retry at " + state.get("retry_at"));
                return 0;
            }

            Path repo = Path.of(Engine.asString(Config.section(config, "repository").get("local_path")));
            if (!Files.exists(repo.resolve(".git"))) {
                state.put("phase", "BLOCKED_REPOSITORY");
                state.put("error", "Git repository not found: " + repo);
                store.save(state);
                System.err.println(state.get("error"));
                return 1;
            }

            Map<String, Object> githubConfig = Config.section(config, "github");
            GitHub github = new GitHub(
                    Engine.asString(Config.section(config, "repository").get("owner")),
                    Engine.asString(Config.section(config, "repository").get("name")),
                    Engine.asString(githubConfig.getOrDefault("token_environment_variable", "GH_TOKEN")),
                    Engine.asString(githubConfig.getOrDefault("cli", "gh")),
                    (int) Engine.asLongOrDefault(githubConfig.get("timeout_seconds"), 30));

            Map<String, Object> epic;
            List<Map<String, Object>> prs;
            try {
                lock.refresh();
                System.out.println("PHASE: GitHub issue read #" + epicNumber);
                epic = asMap(github.issue(epicNumber));
                System.out.println("PHASE: GitHub PR list");
                prs = asMapList(github.prs());
                state.remove("error");
            } catch (GitHubError exc) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", exc.getMessage());
                store.save(state);
                System.err.println("BLOCKED: GitHub unavailable: " + exc.getMessage());
                return 1;
            }

            // Delegate reconciliation/phase selection to the workflow engine.
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, epicNumber, dryRun,
                    new GitOperationsFactory(repo), new CodexRunner(),
                    new ReviewCoordinator(), lock);
            return orchestrator.reconcileAndExecute(prs, options.prompt());
        } catch (RuntimeException exc) {
            System.err.println("BLOCKED: unexpected controller error: " + exc.getMessage());
            return 1;
        } finally {
            lock.release(lockToken);
        }
    }

    /** Repository root: two levels up from the compiled classes / working dir. */
    static Path repositoryRoot() {
        String override = System.getProperty("codex.automation.root");
        if (override != null) {
            return Path.of(override);
        }
        return Path.of("").toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                }
            }
            return result;
        }
        return new java.util.ArrayList<>();
    }

    /** Factory so the orchestrator can create git operations bound to the repo. */
    public static class GitOperationsFactory {
        private final Path repo;

        public GitOperationsFactory(Path repo) {
            this.repo = repo;
        }

        public Path repo() {
            return repo;
        }

        public GitOperations create() {
            return GitOperations.forRepo(repo);
        }
    }
}
