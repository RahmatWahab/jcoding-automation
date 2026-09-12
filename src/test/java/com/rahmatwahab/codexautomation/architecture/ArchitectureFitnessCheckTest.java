package com.rahmatwahab.codexautomation.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureFitnessCheckTest {
    @Test
    @DisplayName("Should pass architecture fitness checks for current source tree")
    void shouldPassArchitectureFitnessChecksForCurrentSourceTree() throws Exception {
        ArchitectureFitnessCheck.main(new String[]{System.getProperty("user.dir")});
    }

    @Test
    @DisplayName("Should reject domain dependency expressed as fully qualified name")
    void shouldRejectDomainDependencyExpressedAsFullyQualifiedName(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/BadWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                final class BadWorkflow {
                    private com.rahmatwahab.codexautomation.git.GitHead head;
                }
                """);

        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow -> git");
    }

    @Test
    @DisplayName("Should reject package cycle expressed as fully qualified name")
    void shouldRejectPackageCycleExpressedAsFullyQualifiedName(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/WorkflowCycle.java", """
                package com.rahmatwahab.codexautomation.workflow;

                final class WorkflowCycle {
                    private com.rahmatwahab.codexautomation.scheduler.SchedulerCycle scheduler;
                }
                """);
        writeJava(root, "scheduler/SchedulerCycle.java", """
                package com.rahmatwahab.codexautomation.scheduler;

                final class SchedulerCycle {
                    private com.rahmatwahab.codexautomation.workflow.WorkflowCycle workflow;
                }
                """);

        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("package cycle detected");
    }

    private static void createRequiredPackageTree(Path root) throws Exception {
        var base = root.resolve("src/main/java/com/rahmatwahab/codexautomation");
        for (var packageName : new String[]{
                "bootstrap", "config", "scheduler", "execution", "locking", "workflow",
                "story", "git", "github", "review", "codex", "validation", "persistence",
                "logging", "housekeeping", "provider"
        }) {
            var directory = base.resolve(packageName);
            Files.createDirectories(directory);
            writeJava(root, packageName + "/PackageMarker.java", """
                    package com.rahmatwahab.codexautomation.%s;

                    final class PackageMarker {
                    }
                    """.formatted(packageName));
        }
    }

    private static void writeJava(Path root, String relativePath, String source) throws Exception {
        var path = root.resolve("src/main/java/com/rahmatwahab/codexautomation").resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }
}
