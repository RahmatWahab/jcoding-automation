package com.rahmatwahab.codexautomation.bootstrap;

import java.util.Optional;

/**
 * Resolves the application version from packaged metadata with a development fallback.
 */
public final class VersionProvider {
    private static final String DEVELOPMENT_VERSION = "0.1.0-SNAPSHOT";

    public String version() {
        return Optional.ofNullable(VersionProvider.class.getPackage().getImplementationVersion())
                .filter(value -> !value.isBlank())
                .orElse(DEVELOPMENT_VERSION);
    }
}
