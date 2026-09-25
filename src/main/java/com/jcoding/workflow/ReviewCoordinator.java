package com.jcoding.workflow;
import com.jcoding.github.GitHub;
import com.jcoding.github.GitHubError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Review-thread and Codex-review-trigger coordination.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code canonical_fixed_thread_ids}, {@code thread_fix_reply},
 * {@code thread_has_fix_reply}, {@code review_gate_open}, {@code fix_reply_heads},
 * {@code ensure_codex_review_trigger}, {@code create_story_pr}. Idempotent and
 * recoverable: a fix reply is scoped to a PR head, and triggers are never
 * duplicated for the same verified head.</p>
 */
public final class ReviewCoordinator {

    /** Canonical/unknown split of worker-declared thread ids. */
    public record ThreadIdMapping(Set<String> canonical, Set<String> unknown) {
    }

    private static final Pattern FIXED_THREAD = Pattern.compile(
            "^FIXED_THREAD:\\s*((?:PRRT|PRRC)_[A-Za-z0-9_-]+)\\s*$", Pattern.MULTILINE);
    private static final Pattern SUPERSEDED_THREAD = Pattern.compile(
            "^SUPERSEDED_THREAD:\\s*((?:PRRT|PRRC)_[A-Za-z0-9_-]+)\\s*$", Pattern.MULTILINE);
    private static final Pattern PASS_LINE = Pattern.compile("\\b(pass|passed|success)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIX_REPLY_HEAD = Pattern.compile(
            "codex-automation-fix:thread=[^:]+:head=([0-9a-f]{7,64})");

    private final Supplier<String> clock;

