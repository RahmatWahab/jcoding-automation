package com.rahmatwahab.codexautomation.bootstrap;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Starts the S002 application foundation and verifies bootstrap wiring.
 */
public final class BootstrapApplication {
    private static final String APPLICATION_NAME = "jcoding-automation";

    private final VersionProvider versionProvider;
    private final RuntimeEnvironment runtimeEnvironment;
    private final PrintStream standardOutput;
    private final PrintStream standardError;

    public BootstrapApplication(
            VersionProvider versionProvider,
            RuntimeEnvironment runtimeEnvironment,
            PrintStream standardOutput,
            PrintStream standardError
    ) {
        this.versionProvider = Objects.requireNonNull(versionProvider, "versionProvider must not be null");
        this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment must not be null");
        this.standardOutput = Objects.requireNonNull(standardOutput, "standardOutput must not be null");
        this.standardError = Objects.requireNonNull(standardError, "standardError must not be null");
    }

    public int run(List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (arguments.contains("--help")) {
            standardOutput.println(helpText());
            return 0;
        }
        if (arguments.contains("--version")) {
            standardOutput.println(versionText());
            return 0;
        }
        if (!arguments.isEmpty() && !arguments.equals(List.of("run"))) {
            standardError.println("Unsupported command. Use --help for supported commands.");
            return 2;
        }
        try (var context = ApplicationContext.create(runtimeEnvironment)) {
            context.shutdownCoordinator().registerShutdownHook();
            standardOutput.println("jcoding-automation bootstrap ready");
            return 0;
        }
    }

    private String versionText() {
        return "%s %s (Java %s, %s %s)".formatted(
                APPLICATION_NAME,
                versionProvider.version(),
                runtimeEnvironment.javaVersion(),
                runtimeEnvironment.operatingSystemName(),
                runtimeEnvironment.operatingSystemVersion()
        );
    }

    private String helpText() {
        return """
                jcoding-automation

                Commands:
                  run          Start the bootstrap context and exit after S002 wiring checks
                  --version    Print application and runtime version
                  --help       Print this help
                """.stripTrailing();
    }
}
