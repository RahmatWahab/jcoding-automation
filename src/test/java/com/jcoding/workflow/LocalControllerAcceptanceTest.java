package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcoding.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual Controller.run local route with mocked model and verifier process only. */
class LocalControllerAcceptanceTest {
    @TempDir Path root;

    @Test void localRequestCompletesThroughControllerWithoutTouchingUserEdits() throws Exception {
        Path repository = repository();
        Files.writeString(repository.resolve("user-note.txt"), "untracked user note");
        Files.writeString(repository.resolve("seed.txt"), "unstaged user edit");
        Files.writeString(repository.resolve("staged-note.txt"), "staged user note");
        git(repository, "add", "staged-note.txt");
        Path request = root.resolve("request.json");
        Files.writeString(request, "{\"title\":\"Add feature\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Create feature marker\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Feature marker exists\"}]}");
        config(repository);
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/job-control.json"), "{\"job_start\":true}");
        AtomicInteger calls = new AtomicInteger();
        LocalRequestWorkflow.Worker model = (config, prompt, checkout, heartbeat, timeout) -> {
            calls.incrementAndGet();
            try {
                String protocol = Files.readString(prompt);
                String id = field(protocol, "request_id");
                String goal = field(protocol, "requirements_hash");
                String source = field(protocol, "source_fingerprint");
                String output = new ObjectMapper().writeValueAsString(Map.of("schema_version", 1,
                        "request_id", id, "requirements_hash", goal, "source_fingerprint", source,
                        "status", "SUCCESS", "reason", "", "changes", List.of(Map.of(
                                "operation", "write", "path", "feature.txt", "criterion_ids", List.of("AC-1"),
                                "expected_sha256", "ABSENT",
                                "content", "feature works"))));
                return new CodexRunner.CodexResult(0, output);
            } catch (Exception exc) { throw new IllegalStateException(exc); }
        };
        RuntimeVerifier verification = new RuntimeVerifier((command, checkout, timeout, heartbeat) -> {
            try {
                return new BoundedProcess.Result(Files.readString(checkout.resolve("feature.txt"))
                        .equals("feature works") ? 0 : 1, "checked", false);
            } catch (Exception exc) { return new BoundedProcess.Result(1, "missing", false); }
        });
        String before = System.getProperty("codex.automation.root");
        System.setProperty("codex.automation.root", root.toString());
        try {
            int resumed = Controller.run(new String[] {"--request-file", request.toString()}, model, verification);
            assertThat(resumed).as("resumed state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            assertThat(calls).hasValue(1);
            assertThat(Files.readString(repository.resolve("user-note.txt"))).isEqualTo("untracked user note");
            assertThat(Files.readString(repository.resolve("seed.txt"))).isEqualTo("unstaged user edit");
            assertThat(Files.readString(repository.resolve("staged-note.txt"))).isEqualTo("staged user note");
            assertThat(GitOperations.forRepo(repository).changedSourcePaths()).contains("staged-note.txt");
            Map<?, ?> delivered = new ObjectMapper().readValue(root.resolve("state/local-result-11.json").toFile(), Map.class);
            assertThat(delivered.get("status")).isEqualTo("VERIFIED");
            assertThat(delivered.get("requirements_hash")).isNotNull();
            assertThat(((Map<?, ?>) delivered.get("criteria")).get("AC-1")).isInstanceOf(Map.class);
            Map<?, ?> bundle = new ObjectMapper().readValue(Path.of(delivered.get("bundle_path").toString()).toFile(), Map.class);
            assertThat(((List<?>) bundle.get("changes"))).hasSize(1);
            assertThat(((Map<?, ?>) ((List<?>) bundle.get("changes")).getFirst()).get("path")).isEqualTo("feature.txt");
            Map<?, ?> attribution = (Map<?, ?>) ((Map<?, ?>) bundle.get("requirement_attribution")).get("feature.txt");
            assertThat(attribution.get("criteria")).isEqualTo(List.of("AC-1"));
            assertThat(attribution.get("requirements_hash")).isEqualTo(delivered.get("requirements_hash"));
            int replay = Controller.run(new String[] {"--request-file", request.toString()}, model, verification);
            assertThat(replay).as("replayed state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            assertThat(calls).hasValue(1);
            Map<String, Object> eval = new StateStore(root.resolve("state/epic-10.json"))
                    .evaluationReport("controller-local-v1");
            assertThat(eval).containsEntry("trials", 2).containsEntry("passed", 2)
                    .containsEntry("success_rate", 1.0).containsEntry("worker_calls", 1L);
            Path workerCheckout = Path.of(new StateStore(root.resolve("state/epic-10.json")).load()
                    .get("worker_checkout").toString());
            Files.writeString(workerCheckout.resolve("unrelated.txt"), "not produced by the worker");
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, model, verification))
                    .isEqualTo(1);
            assertThat(calls).hasValue(1);
            assertThat(Files.readString(repository.resolve("user-note.txt"))).isEqualTo("untracked user note");
        } finally {
            if (before == null) System.clearProperty("codex.automation.root");
            else System.setProperty("codex.automation.root", before);
        }
    }

