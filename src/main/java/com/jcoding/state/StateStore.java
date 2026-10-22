package com.jcoding.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Durable workflow state and resumable job journal.
 *
 * <p>Pure workflow logic.
 * The JSON state file remains a human-readable compatibility/export view;
 * SQLite is the authoritative store because a controller restart must not lose
 * a phase, lease, cursor, or in-flight job record between an atomic file
 * replacement.</p>
 */
public final class StateStore {
    public static final class StaleState extends IllegalStateException {
        public StaleState(String message) { super(message); }
    }

    private static final Set<String> VALID_JOB_STATUS =
            Set.of("SUCCEEDED", "BLOCKED", "FAILED", "TIMED_OUT", "CANCELLED");

    /** Emits JSON with sorted keys and 2-space indent for a stable export. */
    private static final ObjectMapper MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private static final ObjectWriter SORTED_PRETTY = MAPPER.writer(compactColonPrettyPrinter());

    /** Stable JSON: 2-space indent, LF newlines, ": " field separator. */
    private static com.fasterxml.jackson.core.util.DefaultPrettyPrinter compactColonPrettyPrinter() {
        com.fasterxml.jackson.core.util.DefaultIndenter indenter =
                new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n");
        com.fasterxml.jackson.core.util.Separators separators =
                com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(
                                com.fasterxml.jackson.core.util.Separators.Spacing.AFTER);
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter printer =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter(separators);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private final Path jsonPath;
    private final Path dbPath;
    private final Path eventPath;

    public StateStore(Path jsonPath) {
        this.jsonPath = jsonPath;
        this.dbPath = withSuffix(jsonPath, ".sqlite3");
        this.eventPath = withSuffix(jsonPath, ".events.jsonl");
    }

    public Path jsonPath() {
        return jsonPath;
    }

    public Path dbPath() {
        return dbPath;
    }

    public Path eventPath() {
        return eventPath;
    }

    static String utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    /** Replace the final {@code .suffix} of the path, mirroring pathlib.with_suffix. */
    private static Path withSuffix(Path path, String newSuffix) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        Path parent = path.getParent();
        String replaced = stem + newSuffix;
        return parent == null ? Path.of(replaced) : parent.resolve(replaced);
    }

