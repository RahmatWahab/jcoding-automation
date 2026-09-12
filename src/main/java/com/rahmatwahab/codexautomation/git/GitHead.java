package com.rahmatwahab.codexautomation.git;

/**
 * Current Git branch and commit identity.
 */
public record GitHead(String branch, String commitSha) {
}
