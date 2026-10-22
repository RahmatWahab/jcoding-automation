package com.jcoding.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;

/** Persistent controller-owned worktree; the operator checkout is never used for worker edits. */
final class IsolatedCheckout {
    private static final String RECORD = ".owner.json";
    private IsolatedCheckout() { }

    record CleanupResult(boolean removed, String reason) { }

    static Path open(Path configured, int epic) {
        try {
            Path source = configured.toRealPath();
            if (!Files.isDirectory(source) || !Files.exists(source.resolve(".git"))) {
                throw new IllegalArgumentException("Configured repository is not a Git checkout");
            }
            GitOperations sourceGit = GitOperations.forRepo(source);
            if (!source.equals(Path.of(sourceGit.repositoryTopLevel()).toRealPath())) {
                throw new IllegalArgumentException("Configured repository must be its checkout root");
            }
            String identity = sourceGit.commonGitDirectory();
            String suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
            Path parent = source.getParent().resolve(".jcoding-worktrees").resolve(suffix);
            if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(parent.getParent())) {
                throw new IllegalStateException("Controller worktree parent cannot be a symlink");
            }
            Path checkout = parent.resolve("epic-" + epic).toAbsolutePath().normalize();
            Path owner = parent.resolve("epic-" + epic + RECORD);
            if (Files.exists(checkout)) {
                if (Files.isSymbolicLink(checkout) || !Files.isDirectory(checkout)
                        || !identity.equals(GitOperations.forRepo(checkout).commonGitDirectory())
                        || !checkout.equals(Path.of(GitOperations.forRepo(checkout).repositoryTopLevel()).toRealPath())) {
                    throw new IllegalStateException("Existing controller worktree does not belong to configured repository");
                }
                if (!Files.isRegularFile(owner) || Files.isSymbolicLink(owner))
                    throw new IllegalStateException("Existing worktree has no controller ownership record");
                Map<?, ?> record = new com.fasterxml.jackson.databind.ObjectMapper().readValue(owner.toFile(), Map.class);
                if (!source.toString().equals(record.get("source")) || !identity.equals(record.get("identity"))
                        || !checkout.toString().equals(record.get("checkout")))
                    throw new IllegalStateException("Worktree ownership record does not match configured checkout");
                Map<String, Object> seen = new LinkedHashMap<>();
                record.forEach((key, value) -> seen.put(String.valueOf(key), value));
                if (!"VERIFIED_RETAINED".equals(record.get("lifecycle"))) {
                    String priorLifecycle = Engine.asString(record.get("lifecycle"));
                    if (!priorLifecycle.isBlank()) seen.put("previous_lifecycle", priorLifecycle);
                    if ("ACTIVE".equals(priorLifecycle)) {
                        // Controller.run holds the epic lock before opening the
                        // checkout. An ACTIVE owner record at this point belongs
                        // to a prior process that did not record a terminal state.
                        seen.put("recovery_reason", "previous_controller_ended_while_checkout_active");
                        seen.put("recovered_at", Controller.now());
                    }
                    seen.put("lifecycle", "ACTIVE");
                }
                seen.put("last_seen_at", Controller.now());
                writeOwner(owner, seen);
            } else {
                Files.createDirectories(parent);
                sourceGit.addDetachedWorktree(checkout);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("schema_version", 1);
                record.put("source", source.toString());
                record.put("identity", identity);
                record.put("checkout", checkout.toString());
                record.put("epic", epic);
                record.put("created_at", Controller.now());
                record.put("lifecycle", "ACTIVE");
                writeOwner(owner, record);
            }
            return checkout;
        } catch (IOException | java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException("Cannot establish isolated worker checkout", exc);
        }
    }

    static void recordLifecycle(Path checkout, String lifecycle) {
        try {
            Path owner = checkout.getParent().resolve(checkout.getFileName() + RECORD);
            if (!Files.isRegularFile(owner) || Files.isSymbolicLink(owner))
                throw new IllegalStateException("Cannot update unowned worker checkout lifecycle");
            Map<?, ?> old = new com.fasterxml.jackson.databind.ObjectMapper().readValue(owner.toFile(), Map.class);
            Map<String, Object> updated = new LinkedHashMap<>();
            old.forEach((key, value) -> updated.put(String.valueOf(key), value));
            updated.put("lifecycle", lifecycle);
            updated.put("updated_at", Controller.now());
            writeOwner(owner, updated);
        } catch (IOException exc) { throw new IllegalStateException("Cannot persist worker checkout lifecycle", exc); }
    }

    private static void writeOwner(Path owner, Map<String, Object> record) throws IOException {
        Path temporary = Files.createTempFile(owner.getParent(), ".owner-", ".tmp");
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(temporary.toFile(), record);
            Files.move(temporary, owner, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Remove only a controller-owned terminal checkout whose exact source was read back from its artifact. */
    static CleanupResult cleanupDetailed(Path configured, int epic, Path checkout, boolean terminal,
                                         String expectedSourceFingerprint) {
        if (!terminal) return new CleanupResult(false, "outcome is not terminal");
        if (expectedSourceFingerprint == null || expectedSourceFingerprint.isBlank())
            return new CleanupResult(false, "verified source fingerprint is missing");
        try {
            Path source = configured.toRealPath();
            GitOperations sourceGit = GitOperations.forRepo(source);
            Path normalized = checkout.toAbsolutePath().normalize();
            String suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
            Path parent = source.getParent().resolve(".jcoding-worktrees").resolve(suffix);
            Path expected = parent.resolve("epic-" + epic).toAbsolutePath().normalize();
            Path owner = parent.resolve("epic-" + epic + RECORD);
            if (!normalized.equals(expected) || Files.isSymbolicLink(parent) || Files.isSymbolicLink(expected)
                    || Files.isSymbolicLink(owner) || !Files.isRegularFile(owner)) return new CleanupResult(false, "path or owner record is unsafe/missing");
            Map<?, ?> record = new com.fasterxml.jackson.databind.ObjectMapper().readValue(owner.toFile(), Map.class);
            if (!source.toString().equals(record.get("source"))
                    || !sourceGit.commonGitDirectory().equals(record.get("identity"))
                    || !normalized.toString().equals(record.get("checkout"))) return new CleanupResult(false, "owner identity mismatch");
            String expectedSuffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
            Path expectedParent = source.getParent().resolve(".jcoding-worktrees").resolve(expectedSuffix);
            if (!parent.equals(expectedParent) || !"VERIFIED_RETAINED".equals(record.get("lifecycle"))) return new CleanupResult(false, "checkout is not marked verified-retained");
            GitOperations workerGit = GitOperations.forRepo(normalized);
            if (!workerGit.commonGitDirectory().equals(sourceGit.commonGitDirectory())
                    || Files.isSymbolicLink(normalized.resolve(".git"))) return new CleanupResult(false, "Git common directory mismatch");
            if (!expectedSourceFingerprint.equals(workerGit.recoveryFingerprint()))
                return new CleanupResult(false, "checkout changed after verified artifact creation");
            GitOperations.forRepo(source).removeWorktree(normalized);
            Files.delete(owner);
            return new CleanupResult(true, "removed");
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException exc) {
            return new CleanupResult(false, exc.getMessage() == null ? exc.getClass().getSimpleName() : exc.getMessage());
        }
    }
}
