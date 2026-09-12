package com.rahmatwahab.codexautomation.config;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata for a validated configuration file.
 */
public record ConfigurationSnapshot(Path path, String sha256, Instant loadedAt) {
    public ConfigurationSnapshot {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(sha256, "sha256 must not be null");
        Objects.requireNonNull(loadedAt, "loadedAt must not be null");
    }
}
