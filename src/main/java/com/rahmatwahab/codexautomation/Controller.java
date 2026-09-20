package com.rahmatwahab.codexautomation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(Controller.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    /**
     * Passive waiting phases: the controller cannot change their outcome; only
     * external GitHub state (CI, review, merge) advances them. Used by the
     * opt-in waiting-phase poll backoff to avoid re-reading GitHub every minute
     * while nothing actionable can happen.
     */
    private static final Set<String> WAITING_PHASES = Set.of(
            "WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW",
            "WAITING_FOR_HUMAN_REVIEW", "WAITING_FOR_MANUAL_MERGE");

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

    /** Outcome of the waiting-phase poll-backoff decision. */
    enum WaitBackoff {
        /** Skip this run: a passive wait is not yet due for its next poll. */
        SKIP,
        /** Proceed and schedule the next backoff window (a due passive wait). */
        PROCEED_AND_SCHEDULE,
        /** Proceed and clear any stale backoff marker (not a passive wait). */
        PROCEED_AND_CLEAR
    }

    /**
     * Pure waiting-phase poll-backoff decision. When {@code pollSeconds <= 0}
     * (default) or the phase is not a passive wait, always proceed (and clear
     * any stale marker), preserving the previous every-tick behavior. When a
     * passive wait's {@code waitRetryAt} is not yet due, skip the run. A forced
     * run never backs off.
     */
    static WaitBackoff waitBackoffDecision(String phase, String waitRetryAt,
                                           int pollSeconds, boolean forceRun) {
        boolean passiveWait = WAITING_PHASES.contains(phase == null ? "" : phase);
        if (pollSeconds > 0 && passiveWait && !forceRun) {
            return due(waitRetryAt) ? WaitBackoff.PROCEED_AND_SCHEDULE : WaitBackoff.SKIP;
        }
        return WaitBackoff.PROCEED_AND_CLEAR;
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
     * Whether the current skip repeats the last logged skip (same active run in
     * the same state). A blank/absent previous marker is never a repeat, so the
     * first skip of a state always logs at INFO.
     */
    static boolean skipIsRepeat(String previousSignature, String currentSignature) {
        return previousSignature != null && !previousSignature.isEmpty()
                && previousSignature.equals(currentSignature);
    }

    /** Best-effort read of the last-skip marker; missing/unreadable → empty. */
    private static String readMarker(Path marker) {
        try {
            return Files.exists(marker) ? Files.readString(marker).strip() : "";
        } catch (IOException | RuntimeException exc) {
            return "";
        }
    }

    /** Best-effort write of the last-skip marker; failures are ignored. */
    private static void writeMarker(Path marker, String signature) {
        try {
            Files.writeString(marker, signature);
        } catch (IOException | RuntimeException ignored) {
            // A marker write failure only means the next skip logs at INFO
            // instead of DEBUG — never a correctness or safety concern.
        }
    }

    /** Remove the last-skip marker (called once a run acquires the lock). */
    private static void clearMarker(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException | RuntimeException ignored) {
            // best-effort
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
            log.error("BLOCKED_CONFIGURATION: " + exc.getMessage());
            return 1;
        }

        int epicNumber = Engine.asInt(Config.section(config, "epic").get("number"));

        if (!options.forceRun() && !readJobControl(root.resolve(options.jobControlPath()))) {
            log.info("SKIPPED: job_start is not true");
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
            String signature = "unknown";
            try {
                Map<String, Object> active = new StateStore(statePath).load();
                String pid = String.valueOf(active.getOrDefault("pid", "unknown"));
                String story = String.valueOf(active.getOrDefault("current_story", "unknown"));
                String pr = String.valueOf(active.getOrDefault("current_pr", "unknown"));
                String phase = String.valueOf(active.getOrDefault("phase", "unknown"));
                String heartbeat = String.valueOf(active.getOrDefault("heartbeat_at",
                        active.getOrDefault("updated_at", "unknown")));
                detail = " pid=" + pid + " story=#" + story + " pr=#" + pr
                        + " phase=" + phase + " heartbeat=" + heartbeat;
                // The active worker's heartbeat only advances when it makes
                // progress, so pid|phase|heartbeat uniquely identifies "the same
                // ongoing run in the same state". Consecutive 1-minute cron ticks
                // that skip an unchanged run are pure noise (observed 20+ identical
                // SKIPPED lines per long phase). Log the first at INFO and identical
                // repeats at DEBUG so the signal is preserved without the flood.
                signature = pid + "|" + phase + "|" + heartbeat;
            } catch (RuntimeException ignored) {
                // If state cannot be read, still report the skip without detail.
            }
            Path skipMarker = Path.of(lockPath + ".lastskip");
            String message = "SKIPPED: another automation run is active;" + detail;
            if (skipIsRepeat(readMarker(skipMarker), signature)) {
                log.debug(message);
            } else {
                log.info(message);
                writeMarker(skipMarker, signature);
            }
            return 0;
        }

        String lockToken = lock.ownerToken();
        // A run acquired the lock: clear any repeat-skip marker so the next time
        // the controller is skipped it logs at INFO (a fresh skip), not DEBUG.
        clearMarker(Path.of(lockPath + ".lastskip"));
        StateStore store = new StateStore(statePath);
        try {
            Map<String, Object> state = store.load();

            if (StateRecovery.repairLegacyQuotaState(state)) {
                store.save(state);
                log.info("RECOVERY: repaired false PAUSED_QUOTA state from signal termination");
            }
            int abandoned = store.recoverAbandonedJobs();
            if (abandoned > 0) {
                log.info("RECOVERY: marked " + abandoned + " abandoned worker job(s) cancelled");
            }

            // Recover a watchdog forced-termination event, if one was recorded.
            Path watchdogEvent = Path.of(lockPath + ".watchdog.json");
            if (Files.exists(watchdogEvent)) {
                try {
                    state.put("watchdog_event", MAPPER.readValue(Files.readString(watchdogEvent), MAP_TYPE));
                    log.info("WATCHDOG: forced termination recovered: " + state.get("watchdog_event"));
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
                log.info("PAUSED_QUOTA: retry at " + state.get("retry_at"));
                return 0;
            }

            // Opt-in waiting-phase backoff. On passive waits (CI, Codex review,
            // human review, manual-merge gate) nothing the controller does can
            // change the outcome until GitHub state changes, yet each 1-minute
            // cron tick still performs a full EPIC issue read + PR list (both
            // carry large bodies). When automation.wait_poll_seconds > 0 the
            // controller polls those waits at that slower cadence instead of
            // every minute, eliminating the majority of redundant GitHub reads
            // while active work keeps the 1-minute cadence. Default 0 disables
            // the backoff, preserving the previous every-tick behavior exactly.
            // This reuses the same due()/retry_at machinery as PAUSED_QUOTA.
            int waitPollSeconds = (int) Engine.asLongOrDefault(
                    automation.get("wait_poll_seconds"), 0);
            WaitBackoff backoff = waitBackoffDecision(
                    Engine.asString(state.get("phase")),
                    Engine.asString(state.get("wait_retry_at")),
                    waitPollSeconds, options.forceRun());
            if (backoff == WaitBackoff.SKIP) {
                log.info("WAITING BACKOFF: phase=" + state.get("phase")
                        + " next poll at " + state.get("wait_retry_at"));
                return 0;
            }
            if (backoff == WaitBackoff.PROCEED_AND_SCHEDULE) {
                // Due now: schedule the next backoff window and proceed to read.
                state.put("wait_retry_at", OffsetDateTime.now(ZoneOffset.UTC)
                        .plusSeconds(waitPollSeconds).toString());
                store.save(state);
            } else if (backoff == WaitBackoff.PROCEED_AND_CLEAR && state.containsKey("wait_retry_at")) {
                // Not a passive wait (or backoff disabled): drop any stale marker
                // so a resumed active phase is never delayed by it.
                state.remove("wait_retry_at");
                store.save(state);
            }

            Path repo = Path.of(Engine.asString(Config.section(config, "repository").get("local_path")));
            if (!Files.exists(repo.resolve(".git"))) {
                state.put("phase", "BLOCKED_REPOSITORY");
                state.put("error", "Git repository not found: " + repo);
                store.save(state);
                log.error(String.valueOf(state.get("error")));
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
                log.info("PHASE: GitHub issue read #" + epicNumber);
                epic = asMap(github.issue(epicNumber));
                log.info("PHASE: GitHub PR list");
                prs = asMapList(github.prs());
                state.remove("error");
            } catch (GitHubError exc) {
                state.put("phase", "BLOCKED_EXTERNAL");
                state.put("error", exc.getMessage());
                store.save(state);
                log.error("BLOCKED: GitHub unavailable: " + exc.getMessage());
                return 1;
            }

            // Delegate reconciliation/phase selection to the workflow engine.
            WorkflowOrchestrator orchestrator = new WorkflowOrchestrator(
                    config, state, store, github, repo, root, epic, epicNumber, dryRun,
                    new GitOperationsFactory(repo), new CodexRunner(),
                    new ReviewCoordinator(), lock);
            return orchestrator.reconcileAndExecute(prs, options.prompt());
        } catch (WorkflowLock.LockOwnershipLost lost) {
            // Another controller recovered this lock as stale and took over mid
            // run. This is an expected, benign hand-off (not a fault): stop this
            // run cleanly so we never mutate state we no longer own. The takeover
            // process is now the authority; the next tick reconciles normally.
            log.info("ENDED: workflow lock ownership lost mid-run (" + lost.getMessage()
                    + "); another run has taken over");
            return 0;
        } catch (RuntimeException exc) {
            log.error("BLOCKED: unexpected controller error: " + exc.getMessage());
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
