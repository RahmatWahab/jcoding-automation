package com.rahmatwahab.codexautomation.provider;

import java.time.Instant;

/**
 * System UTC clock provider.
 */
public final class SystemClock implements Clock {
    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public String name() {
        return "system-clock";
    }
}
