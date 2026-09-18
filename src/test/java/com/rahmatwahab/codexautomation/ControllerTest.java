package com.rahmatwahab.codexautomation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Controller entry-point tests: timestamp helpers, the JSON job-start gate,
 * phase→prompt dispatch, and CLI argument parsing. Pure/in-memory and
 * temp-file based; no external systems.
 */
class ControllerTest {

    // -- due() ---------------------------------------------------------------

    @Test
    void dueTreatsMissingOrInvalidAsDue() {
        assertThat(Controller.due(null)).isTrue();
        assertThat(Controller.due("")).isTrue();
        assertThat(Controller.due("not-a-timestamp")).isTrue();
    }

    @Test
    void dueRespectsFutureAndPastTimestamps() {
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString();
        assertThat(Controller.due(future)).isFalse();
        assertThat(Controller.due(past)).isTrue();
    }

    // -- readJobControl() --

    @Test
    void jobStartTrueEnablesExecution(@TempDir Path tmp) throws Exception {
        Path control = tmp.resolve("job-control.json");
        Files.writeString(control, "{\"job_start\": true}");
        assertThat(Controller.readJobControl(control)).isTrue();
    }

    @Test
    void jobStartFalseOrMissingSkipsSafely(@TempDir Path tmp) throws Exception {
        Path control = tmp.resolve("job-control.json");
        Files.writeString(control, "{\"job_start\": false}");
        assertThat(Controller.readJobControl(control)).isFalse();

        Path empty = tmp.resolve("empty.json");
        Files.writeString(empty, "{}");
        assertThat(Controller.readJobControl(empty)).isFalse();

        assertThat(Controller.readJobControl(tmp.resolve("absent.json"))).isFalse();
    }

    @Test
    void jobStartNonBooleanIsNotTrue(@TempDir Path tmp) throws Exception {
        Path control = tmp.resolve("job-control.json");
        Files.writeString(control, "{\"job_start\": \"true\"}");
        assertThat(Controller.readJobControl(control)).isFalse();
    }

    // -- phasePromptFor() ----------------------------------------------------

    @Test
    void phasePromptDispatchMatchesReference() {
        assertThat(Controller.phasePromptFor("FIXING_FEEDBACK")).isEqualTo("prompts/feedback.md");
        assertThat(Controller.phasePromptFor("CHANGES_REQUESTED")).isEqualTo("prompts/feedback.md");
        assertThat(Controller.phasePromptFor("VERIFYING")).isEqualTo("prompts/verify.md");
        assertThat(Controller.phasePromptFor("REVIEWING_CODE")).isEqualTo("prompts/review.md");
        assertThat(Controller.phasePromptFor("BLOCKED_REQUIREMENTS")).isEqualTo("prompts/reconcile.md");
        assertThat(Controller.phasePromptFor("FAILED_RECOVERABLE")).isEqualTo("prompts/reconcile.md");
        assertThat(Controller.phasePromptFor("READY_TO_MERGE")).isEqualTo("prompts/final-review.md");
        assertThat(Controller.phasePromptFor("IDLE")).isEqualTo("prompts/discover.md");
        assertThat(Controller.phasePromptFor("IMPLEMENTING")).isEqualTo("prompts/implement.md");
    }

    // -- parseArgs() ---------------------------------------------------------

    @Test
    void parseArgsReadsOverridesAndForceRun() {
        Controller.Options options = Controller.parseArgs(new String[] {
                "--config", "custom/config.json", "--force-run", "--prompt", "prompts/verify.md"});
        assertThat(options.configPath()).isEqualTo(Path.of("custom/config.json"));
        assertThat(options.prompt()).isEqualTo("prompts/verify.md");
        assertThat(options.forceRun()).isTrue();
    }

    @Test
    void parseArgsDefaultsToJsonConfig() {
        Controller.Options options = Controller.parseArgs(new String[] {});
        assertThat(options.configPath()).isEqualTo(Path.of("config/config.json"));
        assertThat(options.jobControlPath()).isEqualTo(Path.of("config/job-control.json"));
        assertThat(options.forceRun()).isFalse();
    }
}
