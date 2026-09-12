package com.rahmatwahab.codexautomation.locking;

/**
 * Owned lock handle. Implementations must release only locks they own.
 */
public interface LockHandle extends AutoCloseable {
    String ownerToken();

    @Override
    void close();
}
