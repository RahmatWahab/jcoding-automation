package com.rahmatwahab.codexautomation.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BootstrapApplicationTest {
    @Test
    @DisplayName("Should print version when version command is requested")
    void shouldPrintVersionWhenVersionCommandIsRequested() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var subject = subject(output, error);

        var exitCode = subject.run(List.of("--version"));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("jcoding-automation");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Java 21.0.12");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Should print help when help command is requested")
    void shouldPrintHelpWhenHelpCommandIsRequested() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var subject = subject(output, error);

        var exitCode = subject.run(List.of("--help"));

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("--version");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("run");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Should start bootstrap context when no command is provided")
    void shouldStartBootstrapContextWhenNoCommandIsProvided() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var subject = subject(output, error);

        var exitCode = subject.run(List.of());

        assertThat(exitCode).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("bootstrap ready");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("Should reject unsupported commands")
    void shouldRejectUnsupportedCommands() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var subject = subject(output, error);

        var exitCode = subject.run(List.of("merge"));

        assertThat(exitCode).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("Unsupported command");
    }

    private static BootstrapApplication subject(ByteArrayOutputStream output, ByteArrayOutputStream error) {
        return new BootstrapApplication(
                new VersionProvider(),
                new RuntimeEnvironment("21.0.12", "Linux", "test"),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)
        );
    }
}
