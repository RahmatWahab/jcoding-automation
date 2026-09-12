package com.rahmatwahab.codexautomation.review;

/**
 * Boundary for per-head review trigger and clean-PR gate decisions.
 */
public interface ReviewGate {
    boolean isCleanForMerge(String commitSha);
}
