package com.rahmatwahab.codexautomation.git;

/**
 * Boundary for local Git and remote branch operations.
 */
public interface GitClient {
    GitHead currentHead();
}
