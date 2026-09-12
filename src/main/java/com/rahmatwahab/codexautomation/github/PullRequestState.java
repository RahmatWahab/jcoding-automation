package com.rahmatwahab.codexautomation.github;

/**
 * Authoritative PR merge and head state from GitHub.
 */
public record PullRequestState(int number, String headSha, boolean merged) {
}
