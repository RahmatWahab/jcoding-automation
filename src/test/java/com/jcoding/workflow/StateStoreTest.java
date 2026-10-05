package com.jcoding.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * state-store parity tests. All I/O is
 * against a JUnit {@code @TempDir}; no real GitHub or network is used. Each test
 * asserts concrete persisted values, migration behavior, and journal outcomes.
 */
class StateStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private static Map<String, Object> readJson(Path path) throws Exception {
        return MAPPER.readValue(Files.readString(path), MAP_TYPE);
    }

    @Test
    void stateStoreIsTransactionalAndKeepsJsonExport(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("epic-1.json");
        StateStore store = new StateStore(path);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", "VERIFYING");
        state.put("current_story", 12);

        store.save(state, "PHASE_CHANGED");

        assertThat(store.load().get("phase")).isEqualTo("VERIFYING");
        assertThat(readJson(path).get("current_story")).isEqualTo(12);
        assertThat(Files.exists(store.dbPath())).isTrue();
        assertThat(Files.readString(store.eventPath())).contains("PHASE_CHANGED");
    }

    @Test
    void stateStoreMigratesExistingJsonOnce(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("epic-1.json");
        Files.writeString(path, "{\"phase\":\"WAITING_FOR_CI\",\"current_story\":12}\n");

        StateStore store = new StateStore(path);
        assertThat(store.load().get("phase")).isEqualTo("WAITING_FOR_CI");
        assertThat(Files.exists(store.dbPath())).isTrue();
        assertThat(store.load().get("current_story")).isEqualTo(12);
    }

    @Test
    void jobJournalRecordsHeartbeatsAndTerminalResult(@TempDir Path tmp) {
        StateStore store = new StateStore(tmp.resolve("epic-1.json"));
        store.startJob("run-1", "CODEX_WORKER", "VERIFYING", 12, 99, "abc", "run.log");
        assertThat(store.runningJob().get("job_id")).isEqualTo("run-1");

        store.heartbeatJob("run-1", "running focused tests");
        store.finishJob("run-1", "SUCCEEDED", 0, "passed");

        assertThat(store.runningJob()).isNull();
    }

    @Test
    void abandonedJobsAreCancelledWithoutTouchingActiveJob(@TempDir Path tmp) {
        StateStore store = new StateStore(tmp.resolve("epic-1.json"));
        store.startJob("old", "CODEX_WORKER", "VERIFYING", null, null, null, null);
        store.startJob("active", "CODEX_WORKER", "VERIFYING", null, null, null, null);

        assertThat(store.recoverAbandonedJobs("active")).isEqualTo(1);
        assertThat(store.runningJob().get("job_id")).isEqualTo("active");
    }

    @Test
    void workerUsageStoresReportedCountersAndUnavailableUsageAsNull(@TempDir Path tmp) throws Exception {
        StateStore store = new StateStore(tmp.resolve("epic-1.json"));
        store.recordWorkerUsage("call-1", 12, "IMPLEMENTING", "test-model", 321,
                2048, 0, 100L, 40L, 20L, "redacted output", "tool diagnostics");
        store.recordWorkerUsage("call-2", 12, "REVIEWING_CODE", "test-model", 123,
                1024, 1, null, null, null, "", "process failed");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.dbPath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT job_id, phase, input_tokens, "
                     + "cached_input_tokens, output_tokens, output FROM worker_usage ORDER BY job_id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("job_id")).isEqualTo("call-1");
            assertThat(rows.getString("phase")).isEqualTo("IMPLEMENTING");
            assertThat(rows.getLong("input_tokens")).isEqualTo(100);
            assertThat(rows.getLong("cached_input_tokens")).isEqualTo(40);
            assertThat(rows.getLong("output_tokens")).isEqualTo(20);
            assertThat(rows.getString("output")).isEqualTo("redacted output");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("job_id")).isEqualTo("call-2");
            assertThat(rows.getObject("input_tokens")).isNull();
            assertThat(rows.getObject("cached_input_tokens")).isNull();
            assertThat(rows.getObject("output_tokens")).isNull();
            assertThat(rows.next()).isFalse();
        }
    }
}
