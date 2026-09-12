package com.rahmatwahab.codexautomation.locking;

/**
 * Boundary for atomic execution ownership.
 */
public interface LockManager {
    LockHandle acquire(String uniquenessKey);
}
