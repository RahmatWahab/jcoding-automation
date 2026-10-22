package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jcoding.state.StateStore;
import com.jcoding.state.WorkflowLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Narrow regressions for controller owned boundaries; no provider or remote calls. */
class AuditFocusedRegressionMockTest {
    @TempDir Path root;

    @Test void emptyVerifierRecipeCannotAuthorizeCompletion() {
        Map<String,Object> state = new LinkedHashMap<>(Map.of("goal_contract", Map.of(
                "requirements_hash", "r1", "criteria", List.of(Map.of("id", "AC-1", "text", "works")))));
        RuntimeVerifier verifier = new RuntimeVerifier((command, repo, timeout, beat) -> {
            throw new AssertionError("No external process should run");
        });
        boolean accepted = verifier.verify(root, new GitOperations(root,
                (repo, args) -> new GitOperations.GitResult(0, "head", "")), state,
                Map.of("verification", Map.of("checks", List.of())), () -> {}, () -> {});
        assertThat(accepted).isFalse();
        assertThat(state).doesNotContainKey("validation_checkpoint");
    }

    @Test void localIntakeRejectsMalformedOrSymlinkedRequestAndCannotCallGithub() throws Exception {
        Path request = root.resolve("request.json");
        Files.writeString(request, "{\"title\":\"Fix\",\"criteria\":[]}");
        LocalRequestGitHub intake = new LocalRequestGitHub(request, 42);
        assertThatThrownBy(() -> intake.issue(43)).hasMessageContaining("requires title and criteria");
        assertThatThrownBy(() -> intake.run("api", "repos/owner/repo"))
                .hasMessageContaining("Local request forbids GitHub CLI");
        Path link = root.resolve("linked.json");
        Files.createSymbolicLink(link, request);
        assertThatThrownBy(() -> new LocalRequestGitHub(link, 42).issue(43))
                .hasMessageContaining("Unsafe local request");
    }

    @Test void cancelledPolicyRejectsAnAuthorizedGitMutationAtEffectBoundary() throws Exception {
        Path control = root.resolve("job-control.json");
        Files.writeString(control, "{\"job_start\":true,\"cancel\":true}");
        ActionPolicy policy = new ActionPolicy(Map.of("_job_control_path", control.toString(),
                "workflow", Map.of("allow_push", true), "automation", Map.of("dry_run", false)));
        assertThatThrownBy(() -> policy.require("git")).isInstanceOf(ActionPolicy.Cancelled.class);
    }

    @Test void duplicateWorkerFooterCannotAdvancePhase() {
        String output = "RESULT_STATUS: DONE\nRESULT_STATUS: DONE";
        assertThat(StateRecovery.parseResult(output)).doesNotContainKey("result_status");
    }

    @Test void recoveredLockFencesOldOwnerAndSqliteRejectsItsStaleWrite() throws Exception {
        Path lockPath = root.resolve("lease.lock");
        WorkflowLock first = WorkflowLock.acquire(lockPath, 1, 3, 10);
        StateStore store = new StateStore(root.resolve("state.json"));
        Map<String,Object> old = store.load();
        store.save(old);
        Files.writeString(lockPath.resolve("owner.json"), "{\"pid\":999999999,"
                + "\"owner_token\":\"replaced\",\"started_at\":\"2000-01-01T00:00:00Z\","
                + "\"heartbeat_at\":\"2000-01-01T00:00:00Z\"}");
        WorkflowLock second = WorkflowLock.acquire(lockPath, 1, 3, 10);
        assertThat(second).isNotNull();
        assertThatThrownBy(first::refresh).isInstanceOf(WorkflowLock.LockOwnershipLost.class);
        Map<String,Object> current = store.load();
        current.put("phase", "IDLE");
        store.save(current);
        assertThatThrownBy(() -> store.save(old)).isInstanceOf(StateStore.StaleState.class);
        second.release();
    }
}
