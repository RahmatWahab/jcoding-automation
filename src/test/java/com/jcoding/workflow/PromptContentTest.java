package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Prompt-content policy test. Verifies the prompt files preserve the
 * business-value testing policy and reconciliation semantics. Reads the
 * repository prompt files.
 */
class PromptContentTest {

    private static Path promptsDir() {
        String override = System.getProperty("codex.automation.root");
        Path root = override != null ? Path.of(override) : Path.of("").toAbsolutePath();
        return root.resolve("prompts");
    }

    private static String read(String name) throws Exception {
        return Files.readString(promptsDir().resolve(name));
    }

    @Test
    void testingPolicyRequiresBusinessValueSubstitutes() throws Exception {
        String common = read("common.md");
        String verify = read("verify.md");
        String reconcile = read("reconcile.md");

        assertThat(common).contains("BUSINESS-VALUE TESTING POLICY");
        assertThat(common).contains("realistic inputs");
        assertThat(verify).contains("later story");
        assertThat(verify).contains("business value");
        assertThat(verify).contains("deferred broader integration");
        assertThat(reconcile).contains("SUCCESS when the reconciliation decision is complete");
        assertThat(reconcile).contains("does\nnot mean the story implementation");
    }
}
