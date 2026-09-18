package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * workflow-lock tests. All lock state lives
 * in a {@code @TempDir}; no external systems. Assertions verify dead-lock
 * recovery, heartbeat refresh, ownership-scoped release, and unique tokens.
 */
class WorkflowLockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private static Map<String, Object> readOwner(Path lock) throws Exception {
        return MAPPER.readValue(Files.readString(lock.resolve("owner.json")), MAP_TYPE);
    }

    private static long currentPid() {
        return ProcessHandle.current().pid();
    }

    @Test
    void deadLockIsRecovered(@TempDir Path tmp) throws Exception {
        Path lockPath = tmp.resolve("epic.lock");
        Files.createDirectory(lockPath);
        // A guaranteed-dead PID owner, 
        Files.writeString(lockPath.resolve("owner.json"), "{\"pid\": 99999999}");

        WorkflowLock lock = WorkflowLock.acquire(lockPath, 900, 3, 10);

        assertThat(lock).isNotNull();
        assertThat(lock.path()).isEqualTo(lockPath);
        assertThat(((Number) readOwner(lockPath).get("pid")).longValue()).isEqualTo(currentPid());
    }

    @Test
    void refreshLockUpdatesOwner(@TempDir Path tmp) throws Exception {
        WorkflowLock lock = WorkflowLock.acquire(tmp.resolve("epic.lock"), 900, 3, 10);
        assertThat(lock).isNotNull();
        lock.refresh();
        Map<String, Object> owner = readOwner(lock.path());
        assertThat(((Number) owner.get("pid")).longValue()).isEqualTo(currentPid());
        assertThat(owner).containsKey("heartbeat_at");
    }

    @Test
    void releaseLockDoesNotRemoveAnotherOwner(@TempDir Path tmp) throws Exception {
        WorkflowLock lock = WorkflowLock.acquire(tmp.resolve("epic.lock"), 900, 3, 10);
        assertThat(lock).isNotNull();
        // Rewrite owner to a different PID; release must not remove it.
        Path ownerFile = lock.path().resolve("owner.json");
        Map<String, Object> owner = readOwner(lock.path());
        owner.put("pid", currentPid() + 1);
        Files.writeString(ownerFile, MAPPER.writeValueAsString(owner));

        lock.release();

        assertThat(Files.exists(lock.path())).isTrue();
    }

    @Test
    void lockHasUniqueOwnerToken(@TempDir Path tmp) throws Exception {
        WorkflowLock lock = WorkflowLock.acquire(tmp.resolve("epic.lock"), 900, 3, 10);
        assertThat(lock).isNotNull();
        Map<String, Object> owner = readOwner(lock.path());
        assertThat(String.valueOf(owner.get("owner_token")).length()).isGreaterThanOrEqualTo(32);
    }

    @Test
    void freshLiveOwnerIsNotStolen(@TempDir Path tmp) throws Exception {
        Path lockPath = tmp.resolve("epic.lock");
        Files.createDirectory(lockPath);
        // Live current process with a fresh heartbeat: acquisition must be refused.
        String owner = "{\"pid\": " + currentPid()
                + ", \"heartbeat_at\": \"" + java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                + "\", \"owner_token\": \"abc123\"}";
        Files.writeString(lockPath.resolve("owner.json"), owner);

        WorkflowLock lock = WorkflowLock.acquire(lockPath, 900, 3, 10);

        assertThat(lock).isNull();
    }
}
