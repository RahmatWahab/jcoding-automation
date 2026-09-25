package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prompt-composition tests: {@code {{COMMON}}} expansion with appended
 * controller context, and phase→prompt mapping. Rendering is tested against a
 * {@code @TempDir} prompt tree; no external systems.
 */
class PromptRendererTest {

    @Test
    void renderPromptExpandsCommonAndContext(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("prompts"));
        Files.writeString(tmp.resolve("prompts").resolve("common.md"), "COMMON");
        Files.writeString(tmp.resolve("phase.md"), "{{COMMON}}");

        String rendered = PromptRenderer.render(tmp, "phase.md", Map.of("story", 45));

        assertThat(rendered).contains("COMMON");
        assertThat(rendered).contains("\"story\": 45");
        assertThat(rendered).contains("CONTROLLER CONTEXT (authoritative; do not modify):");
    }

    @Test
    void activeWorkerPhaseIsMappedFromPrompt() {
        assertThat(PromptRenderer.executionPhaseForPrompt("verify.md")).isEqualTo("VERIFYING");
        assertThat(PromptRenderer.executionPhaseForPrompt("reconcile.md")).isEqualTo("RECONCILING");
        assertThat(PromptRenderer.executionPhaseForPrompt("feedback.md")).isEqualTo("FIXING_FEEDBACK");
        assertThat(PromptRenderer.executionPhaseForPrompt("review.md")).isEqualTo("REVIEWING_CODE");
    }

    @Test
    void unknownPromptDefaultsToImplementing() {
        assertThat(PromptRenderer.executionPhaseForPrompt("unknown.md")).isEqualTo("IMPLEMENTING");
    }
}
