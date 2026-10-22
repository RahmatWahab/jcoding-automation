package com.jcoding.workflow;

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

    // -- waitBackoffDecision() -----------------------------------------------

    @Test
    void waitBackoffDisabledByDefaultAlwaysProceeds() {
        // pollSeconds = 0 (default): behaviour is unchanged — always proceed,
        // clearing any stale marker, even on a passive wait.
        assertThat(Controller.waitBackoffDecision("WAITING_FOR_CI", "", 0, false))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_CLEAR);
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
        assertThat(Controller.waitBackoffDecision("WAITING_FOR_CI", future, 0, false))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_CLEAR);
    }

    @Test
    void waitBackoffSkipsPassiveWaitBeforeNextPoll() {
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString();
        for (String phase : new String[] {"WAITING_FOR_CI", "WAITING_FOR_CODEX_REVIEW",
                "WAITING_FOR_HUMAN_REVIEW", "WAITING_FOR_MANUAL_MERGE"}) {
            assertThat(Controller.waitBackoffDecision(phase, future, 300, false))
                    .as("phase %s should back off before next poll", phase)
                    .isEqualTo(Controller.WaitBackoff.SKIP);
        }
    }

    @Test
    void waitBackoffProceedsAndSchedulesWhenDue() {
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).toString();
        assertThat(Controller.waitBackoffDecision("WAITING_FOR_CI", past, 300, false))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_SCHEDULE);
        // No marker yet also counts as due.
        assertThat(Controller.waitBackoffDecision("WAITING_FOR_CI", "", 300, false))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_SCHEDULE);
    }

    @Test
    void waitBackoffNeverDelaysActivePhases() {
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString();
        // An active phase must never be delayed, even with a stale future marker.
        assertThat(Controller.waitBackoffDecision("IMPLEMENTING", future, 300, false))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_CLEAR);
        assertThat(Controller.waitBackoffDecision("FIXING_FEEDBACK", future, 300, false))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_CLEAR);
    }

    @Test
    void waitBackoffForceRunBypassesSkip() {
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).toString();
        assertThat(Controller.waitBackoffDecision("WAITING_FOR_CI", future, 300, true))
                .isEqualTo(Controller.WaitBackoff.PROCEED_AND_CLEAR);
    }

    // -- skipIsRepeat() ------------------------------------------------------

    @Test
    void skipIsRepeatOnlyWhenSignatureMatchesNonBlankPrevious() {
        // First skip of a state (no/blank previous marker) is never a repeat.
        assertThat(Controller.skipIsRepeat(null, "1|IMPLEMENTING|t1")).isFalse();
        assertThat(Controller.skipIsRepeat("", "1|IMPLEMENTING|t1")).isFalse();
        // Identical signature = repeat (log at DEBUG).
        assertThat(Controller.skipIsRepeat("1|IMPLEMENTING|t1", "1|IMPLEMENTING|t1")).isTrue();
        // Any change (phase advanced, heartbeat moved, new pid) = not a repeat.
        assertThat(Controller.skipIsRepeat("1|IMPLEMENTING|t1", "1|VERIFYING|t1")).isFalse();
        assertThat(Controller.skipIsRepeat("1|IMPLEMENTING|t1", "1|IMPLEMENTING|t2")).isFalse();
        assertThat(Controller.skipIsRepeat("1|IMPLEMENTING|t1", "2|IMPLEMENTING|t1")).isFalse();
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
