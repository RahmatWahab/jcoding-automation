package com.rahmatwahab.codexautomation;

import com.rahmatwahab.codexautomation.bootstrap.BootstrapApplication;
import com.rahmatwahab.codexautomation.bootstrap.RuntimeEnvironment;
import com.rahmatwahab.codexautomation.bootstrap.VersionProvider;
import java.util.List;

/**
 * Command-line entry point for the Java migration application.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        var application = new BootstrapApplication(
                new VersionProvider(),
                RuntimeEnvironment.detect(),
                System.out,
                System.err
        );
        var exitCode = application.run(List.of(args));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
