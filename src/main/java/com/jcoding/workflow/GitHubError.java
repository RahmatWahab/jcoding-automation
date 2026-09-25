package com.jcoding.workflow;

/** Raised for any GitHub CLI failure, timeout, or ambiguous response. */
public class GitHubError extends RuntimeException {

    public GitHubError(String message) {
        super(message);
    }

    public GitHubError(String message, Throwable cause) {
        super(message, cause);
    }
}
