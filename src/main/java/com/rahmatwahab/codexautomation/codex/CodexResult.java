package com.rahmatwahab.codexautomation.codex;

/**
 * Parsed result from a Codex CLI execution.
 */
public record CodexResult(int exitCode, String nextPhase, String blocker) {
}
