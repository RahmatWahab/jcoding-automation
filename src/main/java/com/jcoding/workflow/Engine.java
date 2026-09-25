package com.jcoding.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, side-effect-free workflow decision primitives.
 *
 * <p>Pure workflow logic. GitHub
 * JSON objects are represented as {@code Map<String,Object>} and arrays as
 * {@code List<?>} for dynamic access, including {@code null} for absent values.</p>
 *
 * <p>This class holds the core engine: phase classification, story selection,
 * and the pure decision primitives.</p>
 */
public final class Engine {

    private Engine() {
    }

    // -- quota_error ---------------------------------------------------------

    private static final List<Pattern> QUOTA_PATTERNS = List.of(
            Pattern.compile(
                    "\\b(?:rate[ -]?limit|usage|quota)\\b.{0,60}\\b(?:exceeded|reached|hit|exhausted|unavailable)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile(
                    "\\b(?:5[ -]?hour|weekly)\\s+(?:usage\\s+)?limit\\b.{0,40}\\b(?:exceeded|reached|hit|exhausted)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btoo many requests\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byou(?:'|\u2019)ve\\s+(?:hit|reached)\\b.{0,60}\\blimit\\b",
                    Pattern.CASE_INSENSITIVE));

    /**
     * Detect explicit provider quota errors, not incidental diff text.
     *
     * <p>Generic words such as {@code limit} occur routinely in commands,
     * prompts, and source diffs (for example {@code gh pr list --limit 100}).
     * Only a complete provider-style message is evidence of quota exhaustion.</p>
     */
    public static boolean quotaError(String text) {
        if (text == null) {
            return false;
        }
        for (String line : text.split("\n", -1)) {
            String candidate = line.strip();
            if (candidate.isEmpty()
                    || candidate.startsWith("diff ")
                    || candidate.startsWith("+++")
                    || candidate.startsWith("---")
                    || candidate.startsWith("@@")) {
                continue;
            }
            for (Pattern pattern : QUOTA_PATTERNS) {
                if (pattern.matcher(candidate).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    // -- linked_story_numbers ------------------------------------------------

    private static final Pattern STORY_REFERENCE =
            Pattern.compile("(?<!\\w)#(\\d+)|/issues/(\\d+)(?!\\d)");

    /**
     * Child story issue numbers referenced by an EPIC body, preserving
     * first-seen order and excluding the EPIC itself. References may be
     * {@code #123} or Markdown links ending in {@code /issues/123}.
     */
    public static List<Integer> linkedStoryNumbers(Map<String, Object> epic) {
        String body = asString(epic.get("body"));
        int epicNumber = asInt(epic.get("number"));
        Matcher matcher = STORY_REFERENCE.matcher(body);
        // Preserve first-seen order while removing duplicates.
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            String hashGroup = matcher.group(1);
            String urlGroup = matcher.group(2);
            int number = Integer.parseInt(hashGroup != null ? hashGroup : urlGroup);
            seen.add(number);
        }
        List<Integer> result = new ArrayList<>();
        for (Integer number : seen) {
            if (number != epicNumber) {
                result.add(number);
            }
        }
        return result;
    }

    // -- pr_for_story --------------------------------------------------------

    /**
     * PRs explicitly associated with {@code story}.
     *
     * <p>References in a PR body are not all ownership signals: prerequisite,
     * related, and dependency references commonly mention other stories. Use
     * the story branch, title, or explicit ownership language instead.</p>
     */
    public static List<Map<String, Object>> prForStory(List<Map<String, Object>> prs, int story) {
        Pattern marker = Pattern.compile("(?<![A-Za-z0-9_])#" + story + "(?![0-9])");
        Pattern branch = Pattern.compile("^story/" + story + "(?:-|$)");
        Pattern bodyOwnership = Pattern.compile(
                "(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+#"
                        + story + "\\b|\\bimplements\\s+story\\s+#" + story + "\\b");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> pr : prs) {
            String body = asString(pr.get("body"));
            String title = asString(pr.get("title"));
            String headRefName = asString(pr.get("headRefName"));
            if (branch.matcher(headRefName).matches()
                    || marker.matcher(title).find()
                    || bodyOwnership.matcher(body).find()) {
                result.add(pr);
            }
        }
        return result;
    }

    // -- story_dependencies --------------------------------------------------

    private static final Pattern DEPENDENCY =
            Pattern.compile("(?:depends on|dependency)\\s*:?\\s*#(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Declared story dependencies ({@code depends on #N} / {@code dependency #N}). */
    public static Set<Integer> storyDependencies(Map<String, Object> story) {
        String body = asString(story.get("body"));
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = DEPENDENCY.matcher(body);
        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return result;
    }

    // -- select_story --------------------------------------------------------

    private static final Set<String> EXCLUDED_LABELS = Set.of("blocked", "deferred", "completed");

    /**
     * First eligible open story in order: not completed, state OPEN, no
     * excluded label, and all dependencies completed or CLOSED. Returns
     * {@code null} when none are eligible.
     */
    public static Map<String, Object> selectStory(List<Map<String, Object>> stories, Set<Integer> completed) {
        Map<Integer, Map<String, Object>> available = new LinkedHashMap<>();
        for (Map<String, Object> s : stories) {
            available.put(asInt(s.get("number")), s);
        }
        for (Map<String, Object> story : stories) {
            int number = asInt(story.get("number"));
            Set<String> labels = new LinkedHashSet<>();
            for (Object label : asList(story.get("labels"))) {
                if (label instanceof Map<?, ?> labelMap) {
                    labels.add(asString(labelMap.get("name")).toLowerCase());
                }
            }
            if (completed.contains(number) || !asString(story.get("state")).toUpperCase().equals("OPEN")) {
                continue;
            }
            boolean excluded = false;
            for (String label : labels) {
                if (EXCLUDED_LABELS.contains(label)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) {
                continue;
            }
            boolean blockedByDependency = false;
            for (int dep : storyDependencies(story)) {
                Map<String, Object> depStory = available.get(dep);
                String depState = depStory == null ? "" : asString(depStory.get("state")).toUpperCase();
                if (!completed.contains(dep) && !depState.equals("CLOSED")) {
                    blockedByDependency = true;
                    break;
                }
            }
            if (blockedByDependency) {
                continue;
            }
            return story;
        }
        return null;
    }

    // -- classify_pr ---------------------------------------------------------

    private static final Set<String> FAILED_CONCLUSIONS = Set.of("FAILURE", "CANCELLED", "TIMED_OUT");
    private static final Set<String> PENDING_STATUSES = Set.of("IN_PROGRESS", "QUEUED", "PENDING");

    /** Classify a PR into a workflow phase from authoritative GitHub facts. */
    public static String classifyPr(Map<String, Object> pr) {
        if (pr.get("mergedAt") != null) {
            return "MERGED";
        }
        if ("CLOSED".equals(pr.get("state"))) {
            return "BLOCKED_EXTERNAL";
        }
        if (Boolean.FALSE.equals(pr.get("reviewThreadsAvailable"))) {
            return "BLOCKED_EXTERNAL";
        }
        List<Object> checks = asList(pr.get("statusCheckRollup"));
        for (Object c : checks) {
            if (c instanceof Map<?, ?> check) {
                Object conclusion = check.get("conclusion");
                if (conclusion != null && FAILED_CONCLUSIONS.contains(conclusion)) {
                    return "CHANGES_REQUESTED";
                }
            }
        }
        for (Object c : checks) {
            if (c instanceof Map<?, ?> check) {
                Object status = check.get("status");
                if (status != null && PENDING_STATUSES.contains(status)) {
                    return "WAITING_FOR_CI";
                }
            }
        }
        for (Object t : asList(pr.get("reviewThreads"))) {
            if (t instanceof Map<?, ?> thread && !asBool(thread.get("isResolved"), true)) {
                return "CHANGES_REQUESTED";
            }
        }
        if (codexReviewPending(pr)) {
            return "WAITING_FOR_CODEX_REVIEW";
        }
        Object decision = pr.get("reviewDecision");
        if ("CHANGES_REQUESTED".equals(decision)) {
            return "CHANGES_REQUESTED";
        }
        if ("APPROVED".equals(decision)) {
            return "READY_TO_MERGE";
        }
        // Autonomous ready gate. There is no human reviewer, so the workflow
        // must never park in a "wait for human" state. Reaching this point means
        // CI is not failing or pending and there are no unresolved review
        // threads. If the Codex connector has reviewed THIS head (either a clean
        // "no issues" pass, or a review whose findings are now all resolved),
        // the PR is ready — post READY TO MERGE / auto-merge. The controller
        // still never merges unless auto_merge is enabled, and the manual-merge
        // gate is preserved for auto_merge=false.
        if (!codexReviewPending(pr)) {
            return "READY_TO_MERGE";
        }
        // Codex has not yet reviewed this exact head — wait for the connector
        // review (which the controller triggers via "@codex review"), not for a
        // human.
        return "WAITING_FOR_CODEX_REVIEW";
    }

    // -- codex_review_pending ------------------------------------------------

    /** True until the Codex Connector has reviewed this exact PR head. */
    public static boolean codexReviewPending(Map<String, Object> pr) {
        String head = asString(pr.get("headRefOid"));
        if (head.isEmpty()) {
            return true;
        }
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (Object r : asList(pr.get("reviews"))) {
            if (r instanceof Map<?, ?> review) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) review;
                Object author = typed.get("author");
                String login = author instanceof Map<?, ?> a ? asString(a.get("login")) : "";
                if (isCodexConnector(login)) {
                    reviews.add(typed);
                }
            }
        }
        if (!reviews.isEmpty()) {
            reviews.sort((a, b) -> asString(a.get("submittedAt")).compareTo(asString(b.get("submittedAt"))));
            Map<String, Object> last = reviews.get(reviews.size() - 1);
            Object commit = last.get("commit");
            String oid = commit instanceof Map<?, ?> c ? asString(c.get("oid")) : "";
            if (oid.equals(head)) {
                return false;
            }
        }
        // Clean-result path: when Codex finds no issues it does not submit a
        // review object at the head — it posts a "Didn't find any major issues"
        // issue comment (and/or a 👍 reaction) that references the reviewed
        // commit. Treat such a connector comment for the current head as a
        // completed, clean review so the gate closes instead of waiting forever.
        return !hasCodexCleanReviewComment(pr, head);
    }

    /** Codex connector login as it appears in reviews or issue comments. */
    private static boolean isCodexConnector(String login) {
        return "chatgpt-codex-connector".equals(login)
                || "chatgpt-codex-connector[bot]".equals(login);
    }

    /**
     * Whether an {@code @codex review} trigger comment is actually present on the
     * live PR. Used to detect a <em>false</em> {@code WAITING_FOR_CODEX_REVIEW}
     * state: the controller believes it is waiting for a connector review, but no
     * trigger comment exists on the PR (it was never posted, was stripped, or the
     * PR object was refreshed and lost it). Such a wait can never resolve on its
     * own, so it must be surfaced as a deviation for the WorkerSupervisor to
     * recover — the fully-autonomous system never waits on a review it did not
     * request. The trigger is posted by the controller, so any author is accepted;
     * matching is on the exact {@code @codex review} directive in the body.
     */
    static boolean hasCodexReviewTrigger(Map<String, Object> pr) {
        if (pr == null) {
            return false;
        }
        for (Object c : asList(pr.get("comments"))) {
            if (!(c instanceof Map<?, ?> cm)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> comment = (Map<String, Object>) cm;
            String body = asString(comment.get("body"));
            if (body.toLowerCase().contains("@codex review")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect a connector-posted clean-review comment ("no major issues") that
     * references the current head commit (by full or short SHA in the
     * "Reviewed commit:" line).
     */
    static boolean hasCodexCleanReviewComment(Map<String, Object> pr, String head) {
        if (head.isEmpty()) {
            return false;
        }
        String shortHead = head.length() >= 10 ? head.substring(0, 10) : head;
        for (Object c : asList(pr.get("comments"))) {
            if (!(c instanceof Map<?, ?> cm)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> comment = (Map<String, Object>) cm;
            Object author = comment.get("author");
            String login = author instanceof Map<?, ?> a ? asString(a.get("login"))
                    : asString(comment.get("login"));
            if (author == null && comment.get("user") instanceof Map<?, ?> u) {
                login = asString(u.get("login"));
            }
            if (!isCodexConnector(login)) {
                continue;
            }
            String body = asString(comment.get("body"));
            String lower = body.toLowerCase();
            boolean clean = lower.contains("didn't find any major issues")
                    || lower.contains("didnt find any major issues")
                    || lower.contains("no major issues");
            boolean refsHead = body.contains(head) || body.contains(shortHead);
            if (clean && refsHead) {
                return true;
            }
        }
        return false;
    }

    // -- next_action ---------------------------------------------------------

    private static final Set<String> WAIT_PHASES = Set.of(
            "PAUSED_QUOTA", "WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW",
            "WAITING_FOR_HUMAN_REVIEW", "WAITING_FOR_MANUAL_MERGE", "READY_TO_MERGE");
    private static final Set<String> STOP_PHASES = Set.of(
            "BLOCKED_REQUIREMENTS", "BLOCKED_EXTERNAL", "CONFLICT_DETECTED");

    // -- deviation detection (generic supervisor sensor) ---------------------

    /**
     * A detected workflow deviation: the controller's deterministic signal that
     * something is incorrect, stalled, or cannot complete, so the reasoning
     * (reconcile) supervisor should diagnose and propose a bounded recovery.
     *
     * @param code       stable machine code for routing/telemetry
     * @param detail     human-readable explanation (also injected into the prompt)
     * @param recoverable true when the controller should escalate to reasoning;
     *                    false for hard-safety stops that must not be overridden
     */
    public record Deviation(String code, String detail, boolean recoverable) {
    }

    /**
     * Generic deviation detector. Inspects the durable state and the current PR
     * snapshot and returns a {@link Deviation} when the workflow is in an
     * incorrect, stalled, or incomplete condition that the deterministic path
     * cannot itself resolve. Returns {@code null} when the workflow is healthy
     * (progressing or legitimately waiting).
     *
     * <p>Pure and side-effect free so it is fully unit-testable. The controller
     * decides what to do with the result (escalate to reconcile, or refuse to
     * override a hard-safety stop).</p>
     */
    public static Deviation detectDeviation(Map<String, Object> state, Map<String, Object> pr,
                                            int maxPhaseAttempts) {
        String phase = asString(state.get("phase"));
        boolean hasPr = state.get("current_pr") != null;
        boolean committed = !asString(state.get("last_processed_commit")).isEmpty();
        boolean hasBranch = !asString(state.get("branch")).isEmpty();
        int attempts = (int) asLongOrDefault(state.get("phase_attempt"), 0);

        // Terminal stuck head: a head that was already determined unrecoverable
        // (worker cannot advance it and nothing is deliverable) must NOT be
        // re-escalated — that is what caused an unbounded reconcile/feedback
        // loop. Only a genuinely new head clears this. Return no deviation so
        // the controller leaves it in its terminal blocked state.
        String stuckHead = asString(state.get("stuck_head"));
        String currentHead = pr == null ? "" : asString(pr.get("headRefOid"));
        if (!stuckHead.isEmpty() && stuckHead.equals(currentHead)) {
            return null;
        }

        // Hard-safety stops: real conflicts must be reconciled, but the
        // supervisor must never be told it can force past them.
        if ("CONFLICT_DETECTED".equals(phase)) {
            return new Deviation("CONFLICT", "Conflicting state requires reconciliation: "
                    + asString(state.get("error")), true);
        }

        // Committed implementation but no PR: work is done yet undelivered.
        if (committed && hasBranch && !hasPr) {
            return new Deviation("COMMITTED_NO_PR",
                    "Implementation is committed on " + asString(state.get("branch"))
                    + " but no pull request exists; the delivery step did not complete.", true);
        }

        // Blocked phases that are not hard-external: escalate for diagnosis.
        if ("BLOCKED_EXTERNAL".equals(phase) || "BLOCKED_REQUIREMENTS".equals(phase)
                || "CODEX_FAILURE".equals(phase) || "FAILED_RECOVERABLE".equals(phase)) {
            return new Deviation("BLOCKED_PHASE",
                    "Workflow is in blocked phase " + phase + ": " + asString(state.get("error")), true);
        }

        // Stalled: the same phase re-entered too many times without progress.
        if (attempts > Math.max(1, maxPhaseAttempts)
                && !WAIT_PHASES.contains(phase) && !"MERGED".equals(phase)) {
            return new Deviation("STALLED_PHASE",
                    "Phase " + phase + " re-entered " + attempts
                    + " times without completing; likely stalled.", true);
        }

        // Cross-story contamination: a review cursor that does not belong to the
        // current PR head indicates stale leaked state.
        if (hasPr && pr != null) {
            String head = asString(pr.get("headRefOid"));
            String connectorCursor = asString(state.get("last_connector_review_commit"));
            if (!head.isEmpty() && !connectorCursor.isEmpty()
                    && !connectorCursor.equals(head)
                    && !asString(state.get("last_processed_commit")).equals(connectorCursor)) {
                // Only a deviation if the cursor references neither the head nor
                // the delivered commit — i.e. it is stale/foreign.
                boolean cursorKnown = false;
                for (Object r : asList(pr.get("reviews"))) {
                    if (r instanceof Map<?, ?> rm) {
                        Object c = ((Map<?, ?>) rm).get("commit");
                        if (c instanceof Map<?, ?> cm && head.equals(asString(cm.get("oid"))))  {
                            cursorKnown = true;
                            break;
                        }
                    }
                }
                if (!cursorKnown && !connectorCursor.equals(head)) {
                    return new Deviation("STALE_REVIEW_CURSOR",
                            "last_connector_review_commit " + connectorCursor
                            + " does not match the current PR head " + head
                            + "; stale cross-story state.", true);
                }
            }
        }

        // False WAITING_FOR_CODEX_REVIEW: the controller believes it is waiting
        // for a connector review, but the live PR has NO @codex review trigger
        // comment and the connector review is still pending for the head. Such a
        // wait can never resolve on its own (nothing was requested), so — in a
        // fully autonomous system with no human — surface it as a recoverable
        // deviation so the WorkerSupervisor (reconcile) re-checks the real PR
        // status and recovers (re-trigger the review, resume, or advance).
        // Without this the run waits forever, because WAIT_PHASES are otherwise
        // trusted blindly. Placed after the more specific stale-cursor check so
        // that diagnosis wins when it applies.
        if ("WAITING_FOR_CODEX_REVIEW".equals(phase) && hasPr && pr != null
                && !currentHead.isEmpty()
                && codexReviewPending(pr)
                && !hasCodexReviewTrigger(pr)) {
            return new Deviation("STALLED_REVIEW_TRIGGER",
                    "Phase is WAITING_FOR_CODEX_REVIEW but no @codex review trigger is present "
                    + "on PR head " + (currentHead.length() >= 10 ? currentHead.substring(0, 10)
                    : currentHead) + "; the review was never requested or the trigger was lost.",
                    true);
        }

        return null;
    }

    /** Determine the controller action for a phase without side effects. */
    public static String nextAction(String phase, boolean hasNewFeedback) {
        if (hasNewFeedback) {
            return "RUN_FEEDBACK";
        }
        if (WAIT_PHASES.contains(phase)) {
            return "WAIT";
        }
        if (phase.equals("CHANGES_REQUESTED") || phase.equals("FIXING_FEEDBACK")) {
            return "RUN_FEEDBACK";
        }
        if (phase.equals("MERGED")) {
            return "ADVANCE_STORY";
        }
        if (STOP_PHASES.contains(phase)) {
            return "STOP";
        }
        return "RUN_CODEX";
    }

    public static String nextAction(String phase) {
        return nextAction(phase, false);
    }

    // -- review_events -------------------------------------------------------

    /**
     * Chronologically ordered review/comment events, optionally starting after
     * a cursor id. A missing cursor fails closed (returns empty) to avoid
     * replaying historical events after a snapshot mismatch.
     */
    public static List<Map<String, Object>> reviewEvents(Map<String, Object> pr, String afterId) {
        List<Map<String, Object>> events = new ArrayList<>();
        List<Object> reviewsAndComments = new ArrayList<>();
        reviewsAndComments.addAll(asList(pr.get("reviews")));
        reviewsAndComments.addAll(asList(pr.get("comments")));
        for (Object obj : reviewsAndComments) {
            if (!(obj instanceof Map<?, ?> item)) {
                continue;
            }
            String identifier = item.get("id") == null ? "" : String.valueOf(item.get("id"));
            Object authorObj = item.get("author");
            String author = authorObj instanceof Map<?, ?> a ? asString(a.get("login")) : "";
            String body = asString(item.get("body"));
            if (!identifier.isEmpty() && !body.strip().isEmpty()) {
                Object commit = item.get("commit");
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("id", identifier);
                event.put("author", author);
                event.put("body", body);
                event.put("submittedAt", item.get("submittedAt"));
                event.put("commit", commit instanceof Map<?, ?> c ? c.get("oid") : null);
                event.put("resolved", false);
                events.add(event);
            }
        }
        for (Object obj : asList(pr.get("reviewThreads"))) {
            if (!(obj instanceof Map<?, ?> thread)) {
                continue;
            }
            if (asBool(thread.get("isResolved"), true)) {
                continue;
            }
            Object commentsObj = thread.get("comments");
            List<Object> nodes = commentsObj instanceof Map<?, ?> cm ? asList(cm.get("nodes")) : List.of();
            for (Object nodeObj : nodes) {
                if (!(nodeObj instanceof Map<?, ?> item)) {
                    continue;
                }
                String identifier = item.get("id") == null ? "" : String.valueOf(item.get("id"));
                String body = asString(item.get("body"));
                if (!identifier.isEmpty() && !body.strip().isEmpty()) {
                    Object authorObj = item.get("author");
                    Object commit = item.get("commit");
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", identifier);
                    event.put("thread_id", thread.get("id"));
                    event.put("author", authorObj instanceof Map<?, ?> a ? asString(a.get("login")) : "");
                    event.put("body", body);
                    event.put("submittedAt", item.get("createdAt"));
                    event.put("commit", commit instanceof Map<?, ?> c ? c.get("oid") : null);
                    event.put("resolved", false);
                    events.add(event);
                }
            }
        }
        // Stable sort by submittedAt with null treated as empty string.
        events.sort((a, b) -> {
            String sa = a.get("submittedAt") == null ? "" : String.valueOf(a.get("submittedAt"));
            String sb = b.get("submittedAt") == null ? "" : String.valueOf(b.get("submittedAt"));
            return sa.compareTo(sb);
        });
        if (afterId != null) {
            for (int index = 0; index < events.size(); index++) {
                if (afterId.equals(events.get(index).get("id"))) {
                    return new ArrayList<>(events.subList(index + 1, events.size()));
                }
            }
            // Missing cursor: fail closed rather than replay history.
            return new ArrayList<>();
        }
        return events;
    }

    public static List<Map<String, Object>> reviewEvents(Map<String, Object> pr) {
        return reviewEvents(pr, null);
    }

    // -- actionable_feedback -------------------------------------------------

    private static final Set<String> BOT_NAMES = Set.of(
            "chatgpt-codex-connector", "copilot-pull-request-reviewer", "github-actions[bot]");
    private static final Set<String> IGNORED_BODIES = Set.of("@codex review", "ready to merge");
    private static final List<String> EXPLICIT_TERMS = List.of(
            "request changes", "must fix", "should fix", "please fix", "bug:", "blocking:");

    /**
     * Feedback items that require worker action. Unresolved inline findings are
     * durable work items and remain actionable across runs (even past a cursor)
     * until explicitly resolved.
     */
    public static List<Map<String, Object>> actionableFeedback(Map<String, Object> pr, String afterId) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> events = reviewEvents(pr, afterId);
        // Unresolved inline findings must not disappear after another finding
        // is fixed and the cursor advances past them.
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            seen.add(asString(event.get("id")));
        }
        for (Map<String, Object> event : reviewEvents(pr)) {
            boolean hasThread = event.get("thread_id") != null;
            boolean resolved = asBool(event.get("resolved"), false);
            if (hasThread && !resolved && !seen.contains(asString(event.get("id")))) {
                events.add(event);
            }
        }
        for (Map<String, Object> event : events) {
            if (asBool(event.get("resolved"), false)) {
                continue;
            }
            String body = asString(event.get("body")).toLowerCase();
            if (IGNORED_BODIES.contains(body.strip())) {
                continue;
            }
            if (body.startsWith("<!-- codex-automation-fix:")) {
                continue;
            }
            boolean explicit = false;
            for (String term : EXPLICIT_TERMS) {
                if (body.contains(term)) {
                    explicit = true;
                    break;
                }
            }
            boolean hasThread = event.get("thread_id") != null;
            boolean resolved = asBool(event.get("resolved"), false);
            if (hasThread && !resolved) {
                // Unresolved inline review is an explicit work item even with a
                // descriptive P1/P2 heading rather than a legacy keyword.
                result.add(event);
            } else if (!BOT_NAMES.contains(asString(event.get("author"))) || explicit) {
                result.add(event);
            }
        }
        return result;
    }

    public static List<Map<String, Object>> actionableFeedback(Map<String, Object> pr) {
        return actionableFeedback(pr, null);
    }

    // -- reconcile -----------------------------------------------------------

    /**
     * Decide (phase, action) for the current PR without side effects. A
     * two-element array is returned as (phase, action).
     */
    public static String[] reconcile(Map<String, Object> state, Map<String, Object> pr, boolean hasNewFeedback) {
        if (pr == null) {
            return new String[] {"IMPLEMENTING", "RUN_CODEX"};
        }
        String phase = classifyPr(pr);
        if (hasNewFeedback) {
            return new String[] {"FIXING_FEEDBACK", "RUN_FEEDBACK"};
        }
        if (phase.equals("MERGED")) {
            return new String[] {"MERGED", "ADVANCE_STORY"};
        }
        return new String[] {phase, nextAction(phase)};
    }

    public static String[] reconcile(Map<String, Object> state, Map<String, Object> pr) {
        return reconcile(state, pr, false);
    }

    // -- apply_codex_result --------------------------------------------------

    private static Pattern footerPattern(String key) {
        return Pattern.compile("^" + key + ":\\s*(.+)$", Pattern.MULTILINE);
    }

    private static String lastMatch(Pattern pattern, String output) {
        Matcher matcher = pattern.matcher(output);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1).strip();
        }
        return last;
    }

    /**
     * Apply a worker result to a copy of state without performing I/O or
     * GitHub actions. Non-zero exit codes are classified (signal, timeout,
     * quota, generic failure). A zero exit still fails closed unless the
     * structured footer proves SUCCESS with complete evidence.
     */
    public static Map<String, Object> applyCodexResult(Map<String, Object> state, int exitCode,
                                                        String output, int retrySeconds) {
        Map<String, Object> result = new LinkedHashMap<>(state);
        if (exitCode != 0) {
            if (exitCode < 0) {
                result.put("phase", "FAILED_RECOVERABLE");
                result.put("error", "Codex worker terminated by signal " + (-exitCode)
                        + "; reconcile before retrying");
            } else if (exitCode == 124) {
                result.put("phase", "FAILED_RECOVERABLE");
                result.put("error", "Codex worker timed out; reconcile the worktree before retrying");
            } else if (quotaError(output)) {
                result.put("phase", "PAUSED_QUOTA");
                result.put("error", "Codex usage limit detected");
                result.put("retry_at", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                        .plusSeconds(retrySeconds).toString());
            } else {
                result.put("phase", "CODEX_FAILURE");
                result.put("error", "Codex worker failed");
            }
            return result;
        }
        String status = lastMatch(footerPattern("RESULT_STATUS"), output);
        String phase = lastMatch(footerPattern("RESULT_NEXT_PHASE"), output);
        String blocker = lastMatch(footerPattern("RESULT_BLOCKER"), output);
        String evidence = lastMatch(footerPattern("RESULT_EVIDENCE"), output);
        if (status == null) {
            status = "UNSTRUCTURED";
        }
        if (evidence == null) {
            evidence = "";
        }
        // A fix can be complete yet unable to run heavy validation (e.g. Docker
        // E2E) within the phase budget. In that case the worker may declare
        // SUCCESS with RESULT_EVIDENCE: DEFERRED_TO_CI and RESULT_NEXT_PHASE:
        // WAITING_FOR_CI. This is a deliverable result: the controller commits
        // and pushes, and CI (already a merge gate) becomes the validation
        // authority. The manual-merge gate still protects the final merge.
        boolean deferredToCi = "SUCCESS".equals(status)
                && "DEFERRED_TO_CI".equals(evidence)
                && "WAITING_FOR_CI".equals(phase);
        boolean acceptableEvidence = "COMPLETE".equals(evidence) || deferredToCi;
        if ("BLOCKED".equals(status) || "BLOCKED".equals(phase)) {
            result.put("phase", "BLOCKED_REQUIREMENTS");
        } else if (!"SUCCESS".equals(status) || phase == null || !PhaseModel.PHASES.contains(phase)
                || !acceptableEvidence) {
            result.put("phase", "BLOCKED_REQUIREMENTS");
        } else {
            result.put("phase", phase);
        }
        result.put("result_status", status);
        result.put("result_evidence", evidence);
        if (deferredToCi) {
            result.put("delivery_mode", "CI_DEFERRED");
        } else {
            result.remove("delivery_mode");
        }
        if (blocker != null && !blocker.equals("NONE")) {
            result.put("blockers", new ArrayList<>(List.of(blocker)));
        } else if (!"SUCCESS".equals(status)) {
            result.put("blockers", new ArrayList<>(List.of("Codex returned no valid SUCCESS result")));
        } else if (phase == null || !PhaseModel.PHASES.contains(phase)) {
            result.put("blockers", new ArrayList<>(List.of("Codex returned SUCCESS with an invalid next phase")));
        } else if (!acceptableEvidence) {
            result.put("blockers", new ArrayList<>(
                    List.of("Codex returned SUCCESS without complete acceptance/validation evidence")));
        } else {
            result.put("blockers", new ArrayList<>());
            result.remove("error");
        }
        return result;
    }

    public static Map<String, Object> applyCodexResult(Map<String, Object> state, int exitCode, String output) {
        return applyCodexResult(state, exitCode, output, 1800);
    }

    // -- parse_decision ------------------------------------------------------

    private static final List<String> DECISION_KEYS = List.of(
            "DECISION_ACTION", "DECISION_REASON", "BLOCKER_CLASS", "RECOVERY_ACTION");

    private static final Map<String, Pattern> DECISION_ALIASES = Map.of(
            "decision_action", Pattern.compile("^Decision:\\s*`?([A-Z_]+)`?\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            "decision_reason", Pattern.compile("^Decision reason:\\s*(.+)$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            "blocker_class", Pattern.compile("^Blocker class:\\s*`?([A-Z_]+)`?\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE),
            "recovery_action", Pattern.compile("^Recovery action:\\s*(.+)$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE));

    /**
     * Parse reconciliation decision fields. The exact machine markers are
     * authoritative (last occurrence wins); structured-prose aliases are
     * accepted only when the exact marker was omitted.
     */
    public static Map<String, String> parseDecision(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : DECISION_KEYS) {
            String match = lastMatch(footerPattern(key), output);
            if (match != null) {
                values.put(key.toLowerCase(), match);
            }
        }
        for (Map.Entry<String, Pattern> entry : DECISION_ALIASES.entrySet()) {
            if (!values.containsKey(entry.getKey())) {
                Matcher matcher = entry.getValue().matcher(output);
                String last = null;
                while (matcher.find()) {
                    last = matcher.group(1).strip();
                }
                if (last != null) {
                    // Trim surrounding whitespace and backticks.
                    values.put(entry.getKey(), stripBackticks(last));
                }
            }
        }
        return values;
    }

    private static String stripBackticks(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '`') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '`') {
            end--;
        }
        return value.substring(start, end);
    }

    // -- typed accessors for dynamic map/list/null values --------------------

    static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static boolean asBool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return defaultValue;
    }

    /** Numeric value as long, or the default when absent/non-numeric. */
    static long asLongOrDefault(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exc) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }
}
