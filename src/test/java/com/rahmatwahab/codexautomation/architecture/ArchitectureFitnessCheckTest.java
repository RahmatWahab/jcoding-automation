package com.rahmatwahab.codexautomation.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArchitectureFitnessCheckTest {
    @Test
    @DisplayName("Should pass architecture fitness checks for current source tree")
    void shouldPassArchitectureFitnessChecksForCurrentSourceTree() throws Exception {
        ArchitectureFitnessCheck.main(new String[]{System.getProperty("user.dir")});
    }
}
