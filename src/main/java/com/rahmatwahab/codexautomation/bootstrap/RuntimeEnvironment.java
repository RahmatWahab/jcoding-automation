package com.rahmatwahab.codexautomation.bootstrap;

/**
 * Runtime facts recorded by the bootstrap command.
 */
public record RuntimeEnvironment(
        String javaVersion,
        String operatingSystemName,
        String operatingSystemVersion
) {
    public static RuntimeEnvironment detect() {
        return new RuntimeEnvironment(
                System.getProperty("java.version", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown")
        );
    }
}