    private Connection connect() throws SQLException {
        try {
            secureDirectory(dbPath.getParent());
        } catch (IOException exc) {
            throw new SQLException("unable to create state directory", exc);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath
                + "?busy_timeout=15000");
        try { secureFile(dbPath); }
        catch (IOException exc) {
            try { connection.close(); } catch (SQLException close) { exc.addSuppressed(close); }
            throw new SQLException("unable to restrict workflow database permissions", exc);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS workflow_state ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1),"
                    + "payload TEXT NOT NULL,"
                    + "version INTEGER NOT NULL,"
                    + "updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS workflow_jobs ("
                    + "job_id TEXT PRIMARY KEY,"
                    + "kind TEXT NOT NULL,"
                    + "phase TEXT NOT NULL,"
                    + "story INTEGER,"
                    + "pr INTEGER,"
                    + "head_sha TEXT,"
                    + "status TEXT NOT NULL,"
                    + "started_at TEXT NOT NULL,"
                    + "heartbeat_at TEXT NOT NULL,"
                    + "finished_at TEXT,"
                    + "exit_code INTEGER,"
                    + "log_path TEXT,"
                    + "detail TEXT)");
            statement.execute("CREATE TABLE IF NOT EXISTS workflow_events ("
                    + "sequence INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "event_type TEXT NOT NULL,"
                    + "payload TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS workflow_effects ("
                    + "effect_key TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, "
                    + "status TEXT NOT NULL, result TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS workflow_intent_revisions ("
                    + "story INTEGER NOT NULL, revision INTEGER NOT NULL, requirements_hash TEXT NOT NULL, "
                    + "source_revision TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL, "
                    + "PRIMARY KEY(story,revision))");
            statement.execute("CREATE TABLE IF NOT EXISTS worker_usage ("
                    + "job_id TEXT PRIMARY KEY, story INTEGER, phase TEXT NOT NULL, model TEXT, "
                    + "duration_ms INTEGER NOT NULL, prompt_bytes INTEGER NOT NULL, exit_code INTEGER NOT NULL, "
                    + "input_tokens INTEGER, cached_input_tokens INTEGER, output_tokens INTEGER, "
                    + "output TEXT NOT NULL, diagnostics TEXT NOT NULL, created_at TEXT NOT NULL, "
                    + "prompt_sha256 TEXT NOT NULL DEFAULT '', policy_sha256 TEXT NOT NULL DEFAULT '', "
                    + "runtime_sha256 TEXT NOT NULL DEFAULT '')");
            for (String column : List.of("prompt_sha256", "policy_sha256", "runtime_sha256")) {
                try { statement.execute("ALTER TABLE worker_usage ADD COLUMN " + column + " TEXT NOT NULL DEFAULT ''"); }
                catch (SQLException alreadyPresent) {
                    if (!alreadyPresent.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw alreadyPresent;
                }
            }
            try (ResultSet schema = statement.executeQuery("PRAGMA user_version")) {
                if (schema.next() && schema.getInt(1) > 3)
                    throw new SQLException("Workflow database schema is newer than this controller");
            }
            statement.execute("CREATE TABLE IF NOT EXISTS evaluation_runs ("
                    + "run_id TEXT PRIMARY KEY, suite TEXT NOT NULL, scenario TEXT NOT NULL, "
                    + "status TEXT NOT NULL, requirements_hash TEXT, source_hash TEXT, "
                    + "worker_calls INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, "
                    + "input_tokens INTEGER, output_tokens INTEGER, recovery_count INTEGER NOT NULL DEFAULT 0, "
                    + "detail TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)");
            statement.execute("PRAGMA user_version=3");
        }
        return connection;
    }

    /** Reserve an external effect before calling its adapter. Returns true only for the first reservation. */
    public boolean reserveEffect(String key, String kind, Map<String, Object> payload) {
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO workflow_effects(effect_key,kind,payload,status,created_at,updated_at) VALUES(?,?,?,'INTENT',?,?)")) {
            insert.setString(1, key); insert.setString(2, kind); insert.setString(3, serialize(payload));
            insert.setString(4, utcNow()); insert.setString(5, utcNow());
            return insert.executeUpdate() == 1;
        } catch (SQLException exc) { throw new IllegalStateException("Cannot reserve external effect", exc); }
    }

    public void finishEffect(String key, String status, Object result) {
        if (!Set.of("VERIFIED", "UNKNOWN").contains(status)) throw new IllegalArgumentException("Invalid effect status");
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_effects SET status=?,result=?,updated_at=? WHERE effect_key=?")) {
            update.setString(1, status); update.setString(2, MAPPER.writeValueAsString(result));
            update.setString(3, utcNow()); update.setString(4, key);
            if (update.executeUpdate() != 1) throw new IllegalStateException("Unreserved effect");
        } catch (SQLException | IOException exc) { throw new IllegalStateException("Cannot checkpoint external effect", exc); }
    }

    public Map<String, Object> effect(String key) {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT status,payload,result FROM workflow_effects WHERE effect_key=?")) {
            query.setString(1, key);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Map.of();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", row.getString("status"));
                result.put("payload", row.getString("payload"));
                result.put("result", row.getString("result"));
                return result;
            }
        } catch (SQLException exc) { throw new IllegalStateException("Cannot read effect", exc); }
    }

    /** Operator-visible uncertain effects; payload is retained for independent reconciliation. */
    public List<Map<String, Object>> uncertainEffects() {
        List<Map<String, Object>> effects = new java.util.ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT effect_key,kind,payload,created_at,updated_at FROM workflow_effects "
                        + "WHERE status IN ('INTENT','UNKNOWN','RETRY_AUTHORIZED') ORDER BY created_at LIMIT 1000");
             ResultSet row = query.executeQuery()) {
            while (row.next()) {
                effects.add(Map.of("key", row.getString("effect_key"), "kind", row.getString("kind"),
                        "payload", row.getString("payload"), "created_at", row.getString("created_at"),
                        "updated_at", row.getString("updated_at")));
            }
            return effects;
        } catch (SQLException exc) { throw new IllegalStateException("Cannot list uncertain effects", exc); }
    }

    /** Explicit operator decision after independently proving the remote effect is absent. */
    public void authorizeEffectRetry(String key, String reason) {
        if (!key.matches("[a-f0-9]{64}") || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Effect retry requires a SHA-256 key and a reconciliation reason");
        }
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_effects SET status='RETRY_AUTHORIZED',result=?,updated_at=? "
                        + "WHERE effect_key=? AND status IN ('INTENT','UNKNOWN')")) {
            update.setString(1, serialize(Map.of("operator_reason", reason, "authorized_at", utcNow())));
            update.setString(2, utcNow()); update.setString(3, key);
            if (update.executeUpdate() != 1) throw new IllegalArgumentException("Effect is not uncertain: " + key);
        } catch (SQLException exc) { throw new IllegalStateException("Cannot authorize effect retry", exc); }
    }

    /** Consume a one-shot operator grant before dispatching the external mutation. */
    public boolean consumeEffectRetry(String key) {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "UPDATE workflow_effects SET status='INTENT',updated_at=? WHERE effect_key=? AND status='RETRY_AUTHORIZED'")) {
            update.setString(1, utcNow()); update.setString(2, key);
            return update.executeUpdate() == 1;
        } catch (SQLException exc) { throw new IllegalStateException("Cannot consume effect retry", exc); }
    }

    /** Load durable state, migrating a legacy JSON-only file once, else IDLE default. */
    public Map<String, Object> load() {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT payload,version FROM workflow_state WHERE id=1");
             ResultSet row = select.executeQuery()) {
            if (row.next()) {
                Map<String, Object> value = parseObject(row.getString("payload"),
                        "durable workflow state must be an object");
                value.put("_state_version", row.getInt("version"));
                return value;
            }
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to read durable workflow state", exc);
        }
        // One-time migration from the original JSON-only implementation.
        if (Files.exists(jsonPath)) {
            String text;
            try {
                text = Files.readString(jsonPath);
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
            Map<String, Object> value = parseObject(text, "workflow state must be an object");
            save(value, "STATE_MIGRATED_FROM_JSON");
            return value;
        }
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("phase", "IDLE");
        initial.put("current_story", null);
        initial.put("updated_at", utcNow());
        return initial;
    }

    private Map<String, Object> parseObject(String json, String message) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (IOException exc) {
            throw new IllegalArgumentException(message, exc);
        }
    }

    public void save(Map<String, Object> state) {
        save(state, "STATE_SAVED");
    }

    /** Persist state durably (SQLite), then write the JSON export and events line. */
    public void save(Map<String, Object> state, String eventType) {
        if (state == null) {
            throw new IllegalArgumentException("workflow state must be an object");
        }
        String timestamp = utcNow();
        state.put("updated_at", timestamp);
        state.put("heartbeat_at", timestamp);
        String payload;

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            int previous;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT version FROM workflow_state WHERE id=1");
                 ResultSet row = select.executeQuery()) {
                previous = row.next() ? row.getInt("version") : 0;
            }
            int expected = state.get("_state_version") instanceof Number n ? n.intValue() : 0;
            if (expected != previous) throw new StaleState("workflow state revision changed: expected "
                    + expected + ", actual " + previous);
            int version = previous + 1;
            Map<String, Object> next = new LinkedHashMap<>(state);
            next.put("_state_version", version);
            payload = serialize(next);
            int updated;
            if (previous == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT OR IGNORE INTO workflow_state(id,payload,version,updated_at) VALUES(1,?,?,?)")) {
                    insert.setString(1, payload);
                    insert.setInt(2, version);
                    insert.setString(3, timestamp);
                    updated = insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE workflow_state SET payload=?,version=?,updated_at=? WHERE id=1 AND version=?")) {
                    update.setString(1, payload);
                    update.setInt(2, version);
                    update.setString(3, timestamp);
                    update.setInt(4, previous);
                    updated = update.executeUpdate();
                }
            }
            if (updated != 1) throw new StaleState("workflow state revision changed during save");
            if (state.get("goal_contract") instanceof Map<?, ?> goal
                    && goal.get("revision") instanceof Number revision && goal.get("story") instanceof Number story) {
                String requirementHash = String.valueOf(goal.get("requirements_hash"));
                int storyNumber = story.intValue();
                int revisionNumber = revision.intValue();
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT requirements_hash FROM workflow_intent_revisions WHERE story=? AND revision=?")) {
                    existing.setInt(1, storyNumber);
                    existing.setInt(2, revisionNumber);
                    try (ResultSet found = existing.executeQuery()) {
                        if (found.next() && !requirementHash.equals(found.getString(1)))
                            throw new StaleState("Intent revision cannot be rewritten");
                    }
                }
                try (PreparedStatement insertIntent = connection.prepareStatement(
                        "INSERT OR IGNORE INTO workflow_intent_revisions"
                                + "(story,revision,requirements_hash,source_revision,payload,created_at) VALUES(?,?,?,?,?,?)")) {
                    insertIntent.setInt(1, storyNumber);
                    insertIntent.setInt(2, revisionNumber);
                    insertIntent.setString(3, requirementHash);
                    insertIntent.setString(4, String.valueOf(goal.containsKey("source_revision")
                            ? goal.get("source_revision") : ""));
                    Map<String, Object> intentPayload = new LinkedHashMap<>();
                    goal.forEach((key, value) -> intentPayload.put(String.valueOf(key), value));
                    insertIntent.setString(5, serialize(intentPayload));
                    insertIntent.setString(6, timestamp);
                    insertIntent.executeUpdate();
                }
            }
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("phase", state.get("phase"));
            eventPayload.put("current_story", state.get("current_story"));
            eventPayload.put("current_pr", state.get("current_pr"));
            eventPayload.put("worker_status", state.get("worker_status"));
            eventPayload.put("version", version);
            try (PreparedStatement insertEvent = connection.prepareStatement(
                    "INSERT INTO workflow_events(event_type,payload,created_at) VALUES(?,?,?)")) {
                insertEvent.setString(1, eventType);
                insertEvent.setString(2, serializeSorted(eventPayload));
                insertEvent.setString(3, timestamp);
                insertEvent.executeUpdate();
            }
            connection.commit();
            state.put("_state_version", version);
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to persist durable workflow state", exc);
        }

        // JSON export is written only after the durable transaction commits.
        Path temporary = null;
        try {
            secureDirectory(jsonPath.getParent());
            temporary = Files.createTempFile(jsonPath.getParent(), ".workflow-state-", ".tmp");
            secureFile(temporary);
            byte[] bytes = (payload + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(temporary, bytes, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, jsonPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, jsonPath, StandardCopyOption.REPLACE_EXISTING);
            }
            secureFile(jsonPath);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", eventType);
            line.put("at", timestamp);
            line.put("version", version(state, payload));
            line.put("phase", state.get("phase"));
            line.put("story", state.get("current_story"));
            line.put("pr", state.get("current_pr"));
            if (!Files.exists(eventPath)) Files.createFile(eventPath);
            secureFile(eventPath);
            Files.write(eventPath, (serializeCompact(line) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static void secureDirectory(Path directory) throws IOException {
        if (directory == null) return;
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
    }

    private static void secureFile(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }

    /**
     * The JSONL line records the version that was just written. We recompute it
     * from the store to avoid threading a second variable; SQLite is the source
     * of truth, so we read the current version back.
     */
    private int version(Map<String, Object> state, String payload) {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT version FROM workflow_state WHERE id=1");
             ResultSet row = select.executeQuery()) {
            return row.next() ? row.getInt("version") : 1;
        } catch (SQLException exc) {
            return 1;
        }
    }

    public void startJob(String jobId, String kind, String phase,
                         Integer story, Integer pr, String headSha, String logPath) {
        String timestamp = utcNow();
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO workflow_jobs(job_id,kind,phase,story,pr,head_sha,status,"
                             + "started_at,heartbeat_at,log_path) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, jobId);
            insert.setString(2, kind);
            insert.setString(3, phase);
            setNullableInt(insert, 4, story);
            setNullableInt(insert, 5, pr);
            insert.setString(6, headSha);
            insert.setString(7, "RUNNING");
            insert.setString(8, timestamp);
            insert.setString(9, timestamp);
            insert.setString(10, logPath);
            insert.executeUpdate();
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to start job: " + jobId, exc);
        }
    }

    public void heartbeatJob(String jobId, String detail) {
        try (Connection connection = connect();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE workflow_jobs SET heartbeat_at=?, detail=? WHERE job_id=?")) {
            update.setString(1, utcNow());
            update.setString(2, detail);
            update.setString(3, jobId);
            update.executeUpdate();
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to heartbeat job: " + jobId, exc);
        }
    }

    public void finishJob(String jobId, String status, Integer exitCode, String detail) {
        if (!VALID_JOB_STATUS.contains(status)) {
            throw new IllegalArgumentException("invalid job status: " + status);
        }
        String timestamp = utcNow();
        try (Connection connection = connect();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE workflow_jobs SET status=?, heartbeat_at=?, finished_at=?, "
                             + "exit_code=?, detail=? WHERE job_id=?")) {
            update.setString(1, status);
            update.setString(2, timestamp);
            update.setString(3, timestamp);
            setNullableInt(update, 4, exitCode);
            update.setString(5, detail);
            update.setString(6, jobId);
            update.executeUpdate();
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to finish job: " + jobId, exc);
        }
    }

    /** Every actual model call, including specialists; null counters mean unavailable, not free. */
    public void recordWorkerUsage(String jobId, Integer story, String phase, String model,
                                  long durationMillis, long promptBytes, int exitCode,
                                  Long inputTokens, Long cachedTokens, Long outputTokens,
                                  String output, String diagnostics) {
        recordWorkerUsage(jobId, story, phase, model, durationMillis, promptBytes, exitCode,
                inputTokens, cachedTokens, outputTokens, output, diagnostics, "", "", "");
    }

    public void recordWorkerUsage(String jobId, Integer story, String phase, String model,
                                  long durationMillis, long promptBytes, int exitCode,
                                  Long inputTokens, Long cachedTokens, Long outputTokens,
                                  String output, String diagnostics, String promptHash,
                                  String policyHash, String runtimeHash) {
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO worker_usage(job_id,story,phase,model,duration_ms,prompt_bytes,exit_code,"
                             + "input_tokens,cached_input_tokens,output_tokens,output,diagnostics,created_at,"
                             + "prompt_sha256,policy_sha256,runtime_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, jobId);
            setNullableInt(insert, 2, story);
            insert.setString(3, phase);
            insert.setString(4, model);
            insert.setLong(5, durationMillis);
            insert.setLong(6, promptBytes);
            insert.setInt(7, exitCode);
            insert.setObject(8, inputTokens);
            insert.setObject(9, cachedTokens);
            insert.setObject(10, outputTokens);
            insert.setString(11, output);
            insert.setString(12, diagnostics);
            insert.setString(13, utcNow());
            insert.setString(14, promptHash);
            insert.setString(15, policyHash);
            insert.setString(16, runtimeHash);
            insert.executeUpdate();
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to persist worker usage/evidence: " + jobId, exc);
        }
    }

    /** Most recently started RUNNING job, or {@code null}. */
    public Map<String, Object> runningJob() {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT * FROM workflow_jobs WHERE status='RUNNING' "
                             + "ORDER BY started_at DESC LIMIT 1");
             ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return null;
            }
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("job_id", row.getString("job_id"));
            job.put("kind", row.getString("kind"));
            job.put("phase", row.getString("phase"));
            job.put("story", nullableInt(row, "story"));
            job.put("pr", nullableInt(row, "pr"));
            job.put("head_sha", row.getString("head_sha"));
            job.put("status", row.getString("status"));
            job.put("started_at", row.getString("started_at"));
            job.put("heartbeat_at", row.getString("heartbeat_at"));
            job.put("finished_at", row.getString("finished_at"));
            job.put("exit_code", nullableInt(row, "exit_code"));
            job.put("log_path", row.getString("log_path"));
            job.put("detail", row.getString("detail"));
            return job;
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to read running job", exc);
        }
    }

    /** Close RUNNING jobs left by a terminated controller execution; returns count. */
    public int recoverAbandonedJobs(String activeJobId) {
        String timestamp = utcNow();
        String query = "UPDATE workflow_jobs SET status='CANCELLED', heartbeat_at=?, "
                + "finished_at=?, detail=? WHERE status='RUNNING'";
        if (activeJobId != null) {
            query += " AND job_id <> ?";
        }
        try (Connection connection = connect();
             PreparedStatement update = connection.prepareStatement(query)) {
            update.setString(1, timestamp);
            update.setString(2, timestamp);
            update.setString(3, "controller execution ended before job completion");
            if (activeJobId != null) {
                update.setString(4, activeJobId);
            }
            return update.executeUpdate();
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to recover abandoned jobs", exc);
        }
    }

    public int recoverAbandonedJobs() {
        return recoverAbandonedJobs(null);
    }

    /** Capture a compact immutable trajectory result; unknown token counts remain SQL NULL. */
    public void recordEvaluationRun(String runId, String suite, String scenario, String status,
                                    String requirementsHash, String sourceHash, int workerCalls,
                                    long durationMillis, Long inputTokens, Long outputTokens,
                                    int recoveryCount, String detail) {
        if (runId == null || runId.isBlank() || suite == null || suite.isBlank()
                || scenario == null || scenario.isBlank()
                || !Set.of("PASSED", "FAILED", "BLOCKED", "INCOMPLETE").contains(status)
                || workerCalls < 0 || durationMillis < 0 || recoveryCount < 0)
            throw new IllegalArgumentException("Invalid evaluation trajectory record");
        try (Connection connection = connect(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO evaluation_runs(run_id,suite,scenario,status,requirements_hash,source_hash,"
                        + "worker_calls,duration_ms,input_tokens,output_tokens,recovery_count,detail,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, runId); insert.setString(2, suite); insert.setString(3, scenario);
            insert.setString(4, status); insert.setString(5, requirementsHash); insert.setString(6, sourceHash);
            insert.setInt(7, workerCalls); insert.setLong(8, durationMillis);
            insert.setObject(9, inputTokens); insert.setObject(10, outputTokens);
            insert.setInt(11, recoveryCount); insert.setString(12, detail == null ? "" : detail);
            insert.setString(13, utcNow()); insert.executeUpdate();
        } catch (SQLException exc) { throw new IllegalStateException("unable to persist evaluation trajectory", exc); }
    }

    public List<Map<String, Object>> evaluationSummary(String suite) {
        String sql = "SELECT status,COUNT(*) trials,SUM(worker_calls) worker_calls,SUM(duration_ms) duration_ms,"
                + "SUM(input_tokens) input_tokens,SUM(output_tokens) output_tokens,SUM(recovery_count) recovery_count "
                + "FROM evaluation_runs WHERE suite=? GROUP BY status ORDER BY status";
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, suite);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) rows.add(Map.of("status", result.getString("status"),
                        "trials", result.getInt("trials"), "worker_calls", result.getLong("worker_calls"),
                        "duration_ms", result.getLong("duration_ms"),
                        "input_tokens", result.getObject("input_tokens") == null ? "unavailable" : result.getLong("input_tokens"),
                        "output_tokens", result.getObject("output_tokens") == null ? "unavailable" : result.getLong("output_tokens"),
                        "recovery_count", result.getLong("recovery_count")));
            }
            return List.copyOf(rows);
        } catch (SQLException exc) { throw new IllegalStateException("unable to summarize evaluation trajectories", exc); }
    }

    /** Aggregate trial outcomes into explicit rates; token cost remains unknown if any trial omits usage. */
    public Map<String, Object> evaluationReport(String suite) {
        if (suite == null || suite.isBlank()) throw new IllegalArgumentException("Evaluation suite is required");
        String sql = "SELECT COUNT(*) trials,"
                + "SUM(CASE WHEN status='PASSED' THEN 1 ELSE 0 END) passed,"
                + "SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END) failed,"
                + "SUM(CASE WHEN status='BLOCKED' THEN 1 ELSE 0 END) blocked,"
                + "SUM(CASE WHEN status='INCOMPLETE' THEN 1 ELSE 0 END) incomplete,"
                + "SUM(worker_calls) worker_calls,SUM(duration_ms) duration_ms,SUM(recovery_count) recovery_count,"
                + "SUM(input_tokens) input_tokens,SUM(output_tokens) output_tokens,"
                + "SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL THEN 1 ELSE 0 END) unknown_usage "
                + "FROM evaluation_runs WHERE suite=?";
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, suite);
            try (ResultSet row = query.executeQuery()) {
                int trials = row.getInt("trials");
                int passed = row.getInt("passed");
                int failed = row.getInt("failed");
                int blocked = row.getInt("blocked");
                int incomplete = row.getInt("incomplete");
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("suite", suite);
                report.put("trials", trials);
                report.put("passed", passed);
                report.put("failed", failed);
                report.put("blocked", blocked);
                report.put("incomplete", incomplete);
                report.put("success_rate", trials == 0 ? 0.0 : (double) passed / trials);
                report.put("regression_rate", trials == 0 ? 0.0 : (double) failed / trials);
                report.put("worker_calls", row.getLong("worker_calls"));
                report.put("duration_ms", row.getLong("duration_ms"));
                report.put("recovery_count", row.getLong("recovery_count"));
                if (row.getInt("unknown_usage") == 0 && trials > 0) {
                    report.put("input_tokens", row.getLong("input_tokens"));
                    report.put("output_tokens", row.getLong("output_tokens"));
                } else {
                    report.put("token_usage", "unavailable");
                }
                return Map.copyOf(report);
            }
        } catch (SQLException exc) { throw new IllegalStateException("unable to report evaluation outcomes", exc); }
    }

    // -- serialization helpers ----------------------------------------------

    /** Serialize state as sorted, 2-space-indented JSON. */
    private static String serialize(Map<String, Object> state) {
        try {
            return SORTED_PRETTY.writeValueAsString(new TreeMap<>(state));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static String serializeSorted(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(value));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static String serializeCompact(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Object nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
