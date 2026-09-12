package com.rahmatwahab.codexautomation.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lightweight architecture gate used before heavier architecture tooling is introduced.
 */
public final class ArchitectureFitnessCheck {
    private static final Set<String> REQUIRED_PACKAGES = Set.of(
            "bootstrap",
            "config",
            "scheduler",
            "execution",
            "locking",
            "workflow",
            "story",
            "git",
            "github",
            "review",
            "codex",
            "validation",
            "persistence",
            "logging",
            "housekeeping",
            "provider"
    );
    private static final Set<String> DOMAIN_PACKAGES = Set.of(
            "scheduler",
            "workflow",
            "story",
            "validation"
    );
    private static final Set<String> INFRASTRUCTURE_PACKAGES = Set.of(
            "execution",
            "git",
            "github",
            "codex",
            "persistence",
            "logging",
            "housekeeping"
    );

    private ArchitectureFitnessCheck() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("project directory argument is required");
        }
        var root = Path.of(args[0]);
        verifyRequiredPackages(root);
        verifyNoPackageCycles(root);
        verifyDomainDoesNotDependOnInfrastructure(root);
        verifyDomainDoesNotAccessFilesystem(root);
        verifyNoDirectProcessBuilderOutsideExecution(root);
        verifyNoSystemExitOutsideMain(root);
    }

    private static void verifyRequiredPackages(Path root) {
        var base = root.resolve("src/main/java/com/rahmatwahab/codexautomation");
        var missing = REQUIRED_PACKAGES.stream()
                .filter(packageName -> Files.notExists(base.resolve(packageName)))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing required packages: " + missing);
        }
    }

    private static void verifyNoDirectProcessBuilderOutsideExecution(Path root) throws IOException {
        var offenders = javaFiles(root).stream()
                .filter(path -> !path.toString().contains("/execution/"))
                .filter(path -> containsCode(path, "ProcessBuilder") || containsCode(path, "Runtime.getRuntime().exec"))
                .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("direct process execution outside execution package: " + offenders);
        }
    }

    private static void verifyDomainDoesNotAccessFilesystem(Path root) throws IOException {
        var offenders = javaFiles(root).stream()
                .filter(path -> isDomainPath(path))
                .filter(path -> containsCode(path, "java.nio.file.")
                        || containsCode(path, "java.io.File")
                        || containsCode(path, "Files.")
                        || containsCode(path, "Path.of(")
                        || containsCode(path, "Paths.get("))
                .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("direct filesystem access from domain package: " + offenders);
        }
    }

    private static boolean isDomainPath(Path path) {
        return DOMAIN_PACKAGES.stream().anyMatch(packageName -> path.toString().contains("/" + packageName + "/"));
    }

    private static void verifyNoPackageCycles(Path root) throws IOException {
        var graph = packageDependencies(root);
        for (var packageName : graph.keySet()) {
            var path = findCycle(packageName, graph);
            if (!path.isEmpty()) {
                throw new IllegalStateException("package cycle detected: " + path);
            }
        }
    }

    private static void verifyDomainDoesNotDependOnInfrastructure(Path root) throws IOException {
        var graph = packageDependencies(root);
        var offenders = new ArrayList<String>();
        for (var domainPackage : DOMAIN_PACKAGES) {
            for (var infrastructurePackage : INFRASTRUCTURE_PACKAGES) {
                if (graph.getOrDefault(domainPackage, Set.of()).contains(infrastructurePackage)) {
                    offenders.add(domainPackage + " -> " + infrastructurePackage);
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("domain package depends on infrastructure: " + offenders);
        }
    }

    private static void verifyNoSystemExitOutsideMain(Path root) throws IOException {
        var offenders = javaFiles(root).stream()
                .filter(path -> !path.endsWith(Path.of("Main.java")))
                .filter(path -> containsCode(path, "System.exit"))
                .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("System.exit outside Main: " + offenders);
        }
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (var files = Files.walk(root.resolve("src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static Map<String, Set<String>> packageDependencies(Path root) throws IOException {
        var result = new HashMap<String, Set<String>>();
        for (var path : javaFiles(root)) {
            var packageName = packageName(path);
            result.computeIfAbsent(packageName, ignored -> new HashSet<>());
            var text = stripComments(Files.readString(path));
            var normalized = text.replaceAll("\\s+", "");
            for (var candidate : REQUIRED_PACKAGES) {
                if (!candidate.equals(packageName) && referencesPackage(normalized, candidate)) {
                    result.computeIfAbsent(packageName, ignored -> new HashSet<>()).add(candidate);
                }
            }
        }
        return result;
    }

    private static boolean referencesPackage(String normalizedSource, String candidate) {
        var prefix = "com.rahmatwahab.codexautomation." + candidate + ".";
        return normalizedSource.contains("import" + prefix)
                || normalizedSource.contains("importstatic" + prefix)
                || normalizedSource.contains(prefix);
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static List<String> findCycle(String start, Map<String, Set<String>> graph) {
        var stack = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        if (visit(start, start, graph, visited, stack)) {
            return List.copyOf(stack);
        }
        return List.of();
    }

    private static boolean visit(
            String start,
            String current,
            Map<String, Set<String>> graph,
            Set<String> visited,
            ArrayDeque<String> stack
    ) {
        if (!visited.add(current)) {
            return false;
        }
        stack.addLast(current);
        for (var next : graph.getOrDefault(current, Set.of())) {
            if (next.equals(start) || visit(start, next, graph, visited, stack)) {
                stack.addLast(start);
                return true;
            }
        }
        stack.removeLast();
        return false;
    }

    private static String packageName(Path path) {
        var value = path.toString().replace('\\', '/');
        var marker = "/com/rahmatwahab/codexautomation/";
        var index = value.indexOf(marker);
        if (index < 0) {
            throw new IllegalStateException("unexpected source path: " + path);
        }
        var relative = value.substring(index + marker.length());
        if (!relative.contains("/")) {
            return "";
        }
        return relative.substring(0, relative.indexOf('/'));
    }

    private static boolean containsCode(Path path, String value) {
        try {
            return stripCommentsAndLiterals(Files.readString(path)).contains(value);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + path, exception);
        }
    }

    private static String stripCommentsAndLiterals(String source) {
        return stripComments(source)
                .replaceAll("(?s)\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", "\\\"\\\"")
                .replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
    }
}
