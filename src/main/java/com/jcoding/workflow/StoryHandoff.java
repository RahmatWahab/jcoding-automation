package com.jcoding.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Suspend unsubmitted work while finishing an existing PR, without losing either story. */
final class StoryHandoff {
    private static final Set<String> IDENTITY = Set.of("current_story", "current_story_title",
            "current_story_url", "current_pr", "current_pr_url", "current_pr_head", "branch",
            "base_commit", "phase", "pr_state", "pr_review_decision", "ci_status", "unresolved_feedback");

    private StoryHandoff() { }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> suspended(Map<String, Object> state) {
        return (Map<String, Object>) state.computeIfAbsent("suspended_stories", k -> new LinkedHashMap<>());
    }

    static void suspend(Map<String, Object> state, GitOperations git, Runnable save) {
        int story = Engine.asInt(state.get("current_story"));
        if (state.get("current_pr") != null) throw new IllegalStateException("Cannot suspend an active PR");
        Set<String> keys = new LinkedHashSet<>(WorkflowStateCodec.STORY_SCOPED_KEYS);
        keys.addAll(IDENTITY);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        for (String key : keys) if (state.containsKey(key)) checkpoint.put(key, state.get(key));
        suspended(state).put(String.valueOf(story), checkpoint);
        save.run();
        if (git.classifyWorktree(state) != GitOperations.WorktreeState.CLEAN) {
            WorktreeRecovery.preserve(state, git, Engine.asString(state.get("branch")), story, save);
        }
        WorkflowStateCodec.clearStoryScoped(state);
        IDENTITY.forEach(state::remove);
        state.put("phase", "IDLE");
        save.run();
    }

    static boolean resume(Map<String, Object> state, int story) {
        Object saved = suspended(state).remove(String.valueOf(story));
        if (!(saved instanceof Map<?, ?> checkpoint)) return false;
        WorkflowStateCodec.clearStoryScoped(state);
        IDENTITY.forEach(state::remove);
        checkpoint.forEach((key, value) -> state.put(String.valueOf(key), value));
        // Re-evaluate previous failures after restoration, not against another story's tree.
        state.put("recovery_next_prompt", "prompts/reconcile.md");
        return true;
    }
}
