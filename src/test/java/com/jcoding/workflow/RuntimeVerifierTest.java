package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeVerifierTest {
    @TempDir Path root;
    private Map<String,Object> config() {
        return Map.of("verification", Map.of("checks", List.of(Map.of("id", "unit", "command", List.of("test-command"),
                "criteria", List.of("AC-1"))), "protected_paths", List.of("verification/**")), "automation", Map.of());
    }
    private Map<String,Object> state() {
        return new LinkedHashMap<>(Map.of("current_story", 42, "base_commit", "base", "goal_contract",
                Map.of("requirements_hash", "req", "criteria", List.of(Map.of("id", "AC-1")))));
    }
    private GitOperations git(String[] diff) {
        return new GitOperations(root, (repo,args) -> new GitOperations.GitResult(0,
                args.getFirst().equals("rev-parse") ? "head" : args.getFirst().equals("status") ? diff[0].replace('\n', '\0')
                        : args.contains("--name-only") ? "src/Fix.java" : diff[0], ""));
    }
    @Test void workerSuccessCannotBypassFailedExecutableCheck() {
        Map<String,Object> state = state();
        state.put("result_status", "SUCCESS");
        RuntimeVerifier verifier = new RuntimeVerifier((cmd,repo,seconds,hb) -> new BoundedProcess.Result(1,"assertion failed",false));
        assertThat(verifier.verify(root, git(new String[]{" M src/Fix.java\n"}), state, config(), ()->{}, ()->{})).isFalse();
        assertThat(state).doesNotContainKey("validation_checkpoint");
        assertThat(state.get("verification_failure")).isEqualTo("Verification failed: unit");
    }
    @Test void evidenceIsCachedOnlyForIdenticalSourceRequirementsAndPolicy() {
        Map<String,Object> state = state(); String[] diff={" M src/Fix.java\n"}; AtomicInteger calls=new AtomicInteger();
        RuntimeVerifier verifier = new RuntimeVerifier((cmd,repo,seconds,hb) -> {calls.incrementAndGet();return new BoundedProcess.Result(0,"pass",false);});
        assertThat(verifier.verify(root, git(diff), state, config(), ()->{}, ()->{})).isTrue();
        assertThat(verifier.verify(root, git(diff), state, config(), ()->{}, ()->{})).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        diff[0]=" M src/Changed.java\n";
        assertThat(verifier.verify(root, git(diff), state, config(), ()->{}, ()->{})).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void sourceMutationDuringChecksAndVerifierTamperingFailClosed() {
        Map<String,Object> state=state(); String[] diff={" M src/Fix.java\n"};
        RuntimeVerifier verifier=new RuntimeVerifier((cmd,repo,seconds,hb)->{diff[0]=" M src/Other.java\n";return new BoundedProcess.Result(0,"pass",false);});
        assertThat(verifier.verify(root,git(diff),state,config(),()->{},()->{})).isFalse();
        assertThat(state.get("verification_failure")).isEqualTo("Source changed during verification; evidence invalidated");
        diff[0]=" M verification/oracle.sh\n";
        assertThat(verifier.verify(root,git(diff),state,config(),()->{},()->{})).isFalse();
        assertThat(state.get("verification_failure")).isEqualTo("Path outside authorized scope: verification/oracle.sh");
    }
    @Test void wildcardCriteriaCoversEveryStoryCriterionButStillRequiresPassingCheck() {
        Map<String,Object> cfg = Map.of("verification", Map.of("checks", List.of(Map.of("id", "unit",
                "command", List.of("test-command"), "criteria", List.of("*"))), "protected_paths", List.of()), "automation", Map.of());
        Map<String,Object> multi = new LinkedHashMap<>(Map.of("current_story", 42, "base_commit", "base", "goal_contract",
                Map.of("requirements_hash", "req", "criteria", List.of(Map.of("id", "AC-1"), Map.of("id", "AC-2"), Map.of("id", "AC-3")))));
        RuntimeVerifier passing = new RuntimeVerifier((cmd,repo,seconds,hb) -> new BoundedProcess.Result(0,"pass",false));
        assertThat(passing.verify(root, git(new String[]{" M src/Fix.java\n"}), multi, cfg, ()->{}, ()->{})).isTrue();
        assertThat(new TreeSet<Object>(((Map<?,?>) multi.get("criterion_evidence")).keySet())).containsExactly("AC-1", "AC-2", "AC-3");
        Map<String,Object> failed = new LinkedHashMap<>(Map.of("current_story", 42, "base_commit", "base", "goal_contract",
                Map.of("requirements_hash", "req", "criteria", List.of(Map.of("id", "AC-1"), Map.of("id", "AC-2")))));
        RuntimeVerifier failing = new RuntimeVerifier((cmd,repo,seconds,hb) -> new BoundedProcess.Result(1,"boom",false));
        assertThat(failing.verify(root, git(new String[]{" M src/Fix.java\n"}), failed, cfg, ()->{}, ()->{})).isFalse();
        assertThat(failed).doesNotContainKey("validation_checkpoint");
    }
    @Test void explicitCriterionStillMustExistInStory() {
        Map<String,Object> cfg = Map.of("verification", Map.of("checks", List.of(Map.of("id", "unit",
                "command", List.of("test-command"), "criteria", List.of("AC-9"))), "protected_paths", List.of()), "automation", Map.of());
        RuntimeVerifier verifier = new RuntimeVerifier((cmd,repo,seconds,hb) -> new BoundedProcess.Result(0,"pass",false));
        Map<String,Object> st = state();
        assertThat(verifier.verify(root, git(new String[]{" M src/Fix.java\n"}), st, cfg, ()->{}, ()->{})).isFalse();
        assertThat(st.get("verification_failure")).isEqualTo("Check maps unknown criterion: AC-9");
    }
    @Test void loginTokenInAChangedFileBlocksDeliveryEvenWhenReEncoded() throws Exception {
        Path login = root.resolve("auth.json");
        String token = "access-token-value-0123456789abcdef";
        Files.writeString(login, "{\"tokens\":{\"access_token\":\"" + token + "\"}}");
        System.setProperty(CodexLogin.FILE_PROPERTY, login.toString());
        try {
            Map<String,Object> cfg = Map.of("codex", Map.of("auth", "codex_login"),
                    "verification", Map.of("checks", List.of(Map.of("id", "unit", "command", List.of("test-command"),
                            "criteria", List.of("*"))), "protected_paths", List.of()), "automation", Map.of());
            RuntimeVerifier passing = new RuntimeVerifier((cmd,repo,seconds,hb) -> new BoundedProcess.Result(0,"pass",false));
            Files.createDirectories(root.resolve("src"));

            Files.writeString(root.resolve("src/Fix.java"), "class Fix { }");
            assertThat(passing.verify(root, git(new String[]{" M src/Fix.java\n"}), state(), cfg, ()->{}, ()->{})).isTrue();

            for (String leaked : List.of(token, java.util.Base64.getEncoder().encodeToString(token.getBytes()))) {
                Files.writeString(root.resolve("src/Fix.java"), "String t = \"" + leaked + "\";");
                Map<String,Object> st = state();
                assertThat(passing.verify(root, git(new String[]{" M src/Fix.java\n"}), st, cfg, ()->{}, ()->{})).isFalse();
                assertThat((String) st.get("verification_failure")).contains("Codex login token material found in src/Fix.java")
                        .doesNotContain(token);
            }

            Map<String,Object> gatewayCfg = Map.of("verification", cfg.get("verification"), "automation", Map.of());
            Map<String,Object> st = state();
            assertThat(passing.verify(root, git(new String[]{" M src/Fix.java\n"}), st, gatewayCfg, ()->{}, ()->{})).isTrue();
        } finally { System.clearProperty(CodexLogin.FILE_PROPERTY); }
    }
    @Test void automaticMergeCannotOptOutOfExecutableVerification() {
        Map<String,Object> config=Map.of("verification",Map.of("required",false),"workflow",Map.of("auto_merge",true));
        RuntimeVerifier verifier=new RuntimeVerifier((c,r,t,h)->{throw new AssertionError();});
        assertThat(verifier.verify(root,git(new String[]{""}),state(),config,()->{},()->{})).isFalse();
    }
}
