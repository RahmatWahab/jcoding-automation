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
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
        } catch (IOException exc) {
            throw new SQLException("unable to create state directory", exc);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath
                + "?busy_timeout=15000");
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
            statement.execute("CREATE TABLE IF NOT EXISTS worker_usage ("
                    + "job_id TEXT PRIMARY KEY, story INTEGER, phase TEXT NOT NULL, model TEXT, "
                    + "duration_ms INTEGER NOT NULL, prompt_bytes INTEGER NOT NULL, exit_code INTEGER NOT NULL, "
                    + "input_tokens INTEGER, cached_input_tokens INTEGER, output_tokens INTEGER, "
                    + "output TEXT NOT NULL, diagnostics TEXT NOT NULL, created_at TEXT NOT NULL)");
        }
        return connection;
    }

    /** Load durable state, migrating a legacy JSON-only file once, else IDLE default. */
    public Map<String, Object> load() {
        try (Connection connection = connect();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT payload FROM workflow_state WHERE id=1");
             ResultSet row = select.executeQuery()) {
            if (row.next()) {
                Map<String, Object> value = parseObject(row.getString("payload"),
                        "durable workflow state must be an object");
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
        String payload = serialize(state);

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            int version;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT version FROM workflow_state WHERE id=1");
                 ResultSet row = select.executeQuery()) {
                version = row.next() ? row.getInt("version") + 1 : 1;
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO workflow_state(id,payload,version,updated_at) VALUES(1,?,?,?) "
                            + "ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, "
                            + "version=excluded.version, updated_at=excluded.updated_at")) {
                upsert.setString(1, payload);
                upsert.setInt(2, version);
                upsert.setString(3, timestamp);
                upsert.executeUpdate();
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
        } catch (SQLException exc) {
            throw new IllegalStateException("unable to persist durable workflow state", exc);
        }

        // JSON export is written only after the durable transaction commits.
        Path temporary = Path.of(jsonPath + ".tmp");
        try {
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
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", eventType);
            line.put("at", timestamp);
            line.put("version", version(state, payload));
            line.put("phase", state.get("phase"));
            line.put("story", state.get("current_story"));
            line.put("pr", state.get("current_pr"));
            Files.write(eventPath, (serializeCompact(line) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
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
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO worker_usage VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