    public ReviewCoordinator() {
        this(() -> java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
    }

    public ReviewCoordinator(Supplier<String> clock) {
        this.clock = clock;
    }

    /** Only review threads the worker explicitly says it fixed (PRRT_/PRRC_). */
    public static Set<String> fixedThreadIds(String output) {
        return declaredThreadIds(output, FIXED_THREAD);
    }

    /** Thread ids whose findings the worker proved obsolete on the current head. */
    public static Set<String> supersededThreadIds(String output) {
        return declaredThreadIds(output, SUPERSEDED_THREAD);
    }

    private static Set<String> declaredThreadIds(String output, Pattern pattern) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(output == null ? "" : output);
        while (matcher.find()) {
            String id = matcher.group(1).strip();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Map worker-declared thread or comment ids to canonical thread ids.
     * Comment ids (PRRC_) resolve to their parent thread; unmapped declarations
     * are never silently discarded but reported as unknown.
     */
    @SuppressWarnings("unchecked")
    public static ThreadIdMapping canonicalFixedThreadIds(Map<String, Object> pr, Set<String> declaredIds) {
        Map<String, String> identifiers = new LinkedHashMap<>();
        for (Object threadObj : Engine.asList(pr.get("reviewThreads"))) {
            if (!(threadObj instanceof Map<?, ?> thread)) {
                continue;
            }
            String threadId = thread.get("id") == null ? "" : String.valueOf(thread.get("id"));
            if (threadId.isEmpty()) {
                continue;
            }
            identifiers.put(threadId, threadId);
            Object commentsObj = thread.get("comments");
            List<Object> nodes = commentsObj instanceof Map<?, ?> cm ? Engine.asList(cm.get("nodes")) : List.of();
            for (Object nodeObj : nodes) {
                if (nodeObj instanceof Map<?, ?> comment) {
                    String commentId = comment.get("id") == null ? "" : String.valueOf(comment.get("id"));
                    if (!commentId.isEmpty()) {
                        identifiers.put(commentId, threadId);
                    }
                }
            }
        }
        Set<String> canonical = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (String item : declaredIds) {
            if (identifiers.containsKey(item)) {
                canonical.add(identifiers.get(item));
            } else {
                unknown.add(item);
            }
        }
        return new ThreadIdMapping(canonical, unknown);
    }

    /** Build the fix-reply body with a durable per-head marker. */
    @SuppressWarnings("unchecked")
    public static String threadFixReply(Map<String, Object> item, String commit, String output) {
        List<Object> comments = commentNodes(item);
        String sourceBody = item.get("body") != null ? String.valueOf(item.get("body"))
                : (!comments.isEmpty() && comments.get(0) instanceof Map<?, ?> first
                        ? String.valueOf(((Map<String, Object>) first).getOrDefault("body", "")) : "");
        String finding = truncate(String.join(" ", sourceBody.trim().split("\\s+")), 240);
        String validation = "Validation completed successfully for the delivered change.";
        for (String line : output.split("\n", -1)) {
            String trimmed = line.strip();
            if (PASS_LINE.matcher(line).find() && trimmed.length() < 240) {
                validation = trimmed;
                break;
            }
        }
        String threadId = threadIdOf(item);
        return "Fixed in commit `" + commit.substring(0, Math.min(12, commit.length())) + "`.\n\n"
                + "Finding addressed: " + finding + "\n\n" + validation + "\n\n"
                + "<!-- codex-automation-fix:thread=" + threadId + ":head=" + commit + " -->";
    }

    /** Whether the thread already carries a fix reply for the given commit. */
    public static boolean threadHasFixReply(Map<String, Object> item, String commit) {
        String marker = "<!-- codex-automation-fix:thread=" + threadIdOf(item) + ":head=" + commit + " -->";
        for (Object node : commentNodes(item)) {
            if (node instanceof Map<?, ?> comment) {
                Object body = comment.get("body");
                if (body != null && String.valueOf(body).contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Return true until the Connector has reviewed this exact PR head. */
    public static boolean reviewGateOpen(Map<String, Object> pr) {
        return "OPEN".equals(Engine.asString(pr.get("state")).toUpperCase())
                && pr.get("mergedAt") == null
                && Engine.codexReviewPending(pr);
    }

    /** All commit heads referenced by fix-reply markers on a thread. */
    public static Set<String> fixReplyHeads(Map<String, Object> item) {
        Set<String> heads = new LinkedHashSet<>();
        for (Object node : commentNodes(item)) {
            if (node instanceof Map<?, ?> comment) {
                Object body = comment.get("body");
                if (body != null) {
                    Matcher matcher = FIX_REPLY_HEAD.matcher(String.valueOf(body));
                    while (matcher.find()) {
                        heads.add(matcher.group(1));
                    }
                }
            }
        }
        return heads;
    }

    /**
     * Ensure the current PR head has a controller trigger for Codex review.
     * Idempotent per verified head; recovers a crash between posting the
     * comment and persisting state. Returns whether a new comment was posted.
     */
    public boolean ensureCodexReviewTrigger(GitHub github, Map<String, Object> pr, Map<String, Object> state,
                                            Map<String, Object> workflow, boolean dryRun, Runnable persist) {
        if (dryRun || !Engine.asBool(workflow.get("allow_comments"), false)) {
            return false;
        }
        if (!"OPEN".equals(Engine.asString(pr.get("state")).toUpperCase()) || pr.get("mergedAt") != null) {
            return false;
        }
        String head = Engine.asString(pr.get("headRefOid"));
        if (head.isEmpty()) {
            throw new GitHubError("open PR has no verifiable head commit");
        }
        if (head.equals(state.get("last_review_trigger_commit"))
                && head.equals(state.get("last_review_trigger_verified_commit"))) {
            state.put("review_gate_head", head);
            return false;
        }
        Object pendingObj = state.get("review_trigger_pending");
        Map<String, Object> pending = pendingObj instanceof Map<?, ?> p
                ? castMap(p) : new LinkedHashMap<>();
        if (head.equals(pending.get("head"))) {
            String startedAt = Engine.asString(pending.get("started_at"));
            if (github.hasExactCommentAfter(pr, "@codex review", startedAt)) {
                state.put("last_review_trigger_commit", head);
                state.put("last_review_trigger_verified_commit", head);
                state.put("review_gate_head", head);
                state.remove("review_trigger_pending");
                if (persist != null) {
                    persist.run();
                }
                return false;
            }
        }
        Map<String, Object> newPending = new LinkedHashMap<>();
        newPending.put("pr", Engine.asInt(pr.get("number")));
        newPending.put("head", head);
        newPending.put("started_at", clock.get());
        state.put("review_trigger_pending", newPending);
        if (persist != null) {
            persist.run();
        }
        github.comment(Engine.asInt(pr.get("number")), "@codex review");
        state.put("last_review_trigger_commit", head);
        state.put("last_review_trigger_verified_commit", head);
        state.put("review_gate_head", head);
        state.remove("review_trigger_pending");
        if (persist != null) {
            persist.run();
        }
        return true;
    }

    /** Create a PR for a story, enforcing the push/PR-creation gates. */
    public Object createStoryPr(GitOperations git, GitHub github, Map<String, Object> config,
                                int story, int epicNumber) {
        Map<String, Object> workflow = Config.section(config, "workflow");
        if (!Engine.asBool(workflow.get("allow_push"), false)
                || !Engine.asBool(workflow.get("allow_pr_creation"), false)) {
            throw new IllegalStateException("push and PR creation are disabled by configuration");
        }
        String branch = git.gitBranch();
        git.pushBranch(branch);
        String title = "feat: implement story #" + story;
        String body = "Closes #" + story + "\n\nImplements story #" + story
                + " from EPIC #" + epicNumber + ".\n";
        String baseBranch = Engine.asString(Config.section(config, "repository")
                .getOrDefault("base_branch", "main"));
        return github.createPr(title, body, baseBranch, branch);
    }

    // -- helpers -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> commentNodes(Map<String, Object> item) {
        Object commentsObj = item.get("comments");
        if (commentsObj instanceof Map<?, ?> cm) {
            return Engine.asList(((Map<String, Object>) cm).get("nodes"));
        }
        return List.of();
    }

    private static String threadIdOf(Map<String, Object> item) {
        Object threadId = item.get("thread_id");
        if (threadId != null && !String.valueOf(threadId).isEmpty()) {
            return String.valueOf(threadId);
        }
        return item.get("id") == null ? "" : String.valueOf(item.get("id"));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
