package com.rahmatwahab.codexautomation.persistence;

import java.time.Instant;

/**
 * Structured audit event identity.
 */
public record JournalEvent(String executionId, String action, String result, Instant occurredAt) {
}