    @Test void failedCriterionDrivesBoundedRepairAndProducesCurrentProof() throws Exception {
        Path repo = repository();
        config(repo);
        ready();
        Path request = request();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        RuntimeVerifier verifier = new RuntimeVerifier((command, checkout, timeout, heartbeat) -> {
            checks.incrementAndGet();
            try { return new BoundedProcess.Result(Files.readString(checkout.resolve("feature.txt"))
                    .equals("feature works") ? 0 : 1, "checked", false); }
            catch (Exception exc) { return new BoundedProcess.Result(1, "missing", false); }
        });
        LocalRequestWorkflow.Worker worker = (cfg, prompt, checkout, heartbeat, timeout) -> {
            int call = calls.incrementAndGet();
            String prior = call == 1 ? "ABSENT" : WorkerToolBoundary.sha256("wrong".getBytes());
            return response(prompt, List.of(Map.of("operation", "write", "path", "feature.txt",
                    "expected_sha256", prior, "content", call == 1 ? "wrong" : "feature works")));
        };
        withRoot(() -> {
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, verifier))
                    .as("second run state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            assertThat(calls).hasValue(2);
            assertThat(checks).hasValue(3);
            Map<String,Object> state = new StateStore(root.resolve("state/epic-10.json")).load();
            assertThat(((Map<?, ?>) ((Map<?, ?>) state.get("criterion_evidence")).get("AC-1")).get("status"))
                    .isEqualTo("VERIFIED");
            assertThat(state.get("local_outcome")).isEqualTo("DONE");
            assertThat(state.get("worker_checkout_lifecycle")).isEqualTo("VERIFIED_RETAINED");
            assertThat(Files.isDirectory(Path.of(state.get("worker_checkout").toString()))).isTrue();
        });
    }

    @Test void criteriaBecomeOrderedDurableStepsAndBothSurviveDelivery() throws Exception {
        Path repo = repository();
        config(repo);
        ready();
        Map<String,Object> config = new ObjectMapper().readValue(root.resolve("config/config.json").toFile(), Map.class);
        ((Map<String,Object>) config.get("verification")).put("checks", List.of(
                Map.of("id", "feature-one", "command", List.of("check-one"), "criteria", List.of("AC-1")),
                Map.of("id", "feature-two", "command", List.of("check-two"), "criteria", List.of("AC-2"))));
        new ObjectMapper().writeValue(root.resolve("config/config.json").toFile(), config);
        Path request = root.resolve("multi.json");
        Files.writeString(request, "{\"title\":\"Add two features\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Feature one\"},"
                + "{\"id\":\"R2\",\"text\":\"Feature two\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"First marker\"},"
                + "{\"id\":\"AC-2\",\"text\":\"Second marker\"}]} ");
        AtomicInteger calls = new AtomicInteger();
        LocalRequestWorkflow.Worker worker = (cfg, prompt, checkout, heartbeat, timeout) -> {
            int call = calls.incrementAndGet();
            return response(prompt, List.of(Map.of("operation", "write", "path", "feature-" + call + ".txt",
                    "expected_sha256", "ABSENT", "content", "feature " + call)));
        };
        RuntimeVerifier verifier = new RuntimeVerifier((command, checkout, timeout, heartbeat) -> {
            String file = command.getFirst().equals("check-one") ? "feature-1.txt" : "feature-2.txt";
            try { return new BoundedProcess.Result(Files.readString(checkout.resolve(file)).equals(
                    command.getFirst().equals("check-one") ? "feature 1" : "feature 2") ? 0 : 1, "ok", false); }
            catch (Exception exc) { return new BoundedProcess.Result(1, "missing " + file, false); }
        });
        withRoot(() -> {
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, verifier))
                    .as("source repair state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            assertThat(calls).hasValue(2);
            Map<String,Object> state = new StateStore(root.resolve("state/epic-10.json")).load();
            List<?> tasks = (List<?>) ((Map<?,?>) state.get("task_ledger")).get("tasks");
            assertThat(tasks).hasSize(2);
            assertThat(((Map<?,?>) tasks.get(1)).get("depends_on")).isEqualTo(List.of("local-AC-1"));
            assertThat(tasks).allSatisfy(task -> assertThat(((Map<?,?>) task).get("status")).isEqualTo("VERIFIED"));
            Map<?,?> result = new ObjectMapper().readValue(root.resolve("state/local-result-11.json").toFile(), Map.class);
            assertThat(((Map<?,?>) result.get("criteria")).keySet().stream().map(String::valueOf).toList())
                    .containsExactlyInAnyOrder("AC-1", "AC-2");
        });
    }

    @Test void repeatedUnmetCriterionStopsIncompleteWithoutDelivery() throws Exception {
        Path repo = repository();
        config(repo);
        ready();
        Path request = request();
        AtomicInteger calls = new AtomicInteger();
        LocalRequestWorkflow.Worker worker = (cfg, prompt, checkout, heartbeat, timeout) -> {
            calls.incrementAndGet();
            return response(prompt, List.of());
        };
        RuntimeVerifier verifier = new RuntimeVerifier((command, checkout, timeout, heartbeat) ->
                new BoundedProcess.Result(1, "criterion still unmet", false));
        withRoot(() -> {
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, verifier)).isEqualTo(1);
            assertThat(calls.get()).isLessThanOrEqualTo(2);
            assertThat(Files.exists(root.resolve("state/local-result-11.json"))).isFalse();
            Map<String,Object> state = new StateStore(root.resolve("state/epic-10.json")).load();
            assertThat(state.get("local_outcome")).isEqualTo("INCOMPLETE");
            assertThat(state.get("validation_checkpoint")).isNull();
        });
    }

    @Test void sourceAmendmentInvalidatesEarlierDeliveryAndRequiresNewProof() throws Exception {
        Path repo = repository();
        config(repo);
        ready();
        Path request = request();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        RuntimeVerifier verifier = new RuntimeVerifier((command, checkout, timeout, heartbeat) -> {
            checks.incrementAndGet();
            try { return new BoundedProcess.Result(Files.readString(checkout.resolve("feature.txt"))
                    .equals("feature works") ? 0 : 1, "checked", false); }
            catch (Exception exc) { return new BoundedProcess.Result(1, "missing", false); }
        });
        LocalRequestWorkflow.Worker worker = (cfg, prompt, checkout, heartbeat, timeout) -> {
            calls.incrementAndGet();
            try {
                Path file = checkout.resolve("feature.txt");
                String expected = Files.exists(file) ? WorkerToolBoundary.sha256(Files.readAllBytes(file)) : "ABSENT";
                return response(prompt, List.of(Map.of("operation", "write", "path", "feature.txt",
                        "expected_sha256", expected, "content", "feature works")));
            } catch (Exception exc) { throw new IllegalStateException(exc); }
        };
        withRoot(() -> {
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, verifier))
                    .as("amended request state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            Map<String,Object> first = new StateStore(root.resolve("state/epic-10.json")).load();
            String oldProof = Engine.asString(((Map<?, ?>) first.get("local_delivery_result")).get("proof_identity"));
            Path checkout = Path.of(Engine.asString(first.get("worker_checkout")));
            Files.writeString(checkout.resolve("feature.txt"), "regressed");
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, verifier)).isZero();
            Map<String,Object> current = new StateStore(root.resolve("state/epic-10.json")).load();
            assertThat(((Map<?, ?>) current.get("local_delivery_result")).get("proof_identity")).isEqualTo(oldProof);
            assertThat(checks).hasValue(5);
            assertThat(calls).hasValue(2);
        });
    }

    @Test void interruptedUnappliedEffectResumesAndTamperedDeliveryIsRebuilt() throws Exception {
        Path repo = repository();
        config(repo);
        ready();
        Path request = request();
        AtomicInteger calls = new AtomicInteger();
        LocalRequestWorkflow.Worker worker = (cfg, prompt, checkout, heartbeat, timeout) -> {
            calls.incrementAndGet();
            return response(prompt, List.of(Map.of("operation", "write", "path", "feature.txt",
                    "expected_sha256", "ABSENT", "content", "feature works")));
        };
        withRoot(() -> {
            Path checkout = IsolatedCheckout.open(repo, 10);
            StateStore store = new StateStore(root.resolve("state/epic-10.json"));
            Map<String,Object> state = store.load();
            state.put("tool_effect_pending", Map.of("execution_id", "interrupted",
                    "source", GitOperations.forRepo(checkout).recoveryFingerprint()));
            store.save(state);
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, fileVerifier()))
                    .as("tampered artifact recovery state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            assertThat(calls).hasValue(1);
            Path bundle = root.resolve("state/local-bundle-11.json");
            Files.writeString(bundle, "tampered");
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, fileVerifier()))
                    .as("artifact recovery state: %s", new StateStore(root.resolve("state/epic-10.json")).load()).isZero();
            assertThat(calls).hasValue(1);
            Map<?,?> delivered = new ObjectMapper().readValue(root.resolve("state/local-result-11.json").toFile(), Map.class);
            assertThat(delivered.get("bundle_sha256"))
                    .isEqualTo(WorkerToolBoundary.sha256(Files.readAllBytes(bundle)));
        });
    }

    @Test void interruptedEffectWithChangedSourceBlocksWithoutReplayingWorker() throws Exception {
        Path repo = repository();
        config(repo);
        ready();
        Path request = request();
        withRoot(() -> {
            Path checkout = IsolatedCheckout.open(repo, 10);
            StateStore store = new StateStore(root.resolve("state/epic-10.json"));
            Map<String,Object> state = store.load();
            state.put("tool_effect_pending", Map.of("execution_id", "interrupted", "source", "old-fingerprint"));
            store.save(state);
            AtomicInteger calls = new AtomicInteger();
            LocalRequestWorkflow.Worker worker = (cfg, prompt, path, heartbeat, timeout) -> {
                calls.incrementAndGet();
                return response(prompt, List.of());
            };
            assertThat(Controller.run(new String[] {"--request-file", request.toString()}, worker, fileVerifier()))
                    .isEqualTo(1);
            assertThat(calls).hasValue(0);
            assertThat(new StateStore(root.resolve("state/epic-10.json")).load().get("phase"))
                    .isEqualTo("BLOCKED_REPOSITORY");
        });
    }

    private Path request() throws Exception {
        Path file = root.resolve("request.json");
        Files.writeString(file, "{\"title\":\"Add feature\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Create feature marker\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Feature marker exists\"}]}");
        return file;
    }

    private void ready() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/job-control.json"), "{\"job_start\":true}");
    }

    private RuntimeVerifier fileVerifier() {
        return new RuntimeVerifier((command, checkout, timeout, heartbeat) -> {
            try { return new BoundedProcess.Result(Files.readString(checkout.resolve("feature.txt"))
                    .equals("feature works") ? 0 : 1, "checked", false); }
            catch (Exception exc) { return new BoundedProcess.Result(1, "missing", false); }
        });
    }

    private CodexRunner.CodexResult response(Path prompt, List<Map<String,Object>> changes) {
        try {
            String protocol = Files.readString(prompt);
            Map<String,Object> payload = new LinkedHashMap<>();
            payload.put("schema_version", 1);
            payload.put("request_id", field(protocol, "request_id"));
            payload.put("requirements_hash", field(protocol, "requirements_hash"));
            payload.put("source_fingerprint", field(protocol, "source_fingerprint"));
            payload.put("status", "SUCCESS");
            payload.put("reason", "");
            List<String> allowed = permittedCriteria(protocol);
            List<Map<String, Object>> attributed = new ArrayList<>();
            for (Map<String, Object> change : changes) {
                Map<String, Object> row = new LinkedHashMap<>(change);
                row.put("criterion_ids", allowed);
                attributed.add(row);
            }
            payload.put("changes", attributed);
            return new CodexRunner.CodexResult(0, new ObjectMapper().writeValueAsString(payload));
        } catch (Exception exc) { throw new IllegalStateException(exc); }
    }

    private void withRoot(ThrowingRunnable action) throws Exception {
        String before = System.getProperty("codex.automation.root");
        System.setProperty("codex.automation.root", root.toString());
        try { action.run(); }
        finally {
            if (before == null) System.clearProperty("codex.automation.root");
            else System.setProperty("codex.automation.root", before);
        }
    }
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    private Path repository() throws Exception {
        Path repo = root.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("seed.txt"), "baseline");
        git(repo, "add", "seed.txt");
        git(repo, "commit", "-q", "-m", "baseline");
        return repo;
    }

    private void config(Path repo) throws Exception {
        Files.createDirectories(root.resolve("config"));
        Map<String,Object> config = Map.of("repository", Map.of("local_path", repo.toString(), "owner", "local", "name", "repo"),
                "epic", Map.of("number", 10), "codex", Map.of("executable", "codex", "model", "mock",
                        "isolation", "bubblewrap", "execution_timeout_seconds", 30),
                "automation", Map.of("dry_run", false, "max_task_attempts", 3),
                "workflow", Map.of(), "verification", Map.of("required", true,
                        "checks", List.of(Map.of("id", "feature", "command", List.of("mock-check"),
                                "criteria", List.of("AC-1")))));
        new ObjectMapper().writeValue(root.resolve("config/config.json").toFile(), config);
    }

    private static String field(String prompt, String name) {
        Matcher matcher = Pattern.compile(name + "=\\\"([^\\\"]+)\\\"").matcher(prompt);
        if (!matcher.find()) throw new IllegalArgumentException("Missing protocol field " + name);
        return matcher.group(1);
    }

    private static List<String> permittedCriteria(String prompt) {
        String marker = "allowed acceptance criteria JSON: ";
        int start = prompt.lastIndexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing permitted criteria in worker protocol");
        start += marker.length();
        int end = prompt.indexOf(')', start);
        if (end < 0) throw new IllegalArgumentException("Malformed permitted criteria in worker protocol");
        try { return new ObjectMapper().readValue(prompt.substring(start, end),
                new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (Exception exc) { throw new IllegalArgumentException("Malformed permitted criteria", exc); }
    }

    private static void git(Path repo, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(repo.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("Disposable Git setup failed");
    }
}
