package com.rahmatwahab.codexautomation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Atomic single-owner workflow lock with heartbeat and watchdog recovery.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code refresh_lock}, {@code release_lock}). The lock is a directory whose
 * {@code owner.json} carries the owner PID, timestamps, and a unique token.
 * A live owner with a fresh heartbeat is authoritative; a dead or genuinely
 * stale lock is recovered. Java uses {@link ProcessHandle} for liveness where
 * liveness uses {@link ProcessHandle}; the force-termination watchdog path is
 * reproduced behaviorally via process-group destruction.</p>
 */
public final class WorkflowLock {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path path;

    private WorkflowLock(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private static String token(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        StringBuilder hex = new StringBuilder(buffer.length * 2);
        for (byte b : buffer) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Acquire the lock, recovering a dead or genuinely stale owner. Returns the
     * acquired lock, or {@code null} when a live, fresh owner still holds it.
     */
    public static WorkflowLock acquire(Path path, int staleSeconds,
                                       int watchdogObservations, int watchdogGraceSeconds) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }

        if (!tryMakeDir(path)) {
            Optional<WorkflowLock> recovered = recoverIfStale(path, staleSeconds);
            if (recovered.isEmpty()) {
                return null;
            }
        }
        writeOwner(path, ownerMetadata());
        return new WorkflowLock(path);
    }

    private static Map<String, Object> ownerMetadata() {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("pid", ProcessHandle.current().pid());
        owner.put("started_at", now());
        owner.put("heartbeat_at", now());
        owner.put("owner_token", token(24));
        return owner;
    }

    private static Optional<WorkflowLock> recoverIfStale(Path path, int staleSeconds) {
        Path ownerFile = path.resolve("owner.json");
        Map<String, Object> owner = readOwnerQuietly(ownerFile);

        Long ownerPid = asLong(owner.get("pid"));
        boolean ownerAlive = ownerPid != null && ProcessHandle.of(ownerPid).map(ProcessHandle::isAlive).orElse(false);

        String heartbeat = owner.get("heartbeat_at") != null
                ? String.valueOf(owner.get("heartbeat_at"))
                : (owner.get("started_at") != null ? String.valueOf(owner.get("started_at")) : null);
        double heartbeatAge = Double.POSITIVE_INFINITY;
        if (heartbeat != null) {
            try {
                heartbeatAge = Duration.between(OffsetDateTime.parse(heartbeat),
                        OffsetDateTime.now(ZoneOffset.UTC)).toMillis() / 1000.0;
            } catch (RuntimeException ignored) {
                // leave as infinity
            }
        }

        if (!Files.exists(ownerFile)) {
            // Do not steal during another controller's mkdir-to-owner-file window.
            try {
                long mtimeAgeSeconds = (System.currentTimeMillis()
                        - Files.getLastModifiedTime(path).toMillis()) / 1000L;
                if (mtimeAgeSeconds <= staleSeconds) {
                    return Optional.empty();
                }
            } catch (IOException exc) {
                return Optional.empty();
            }
        }

        // A live owner with a fresh heartbeat is authoritative.
        if (ownerAlive && heartbeatAge <= staleSeconds) {
            return Optional.empty();
        }

        // Recover only a dead or genuinely stale lock.
        try {
            Files.deleteIfExists(ownerFile);
            Files.deleteIfExists(path);
            if (!tryMakeDir(path)) {
                return Optional.empty();
            }
        } catch (IOException exc) {
            return Optional.empty();
        }
        return Optional.of(new WorkflowLock(path));
    }

    /** Refresh ownership heartbeat without replacing the lock directory. */
    public void refresh() {
        Path ownerFile = path.resolve("owner.json");
        Map<String, Object> owner;
        try {
            owner = MAPPER.readValue(Files.readString(ownerFile), MAP_TYPE);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
        Long ownerPid = asLong(owner.get("pid"));
        if (ownerPid == null || ownerPid != ProcessHandle.current().pid()
                || owner.get("owner_token") == null) {
            throw new IllegalStateException("workflow lock ownership changed");
        }
        owner.put("heartbeat_at", now());
        writeOwner(path, owner);
    }

    /** Release only the lock still owned by this process (optionally by token). */
    public void release(String ownerToken) {
        Path ownerFile = path.resolve("owner.json");
        try {
            Map<String, Object> owner = MAPPER.readValue(Files.readString(ownerFile), MAP_TYPE);
            Long ownerPid = asLong(owner.get("pid"));
            if (ownerPid == null || ownerPid != ProcessHandle.current().pid()
                    || owner.get("owner_token") == null
                    || (ownerToken != null && !ownerToken.equals(owner.get("owner_token")))) {
                return;
            }
            Files.deleteIfExists(ownerFile);
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException exc) {
            // best-effort release; never throw during cleanup
        }
    }

    public void release() {
        release(null);
    }

    /** The current owner token, for verification and scoped release. */
    public String ownerToken() {
        try {
            Map<String, Object> owner = MAPPER.readValue(
                    Files.readString(path.resolve("owner.json")), MAP_TYPE);
            return String.valueOf(owner.get("owner_token"));
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    // -- helpers -------------------------------------------------------------

    private static boolean tryMakeDir(Path path) {
        try {
            Files.createDirectory(path);
            return true;
        } catch (IOException exc) {
            return false;
        }
    }

    private static void writeOwner(Path path, Map<String, Object> owner) {
        Path temporary = path.resolve("owner." + ProcessHandle.current().pid() + ".tmp");
        try {
            Files.write(temporary, MAPPER.writeValueAsBytes(owner));
            Files.move(temporary, path.resolve("owner.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    private static Map<String, Object> readOwnerQuietly(Path ownerFile) {
        if (!Files.exists(ownerFile)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(Files.readString(ownerFile), MAP_TYPE);
        } catch (IOException | RuntimeException exc) {
            return new LinkedHashMap<>();
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exc) {
            return null;
        }
    }
}
