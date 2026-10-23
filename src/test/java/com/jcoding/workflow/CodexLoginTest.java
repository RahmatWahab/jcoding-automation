package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** codex_login mode: opt-in worker auth using the operator's Codex login instead of the API-key gateway. */
class CodexLoginTest {
    private static final String ACCESS = "access-token-value-0123456789abcdef";
    private static final String REFRESH = "refresh-token-value-0123456789abcdef";
    private static final String LOGIN = "{\"tokens\":{\"access_token\":\"" + ACCESS + "\",\"refresh_token\":\""
            + REFRESH + "\",\"account_id\":\"short\"},\"last_refresh\":\"2026-10-23T10:00:00.123456789Z\"}";

    @TempDir Path dir;
    private Path login;

    @BeforeEach void pointAtTemporaryLogin() throws Exception {
        login = dir.resolve("auth.json");
        Files.writeString(login, LOGIN);
        System.setProperty(CodexLogin.FILE_PROPERTY, login.toString());
    }

    @AfterEach void clearOverride() { System.clearProperty(CodexLogin.FILE_PROPERTY); }

    @Test void secretValuesKeepTokensAndIgnoreShortValuesAndTimestamps() {
        assertThat(CodexLogin.secretValues(login)).containsExactlyInAnyOrder(ACCESS, REFRESH);
        assertThat(CodexLogin.secretValues(dir.resolve("missing.json"))).isEmpty();
    }

