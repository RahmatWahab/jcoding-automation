package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
}
