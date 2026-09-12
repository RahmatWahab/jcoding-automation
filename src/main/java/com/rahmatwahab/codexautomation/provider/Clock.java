package com.rahmatwahab.codexautomation.provider;

import java.time.Instant;

/**
 * Testable time source for workflow and recovery decisions.
 */
public interface Clock extends AutomationProvider {
    Instant now();
}