    @Test void leakNeedlesCoverTheTokenAndItsBase64Forms() {
        List<String> needles = CodexLogin.leakNeedles(List.of(ACCESS));
        assertThat(needles).contains(ACCESS,
                Base64.getEncoder().encodeToString(ACCESS.getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ACCESS.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void onlyJsonObjectsAreAcceptedAsALogin() {
        assertThat(CodexLogin.plausible(LOGIN.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(CodexLogin.plausible("not json".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(CodexLogin.plausible("[]".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(CodexLogin.plausible("{}".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(CodexLogin.plausible(new byte[0])).isFalse();
    }

    @Test void workerGetsAPrivateCopyAndNoGatewayOrRunToken() throws Exception {
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            isolation.useCodexLogin();
            Path copy = isolation.homeDirectory().resolve(".codex").resolve("auth.json");
            assertThat(Files.readString(copy)).isEqualTo(LOGIN);
            assertThat(Files.getPosixFilePermissions(copy)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(isolation.modelEnvironment()).doesNotContainKeys("JCODING_RUN_TOKEN", "OPENAI_API_KEY", "GH_TOKEN");

            List<String> command = isolation.wrap(List.of("sh"), dir, Map.of(), true, false);
            assertThat(command).contains("--share-net");
            assertThat(command).doesNotContain("/home/worker/model_bridge.py");
            assertThat(command).containsSubsequence("--setenv", "CODEX_HOME", "/home/worker/.codex");
        }
    }

    @Test void gatewayModeKeepsThePrivateNetworkAndNeverShareNet() throws Exception {
        try (WorkerIsolation isolation = WorkerIsolation.create(false)) {
            assertThat(isolation.wrap(List.of("sh"), dir, Map.of(), false, false)).doesNotContain("--share-net");
        }
    }

    @Test void missingOrInvalidLoginFailsClosedWithoutLeakingContent() throws Exception {
        Files.delete(login);
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            assertThatThrownBy(isolation::useCodexLogin).hasMessageContaining("Codex login not found");
        }
        Files.writeString(login, "garbage-secret-text");
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            assertThatThrownBy(isolation::useCodexLogin).hasMessageContaining("invalid")
                    .hasMessageNotContaining("garbage-secret-text");
        }
    }

    @Test void refreshedLoginIsPersistedToTheHostOnClose() throws Exception {
        String refreshed = LOGIN.replace(ACCESS, "rotated-access-token-9876543210fedcba");
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            isolation.useCodexLogin();
            Files.writeString(isolation.homeDirectory().resolve(".codex").resolve("auth.json"), refreshed);
        }
        assertThat(Files.readString(login)).isEqualTo(refreshed);
        assertThat(Files.getPosixFilePermissions(login)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test void unchangedLoginIsNotRewritten() throws Exception {
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(login);
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            isolation.useCodexLogin();
        }
        assertThat(Files.readString(login)).isEqualTo(LOGIN);
        assertThat(Files.getLastModifiedTime(login)).isEqualTo(before);
    }

    @Test void hostLoginChangedMeanwhileIsNeverOverwritten() throws Exception {
        String hostUpdate = LOGIN.replace(REFRESH, "host-side-newer-refresh-0123456789abcdef");
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            isolation.useCodexLogin();
            Files.writeString(login, hostUpdate);
            Files.writeString(isolation.homeDirectory().resolve(".codex").resolve("auth.json"),
                    LOGIN.replace(ACCESS, "worker-side-rotated-0123456789abcdef"));
        }
        assertThat(Files.readString(login)).isEqualTo(hostUpdate);
    }

    @Test void corruptedWorkerCopyIsNeverPersisted() throws Exception {
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            isolation.useCodexLogin();
            Files.writeString(isolation.homeDirectory().resolve(".codex").resolve("auth.json"), "tampered");
        }
        assertThat(Files.readString(login)).isEqualTo(LOGIN);
    }

    @Test void commandUsesCodexOwnProviderNotTheScopedGateway() {
        List<String> login = CodexRunner.buildCommand(Map.of("executable", "codex", "isolation", "bubblewrap",
                "auth", "codex_login", "approval_policy", "never"), Path.of("/repo"));
        assertThat(String.join(" ", login)).doesNotContain("jcoding_scoped", "model_provider");
        assertThat(login).containsSubsequence("--sandbox", "workspace-write", "--ask-for-approval", "never", "exec");
        assertThat(String.join(" ", login)).contains("sandbox_workspace_write.network_access=false");

        List<String> gateway = CodexRunner.buildCommand(Map.of("executable", "codex", "isolation", "bubblewrap",
                "approval_policy", "never"), Path.of("/repo"));
        assertThat(String.join(" ", gateway)).contains("model_provider=jcoding_scoped");
    }

    @Test void configAcceptsLoginOnlyWithBubblewrapAndKnownAuthValues() {
        java.util.function.Function<Map<String, Object>, Map<String, Object>> config = codex -> Map.of(
                "repository", Map.of("local_path", "/repo", "owner", "o", "name", "n"),
                "epic", Map.of("number", 1), "codex", codex);
        Config.validate(config.apply(Map.of("executable", "codex", "model", "m", "isolation", "bubblewrap", "auth", "codex_login")));
        Config.validate(config.apply(Map.of("executable", "codex", "model", "m", "isolation", "bubblewrap", "auth", "gateway")));
        assertThatThrownBy(() -> Config.validate(config.apply(Map.of("executable", "codex", "model", "m", "isolation", "native", "auth", "codex_login"))))
                .hasMessageContaining("codex_login requires");
        assertThatThrownBy(() -> Config.validate(config.apply(Map.of("executable", "codex", "model", "m", "isolation", "bubblewrap", "auth", "token"))))
                .hasMessageContaining("codex.auth(gateway|codex_login)");
    }

    @Test void hostReadinessReportsAMissingOrBrokenLoginButNeverItsContent() throws Exception {
        assertThat(HostReadiness.loginGaps(login)).isEmpty();
        assertThat(HostReadiness.loginGaps(dir.resolve("nope.json"))).singleElement().asString().contains("Codex login is absent");
        Files.writeString(login, "secret-garbage-value");
        assertThat(HostReadiness.loginGaps(login)).singleElement().asString()
                .contains("invalid").doesNotContain("secret-garbage-value");
    }

    @Test void loginModeProbeSharesTheNetworkAndGatewayModeDoesNot() {
        assertThat(HostReadiness.privateNetworkProbeCommand(p -> p.equals("/usr"), true)).contains("--share-net");
        assertThat(HostReadiness.privateNetworkProbeCommand(p -> p.equals("/usr"), false)).doesNotContain("--share-net");
    }

    @Test void redactionRemovesLoginTokensFromWorkerOutput() throws Exception {
        CodexRunner runner = new CodexRunner(Map.of(), System::nanoTime);
        java.lang.reflect.Field field = CodexRunner.class.getDeclaredField("loginSecrets");
        field.setAccessible(true);
        field.set(runner, CodexLogin.secretValues(login));
        assertThat(runner.redactSensitive("leaked " + ACCESS + " and " + REFRESH + " here"))
                .doesNotContain(ACCESS, REFRESH).contains("[REDACTED]");
    }
}
