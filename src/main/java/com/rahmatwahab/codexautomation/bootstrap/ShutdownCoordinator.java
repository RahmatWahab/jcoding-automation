package com.rahmatwahab.codexautomation.bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates graceful shutdown state for the bootstrap application.
 */
public final class ShutdownCoordinator {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean hookRegistered = new AtomicBoolean(false);

    public void registerShutdownHook() {
        if (hookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::markStopped, "jcoding-automation-shutdown"));
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public void markStopped() {
        running.set(false);
    }
}
