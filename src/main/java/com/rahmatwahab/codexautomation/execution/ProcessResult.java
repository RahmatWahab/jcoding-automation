package com.rahmatwahab.codexautomation.execution;

/**
 * Captured result of an external command.
 */
public record ProcessResult(int exitCode, String standardOutput, String standardError) {
}
