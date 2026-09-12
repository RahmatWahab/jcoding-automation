package com.rahmatwahab.codexautomation.github;

/**
 * Boundary for GitHub issue, PR, review, CI, and merge-state operations.
 */
public interface GitHubClient {
    PullRequestState pullRequest(int number);
}
