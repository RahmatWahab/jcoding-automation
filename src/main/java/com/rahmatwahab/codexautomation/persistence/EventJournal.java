package com.rahmatwahab.codexautomation.persistence;

/**
 * Append-only audit event boundary.
 */
public interface EventJournal {
    void append(JournalEvent event);
}
