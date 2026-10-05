package com.jcoding.workflow;

import java.util.List;
import java.util.Map;

/** Controller-owned identity and durable storage. All methods run sequentially. */
interface ReviewContext {
    /** Exact content identity including tracked, staged, deleted and untracked source files. */
    String snapshot();

    List<String> changedPaths();

    /** Identity of rendered review prompt AND reviewer configuration (model, effort, etc.). */
    String signature(InternalReviewGate.Specialist specialist);

    /** Optional caller-owned JSON-compatible durable cache, persisted by persist(). */
    default Map<String, Object> cache() {
        return new java.util.LinkedHashMap<>();
    }

    /** Previous durable findings for this story; retained until the specialist passes. */
    default List<InternalReviewGate.Finding> unresolved() {
        return List.of();
    }

    /** Persist synchronously; failure must propagate and prevent delivery. */
    void persist(InternalReviewGate.Report report);
}
