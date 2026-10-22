package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable local backups keep parked work separate from the next story. */
final class WorktreeRecovery {
    static final String KEY = "worktree_checkpoints";

    private WorktreeRecovery() { }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkpoints(Map<String, Object> state) {
        return (Map<String, Object>) state.computeIfAbsent(KEY, ignored -> new LinkedHashMap<>());
    }

    /** Recover old parked runs too, before any checkout can carry their changes away. */
    static void isolateParkedWork(Map<String, Object> state, GitOperations git, Runnable save) {
        String branch = git.currentBranchName();
        String active = Engine.asString(state.get("branch"));
        if (branch.equals(active)) return;
        Object pending = checkpoints(state).get(branch);
        if (pending instanceof Map<?, ?> record && "CAPTURING".equals(record.get("status"))) {
            preserve(state, git, branch, Engine.asInt(record.get("story")), save);
            return;
        }
        if (git.classifyWorktree(state) == GitOperations.WorktreeState.CLEAN) return;
        Object parked = state.get(ParkPolicy.STATE_KEY);
        if (!(parked instanceof Map<?, ?> records)) return;
        for (var entry : records.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> record)
                    || !(record.get("checkpoint") instanceof Map<?, ?> checkpoint)) continue;
            if (branch.equals(checkpoint.get("branch"))) {
                preserve(state, git, branch, Integer.parseInt(entry.getKey().toString()), save);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void preserve(Map<String, Object> state, GitOperations git, String branch,
                         int story, Runnable save) {
        if (branch.isBlank() || !branch.equals(git.currentBranchName())) {
            throw new IllegalStateException("Cannot preserve work without confirmed story branch ownership");
        }
        Map<String, Object> records = checkpoints(state);
        Map<String, Object> record = records.get(branch) instanceof Map<?, ?> existing
                ? (Map<String, Object>) existing : null;
        if (record == null || !"CAPTURING".equals(record.get("status"))) {
            if (git.classifyWorktree(state) == GitOperations.WorktreeState.CLEAN) return;
            if (record != null && !"RESTORED".equals(record.get("status"))) {
                throw new IllegalStateException("Unrestored checkpoint already exists for " + branch);
            }
            record = new LinkedHashMap<>();
            record.put("story", story);
            record.put("branch", branch);
            record.put("base_head", git.head());
            record.put("fingerprint", git.recoveryFingerprint());
            record.put("paths", git.changedSourcePaths());
            record.put("ref", "refs/jcoding/checkpoints/story-" + story + "-" + UUID.randomUUID());
            record.put("status", "CAPTURING");
            records.put(branch, record);
            save.run(); // Write intent before git stash changes the working tree.
        }
        record.put("sha", git.preserveWorktree(Engine.asString(record.get("ref"))));
        record.put("status", "PRESERVED");
        save.run();
    }

    @SuppressWarnings("unchecked")
    static void restoreActiveWork(Map<String, Object> state, GitOperations git, Runnable save) {
        String branch = Engine.asString(state.get("branch"));
        Object value = checkpoints(state).get(branch);
        if (!(value instanceof Map<?, ?>)) return;
        Map<String, Object> record = (Map<String, Object>) value;
        if ("RESTORED".equals(record.get("status"))) return;
        if (!branch.equals(git.currentBranchName())
                || Engine.asInt(record.get("story")) != Engine.asInt(state.get("current_story"))) {
            throw new IllegalStateException("Checkpoint belongs to a different story/branch");
        }
        if ("CAPTURING".equals(record.get("status"))) {
            preserve(state, git, branch, Engine.asInt(record.get("story")), save);
        }
        if ("RESTORING".equals(record.get("status"))) {
            if (record.get("fingerprint").equals(git.recoveryFingerprint())) {
                record.put("status", "RESTORED");
                save.run();
                return;
            }
            throw new IllegalStateException("Interrupted checkpoint restore needs inspection: " + record.get("ref"));
        }
        if (!git.head().equals(record.get("base_head"))) {
            throw new IllegalStateException("Checkpoint base changed; inspect before applying " + record.get("ref"));
        }
        git.requireCleanWorktree();
        record.put("status", "RESTORING");
        save.run();
        git.restoreWorktree(Engine.asString(record.get("ref")));
        if (!record.get("fingerprint").equals(git.recoveryFingerprint())) {
            throw new IllegalStateException("Restored checkpoint did not match recorded content");
        }
        record.put("status", "RESTORED");
        save.run();
    }
}
