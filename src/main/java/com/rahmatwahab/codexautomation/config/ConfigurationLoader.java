package com.rahmatwahab.codexautomation.config;

import java.nio.file.Path;

/**
 * Boundary for loading and validating JSON configuration.
 */
public interface ConfigurationLoader {
    ConfigurationSnapshot load(Path path);
}
