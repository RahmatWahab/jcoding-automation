package com.jcoding.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Bind canary evidence to executable code, packaged isolation resources and controller prompts. */
final class RuntimeIdentity {
    private static final String CODE = codeHash();
    private RuntimeIdentity() { }
    static String fingerprint(java.util.Map<String, Object> config) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CODE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (config.containsKey("_controller_root")) {
                hashTree(Path.of(config.get("_controller_root").toString()).resolve("prompts"), digest);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exc) { throw new IllegalStateException("Cannot fingerprint controller prompts", exc); }
    }
    private static String codeHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path code = Path.of(RuntimeIdentity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(code)) hashTree(code, digest); else digest.update(Files.readAllBytes(code));
            for (String resource : java.util.List.of("model_gateway.py", "model_bridge.py")) {
                try (var stream = RuntimeIdentity.class.getResourceAsStream("/isolation/" + resource)) {
                    if (stream == null) throw new IllegalStateException("Missing isolation resource");
                    digest.update(stream.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exc) { throw new IllegalStateException("Cannot fingerprint controller runtime", exc); }
    }
    private static void hashTree(Path root, MessageDigest digest) throws java.io.IOException {
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(root.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
    }
}
