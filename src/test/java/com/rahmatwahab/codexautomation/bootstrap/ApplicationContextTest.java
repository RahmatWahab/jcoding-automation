package com.rahmatwahab.codexautomation.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplicationContextTest {
    @Test
    @DisplayName("Should wire provider boundaries when context is created")
    void shouldWireProviderBoundariesWhenContextIsCreated() {
        var environment = new RuntimeEnvironment("21", "Linux", "test");

        try (var context = ApplicationContext.create(environment)) {
            assertThat(context.runtimeEnvironment()).isEqualTo(environment);
            assertThat(context.providers())
                    .extracting(provider -> provider.name())
                    .containsExactly("system-clock");
            assertThat(context.shutdownCoordinator().isRunning()).isTrue();
        }
    }

    @Test
    @DisplayName("Should mark shutdown coordinator stopped when context closes")
    void shouldMarkShutdownCoordinatorStoppedWhenContextCloses() {
        var context = ApplicationContext.create(new RuntimeEnvironment("21", "Linux", "test"));

        context.close();

        assertThat(context.shutdownCoordinator().isRunning()).isFalse();
    }
}
