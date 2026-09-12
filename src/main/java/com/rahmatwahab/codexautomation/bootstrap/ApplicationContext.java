package com.rahmatwahab.codexautomation.bootstrap;

import com.rahmatwahab.codexautomation.provider.AutomationProvider;
import com.rahmatwahab.codexautomation.provider.SystemClock;
import java.util.List;
import java.util.Objects;

/**
 * Minimal dependency container for the modular-monolith foundation.
 */
public final class ApplicationContext implements AutoCloseable {
    private final RuntimeEnvironment runtimeEnvironment;
    private final ShutdownCoordinator shutdownCoordinator;
    private final List<AutomationProvider> providers;

    private ApplicationContext(
            RuntimeEnvironment runtimeEnvironment,
            ShutdownCoordinator shutdownCoordinator,
            List<AutomationProvider> providers
    ) {
        this.runtimeEnvironment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment must not be null");
        this.shutdownCoordinator = Objects.requireNonNull(shutdownCoordinator, "shutdownCoordinator must not be null");
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
    }

    public static ApplicationContext create(RuntimeEnvironment runtimeEnvironment) {
        return new ApplicationContext(
                runtimeEnvironment,
                new ShutdownCoordinator(),
                List.of(new SystemClock())
        );
    }

    public RuntimeEnvironment runtimeEnvironment() {
        return runtimeEnvironment;
    }

    public ShutdownCoordinator shutdownCoordinator() {
        return shutdownCoordinator;
    }

    public List<AutomationProvider> providers() {
        return providers;
    }

    @Override
    public void close() {
        shutdownCoordinator.markStopped();
    }
}
