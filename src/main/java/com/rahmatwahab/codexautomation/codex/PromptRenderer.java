package com.rahmatwahab.codexautomation.codex;

/**
 * Boundary for versioned prompt rendering and context injection.
 */
public interface PromptRenderer {
    CodexRequest render(String phase);
}
