package com.jcoding.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opt-in {@code codex.auth: "codex_login"} worker authentication.
 *
 * <p>Instead of the API-key gateway, the sandboxed Codex process uses the operator's own
 * {@code codex login} (the {@code auth.json} in {@code CODEX_HOME}, default {@code ~/.codex}).
 * This weakens the default isolation: the worker holds the login token and the Codex process
 * has outbound network. The helpers here support the compensating controls: the token values are
 * redacted from worker output and delivery is blocked if any changed file contains them.
 */
final class CodexLogin {
    static final String MODE = "codex_login";
    /** Test and operator override for the login file location. */
    static final String FILE_PROPERTY = "jcoding.codex.auth.file";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_SECRET_LENGTH = 24;
    private static final long MAX_LOGIN_BYTES = 1_000_000L;
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T.*");

    private CodexLogin() { }

    static boolean enabled(Map<String, Object> codex) {
        return MODE.equals(codex.get("auth"));
    }

    static Path authFile() {
        String override = System.getProperty(FILE_PROPERTY);
        if (override != null && !override.isBlank()) return Path.of(override);
        String home = System.getenv("CODEX_HOME");
        Path dir = home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".codex");
        return dir.resolve("auth.json");
    }

    /** A usable login file is a non-empty, bounded JSON object. */
    static boolean plausible(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_LOGIN_BYTES) return false;
        try {
            JsonNode node = MAPPER.readTree(bytes);
            return node != null && node.isObject() && node.size() > 0;
        } catch (IOException | RuntimeException exc) {
            return false;
        }
    }

    /** Secret-looking string values in the login file, for redaction and leak checks. */
    static List<String> secretValues(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_LOGIN_BYTES) return List.of();
            Set<String> found = new LinkedHashSet<>();
            collect(MAPPER.readTree(Files.readAllBytes(file)), found);
            return List.copyOf(found);
        } catch (IOException | RuntimeException exc) {
            return List.of();
        }
    }

    private static void collect(JsonNode node, Set<String> found) {
        if (node == null) return;
        if (node.isTextual()) {
            String value = node.asText();
            if (value.length() >= MIN_SECRET_LENGTH && !TIMESTAMP.matcher(value).matches()) found.add(value);
        } else {
            node.forEach(child -> collect(child, found));
        }
    }

    /** The secrets plus their base64 forms, so trivial re-encoding does not evade the leak check. */
    static List<String> leakNeedles(List<String> secrets) {
        Set<String> needles = new LinkedHashSet<>();
        for (String secret : secrets) {
            byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
            needles.add(secret);
            needles.add(Base64.getEncoder().encodeToString(raw));
            needles.add(Base64.getEncoder().withoutPadding().encodeToString(raw));
            needles.add(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        }
        return List.copyOf(needles);
    }
}
