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
                .filter(path -> !topLevelPackage(path).equals("execution"))
                .filter(ArchitectureFitnessCheck::containsForbiddenProcessExecution)
                .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("direct process execution outside execution package: " + offenders);
        }
    }

    private static void verifyDomainDoesNotAccessFilesystem(Path root) throws IOException {
        var offenders = javaFiles(root).stream()
                .filter(path -> isDomainPath(path))
                .filter(path -> containsFilesystemFileType(path)
                        || containsCode(path, "java.io.RandomAccessFile")
                        || containsJdkFilesType(path)
                        || containsCode(path, "Path.of(")
                        || containsCode(path, "Paths.get(")
                        || containsKnownJavaIoType(path))
                .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("direct filesystem access from domain package: " + offenders);
        }
    }

    private static boolean isDomainPath(Path path) {
        return DOMAIN_PACKAGES.stream().anyMatch(packageName -> topLevelPackage(path).equals(packageName));
    }

    private static String normalizedPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String topLevelPackage(Path path) {
        var value = normalizedPath(path);
        var marker = "/com/rahmatwahab/codexautomation/";
        var remainder = value.substring(value.lastIndexOf(marker) + marker.length());
        var separator = remainder.indexOf('/');
        return separator < 0 ? "" : remainder.substring(0, separator);
    }

    private static boolean containsForbiddenProcessExecution(Path path) {
        var source = code(path);
        if (source.contains("ProcessBuilder") || source.contains("Runtime.getRuntime().exec(")
                || source.contains("getRuntime().exec(")) return true;
        var runtimeType = java.util.regex.Pattern.compile("Runtime([A-Za-z_$][A-Za-z0-9_$]*)(?:\\)|,|;)").matcher(source);
        while (runtimeType.find()) {
            if (source.contains(runtimeType.group(1) + ".exec(")) return true;
        }
        var matcher = java.util.regex.Pattern.compile("(?:var)?([A-Za-z_$][A-Za-z0-9_$]*)=(?:Runtime|java.lang.Runtime)\\.getRuntime\\(\\)").matcher(source);
        while (matcher.find()) {
            if (source.matches("(?s).*\\b" + java.util.regex.Pattern.quote(matcher.group(1)) + "\\.exec\\(.*")) return true;
        }
        return false;
    }

    private static boolean containsKnownJavaIoType(Path path) {
        var source = code(path);
        return Set.of("File", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile",
                "BufferedReader", "BufferedWriter", "DataInputStream", "DataOutputStream", "ObjectInputStream",
                "ObjectOutputStream", "PrintStream", "PrintWriter", "Scanner", "ZipFile", "JarFile")
                .stream().anyMatch(type -> source.contains("new" + type + "(\"")
                        || source.contains("new" + type + "(java.io.File")
                        || source.contains("new" + type + "(java.nio.file.Path")
                        || source.contains("new" + type + "(File")
                        || source.contains("new" + type + "(Path")
                        || source.contains("java.util.zip." + type)
                        || (Set.of("FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile", "ZipFile", "JarFile", "PrintWriter", "PrintStream").contains(type)
                            && source.matches("(?s).*new" + type + "\\([A-Za-z_$][A-Za-z0-9_$]*\\).*")));
    }

    private static boolean containsJdkFilesType(Path path) {
        var source = code(path);
        return source.contains("java.nio.file.Files.")
                || (source.contains("importjava.nio.file.Files;") && source.contains("Files."));
    }

    private static boolean containsFilesystemFileType(Path path) {
        var source = code(path);
        return source.contains("java.io.File;") || source.contains("java.io.File(")
                || source.contains("importjava.io.File;") || source.contains("newFile(");
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
                .filter(path -> !normalizedPath(path).endsWith("/com/rahmatwahab/codexautomation/Main.java"))
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
        var sourceFiles = javaFiles(root);
        var discoveredPackages = sourceFiles.stream().map(ArchitectureFitnessCheck::packageName)
                .filter(packageName -> !packageName.isEmpty()).collect(Collectors.toSet());
        for (var path : sourceFiles) {
            var packageName = packageName(path);
            result.computeIfAbsent(packageName, ignored -> new HashSet<>());
            var text = stripCommentsAndLiterals(Files.readString(path));
            var normalized = text.replaceAll("\\s+", "");
            for (var candidate : discoveredPackages) {
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
        return code(path).contains(value);
    }

    private static String code(Path path) {
        try {
            return stripCommentsAndLiterals(Files.readString(path));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + path, exception);
        }
    }

    private static String stripCommentsAndLiterals(String source) {
        var output = new StringBuilder(source.length());
        var state = ScanState.CODE;
        for (var index = 0; index < source.length(); index++) {
            var current = source.charAt(index);
            var next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (state == ScanState.CODE) {
                if (current == '/' && next == '/') { state = ScanState.LINE_COMMENT; index++; output.append(' '); }
                else if (current == '/' && next == '*') { state = ScanState.BLOCK_COMMENT; index++; output.append(' '); }
                else if (current == '"' && next == '"' && index + 2 < source.length() && source.charAt(index + 2) == '"') { state = ScanState.TEXT_BLOCK; index += 2; output.append("\"\""); }
                else if (current == '"') { state = ScanState.STRING; output.append("\"\""); }
                else if (current == '\'') { state = ScanState.CHARACTER; output.append("''"); }
                else { output.append(current); }
            } else if (state == ScanState.LINE_COMMENT) {
                if (current == '\n' || current == '\r') { state = ScanState.CODE; output.append(current); }
            } else if (state == ScanState.BLOCK_COMMENT) {
                if (current == '*' && next == '/') { state = ScanState.CODE; index++; }
                else if (current == '\n' || current == '\r') { output.append(current); }
            } else if (state == ScanState.STRING) {
                if (current == '\\') { index++; }
                else if (current == '"') { state = ScanState.CODE; }
            } else if (state == ScanState.TEXT_BLOCK) {
                if (current == '"' && next == '"' && index + 2 < source.length() && source.charAt(index + 2) == '"') { state = ScanState.CODE; index += 2; }
                else if (current == '\n' || current == '\r') { output.append(current); }
            } else {
                if (current == '\\') { index++; }
                else if (current == '\'') { state = ScanState.CODE; }
            }
        }
        return output.toString().replaceAll("\\s+", "");
    }

    private enum ScanState {
        CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, TEXT_BLOCK, CHARACTER
    }
}
