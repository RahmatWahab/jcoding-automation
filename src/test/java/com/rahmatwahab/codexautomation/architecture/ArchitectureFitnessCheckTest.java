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

    @Test
    @DisplayName("Should ignore fully qualified names in comments")
    void shouldIgnoreFullyQualifiedNamesInComments(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/DocumentedWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                /** Do not depend on com.rahmatwahab.codexautomation.git.GitClient here. */
                final class DocumentedWorkflow {
                    // com.rahmatwahab.codexautomation.github.GitHubClient is infrastructure.
                }
                """);

        ArchitectureFitnessCheck.main(new String[]{root.toString()});
    }

    @Test
    @DisplayName("Should reject direct filesystem access from domain packages")
    void shouldRejectDirectFilesystemAccessFromDomainPackages(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/BadWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                import java.nio.file.Files;
                import java.nio.file.Path;

                final class BadWorkflow {
                    String read(Path path) throws Exception { return Files.readString(path); }
                }
                """);

        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    @DisplayName("Should ignore process APIs in comments and string literals")
    void shouldIgnoreProcessApisInCommentsAndStringLiterals(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/DocumentedWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                /** ProcessBuilder and Runtime.getRuntime().exec are forbidden APIs. */
                final class DocumentedWorkflow {
                    String message = "ProcessBuilder Runtime.getRuntime().exec";
                }
                """);

        ArchitectureFitnessCheck.main(new String[]{root.toString()});
    }

    @Test
    @DisplayName("Should reject indirect runtime process execution")
    void shouldRejectIndirectRuntimeProcessExecution(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/BadWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                final class BadWorkflow {
                    void run(String command) {
                        var runtime = Runtime.getRuntime();
                        runtime.exec(command);
                    }
                }
                """);

        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct process execution outside execution package");
    }

    @Test
    @DisplayName("Should reject RandomAccessFile from domain packages")
    void shouldRejectRandomAccessFileFromDomainPackages(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/BadWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                final class BadWorkflow {
                    void open() throws Exception {
                        new java.io.RandomAccessFile("state", "r");
                    }
                }
                """);

        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    @DisplayName("Should ignore package names in string literals")
    void shouldIgnorePackageNamesInStringLiterals(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/DocumentedWorkflow.java", """
                package com.rahmatwahab.codexautomation.workflow;

                final class DocumentedWorkflow {
                    String diagnostic = "com.rahmatwahab.codexautomation.git.GitClient";
                }
                """);

        ArchitectureFitnessCheck.main(new String[]{root.toString()});
    }

    @Test
    void shouldNotRejectUnrelatedExecAndShouldRejectWildcardFilesystem(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Query.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Query { interface Q { void exec(); } Q q; void run() { q.exec(); } }
                """);
        ArchitectureFitnessCheck.main(new String[]{root.toString()});
        writeJava(root, "workflow/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.*;
                final class Bad { void open() throws Exception { new FileInputStream("state"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    void shouldUseTopLevelExecutionAndExactMainBoundaries(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/execution/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow.execution;
                final class Bad { void run() { new ProcessBuilder("sh"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct process execution outside execution package");
        writeJava(root, "workflow/execution/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow.execution;
                final class Bad { }
                """);
        writeJava(root, "workflow/Main.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Main { void stop() { System.exit(1); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("System.exit outside Main");
    }

    @Test
    void shouldNotRejectFileNotFoundExceptionTypeAlone(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Exception.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.FileNotFoundException;
                final class Exception { void handle() { FileNotFoundException ignored = null; } }
                """);
        ArchitectureFitnessCheck.main(new String[]{root.toString()});
    }

    @Test
    void shouldRejectExplicitlyImportedFilesystemStream(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Stream.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.FileInputStream;
                final class Stream { void open() throws Exception { new FileInputStream("state"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    void shouldRejectPrintWriterAndAllowUnrelatedFilesApi(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Api.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Api { static final class Files { static void refresh() {} } void ok() { Files.refresh(); } }
                """);
        ArchitectureFitnessCheck.main(new String[]{root.toString()});
        writeJava(root, "workflow/Writer.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.PrintWriter;
                final class Writer { void open() { new PrintWriter("state"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    void shouldRejectStaticRuntimeExecAndZipFile(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import static java.lang.Runtime.getRuntime;
                final class Bad { void run() { getRuntime().exec("tool"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct process execution outside execution package");
        writeJava(root, "workflow/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Bad { void open() throws Exception { new java.util.zip.ZipFile("state.zip"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    void shouldRejectRuntimeParameterAndAllowInMemoryWriter(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Bad { void run(Runtime runtime) { runtime.exec("tool"); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct process execution outside execution package");
        writeJava(root, "workflow/Bad.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.PrintWriter;
                import java.io.StringWriter;
                final class Bad { void write() { new PrintWriter(new StringWriter()); } }
                """);
        ArchitectureFitnessCheck.main(new String[]{root.toString()});
    }

    @Test
    void shouldAllowPathDataBoundaryAndRejectVariableFilePath(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/PathModel.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.nio.file.Path;
                final class PathModel { Path path; Path getPath() { return path; } }
                """);
        ArchitectureFitnessCheck.main(new String[]{root.toString()});
        writeJava(root, "workflow/FileModel.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.FileInputStream;
                final class FileModel { void open(String filename) throws Exception { new FileInputStream(filename); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    void shouldRejectVariablePrintWriterPathAndIgnoreTextBlockContent(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Prompt.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Prompt { String text = \"\"\"ProcessBuilder // com.rahmatwahab.codexautomation.git.GitClient\"\"\"; }
                """);
        ArchitectureFitnessCheck.main(new String[]{root.toString()});
        writeJava(root, "workflow/Writer.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.io.PrintWriter;
                final class Writer { void open(String filename) { new PrintWriter(filename); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
    }

    @Test
    void shouldRejectQualifiedFileConstructorAndNioWildcardAndStaticImports(@TempDir Path root) throws Exception {
        createRequiredPackageTree(root);
        writeJava(root, "workflow/Qualified.java", """
                package com.rahmatwahab.codexautomation.workflow;
                final class Qualified { void open(String filename) throws Exception { new java.io.FileInputStream(filename); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
        writeJava(root, "workflow/Qualified.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import java.nio.file.*;
                final class Qualified { void open(Path path) throws Exception { Files.readString(path); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
        writeJava(root, "workflow/Qualified.java", """
                package com.rahmatwahab.codexautomation.workflow;
                import static java.nio.file.Files.readString;
                final class Qualified { void open(java.nio.file.Path path) throws Exception { readString(path); } }
                """);
        assertThatThrownBy(() -> ArchitectureFitnessCheck.main(new String[]{root.toString()}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct filesystem access from domain package");
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
