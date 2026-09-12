package com.rahmatwahab.codexautomation.logging;

/**
 * Boundary for removing secrets from logs, state, prompts, and reports.
 */
public interface Redactor {
    String redact(String value);
}
