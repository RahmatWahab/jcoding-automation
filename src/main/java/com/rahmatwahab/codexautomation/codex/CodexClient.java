package com.rahmatwahab.codexautomation.codex;

/**
 * Boundary for Codex CLI execution.
 */
public interface CodexClient {
    CodexResult execute(CodexRequest request);
}
