package com.rahmatwahab.codexautomation.logging;

/**
 * Structured audit logging boundary.
 */
public interface AuditLogger {
    void info(String action, String result);
}
