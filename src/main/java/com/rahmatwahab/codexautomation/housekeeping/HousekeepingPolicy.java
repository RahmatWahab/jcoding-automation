package com.rahmatwahab.codexautomation.housekeeping;

import java.time.Duration;

/**
 * Log retention policy for operational housekeeping.
 */
public record HousekeepingPolicy(Duration retention) {
}
