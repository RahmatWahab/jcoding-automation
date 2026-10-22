package com.jcoding.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jcoding.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline business outcomes using a disposable SQLite state and mocked external boundary. */
class AuditBusinessAcceptanceMockTest {
    @TempDir Path root;

    @Test void requestAmendmentPersistsPriorRequirementAndRejectsStaleOwner() throws Exception {
        Path request = root.resolve("request.json");
        Files.writeString(request, "{\"title\":\"Fix login\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Login works\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Login test passes\"}]}");
        LocalRequestGitHub intake = new LocalRequestGitHub(request, 10);
        Map<String,Object> config = Map.of("repository", Map.of("owner", "local", "name", "repo"));
        StateStore store = new StateStore(root.resolve("state.json"));
        Map<String,Object> active = store.load();
        Map<String,Object> goal = GoalContract.capture(cast(intake.issue(11)), cast(intake.issue(10)), config);
        GoalContract.observe(active, goal);
        assertThat(cast(active.get("goal_contract")).get("status")).isEqualTo("ACTIVE");
        store.save(active);
        Map<String,Object> stale = store.load();
        Files.writeString(request, "{\"title\":\"Fix login\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R2\",\"text\":\"Add audit event\"}],"
                + "\"criteria\":[{\"id\":\"AC-2\",\"text\":\"Audit test passes\"}]}");
        GoalContract.observe(active, GoalContract.capture(cast(intake.issue(11)), cast(intake.issue(10)), config));
        store.save(active);
        Map<String,Object> latest = cast(store.load().get("goal_contract"));
        assertThat((List<?>) latest.get("requirement_items")).hasSize(2);
        assertThat((List<?>) latest.get("criteria")).hasSize(2);
        assertThat(latest.get("revision")).isEqualTo(2);
        stale.put("phase", "IDLE");
        assertThatThrownBy(() -> store.save(stale)).isInstanceOf(StateStore.StaleState.class);
    }

    @Test void explicitSupersessionRetainsHistoryAndInvalidatesOldProof() throws Exception {
        Path request = root.resolve("request.json");
        Files.writeString(request, "{\"title\":\"Fix login\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Use password\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Password works\"}]}");
        LocalRequestGitHub intake = new LocalRequestGitHub(request, 20);
        Map<String,Object> config = Map.of();
        Map<String,Object> state = new LinkedHashMap<>();
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(21)), Map.of(), config));
        state.put("validation_checkpoint", Map.of("identity", "old-proof"));
        Files.writeString(request, "{\"title\":\"Fix login\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R2\",\"text\":\"Use passkeys\"}],"
                + "\"supersedes\":[\"R1\",\"AC-1\"],"
                + "\"criteria\":[{\"id\":\"AC-2\",\"text\":\"Passkey works\"}]}");
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(21)), Map.of(), config));
        Map<String,Object> goal = cast(state.get("goal_contract"));
        assertThat((List<?>) goal.get("requirement_items")).hasSize(1);
        assertThat((List<?>) goal.get("supersession_history")).hasSize(1);
        assertThat(state).doesNotContainKey("validation_checkpoint");
        assertThat((List<?>) state.get("goal_history")).hasSize(1);
        Map<?, ?> oldRevision = (Map<?, ?>) ((List<?>) state.get("goal_history")).getFirst();
        assertThat(oldRevision.get("status")).isEqualTo("SUPERSEDED");
        assertThat(oldRevision.get("superseded_by_revision")).isEqualTo(2);
    }

    @Test void terminalIntentCannotResumeWithoutAChangedAuthorizedRevision() throws Exception {
        Path request = root.resolve("request.json");
        Files.writeString(request, "{\"title\":\"Build index\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Index records\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Index check passes\"}]}");
        LocalRequestGitHub intake = new LocalRequestGitHub(request, 30);
        Map<String,Object> config = Map.of();
        Map<String,Object> state = new LinkedHashMap<>();
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(31)), Map.of(), config));
        Map<String,Object> goal = cast(state.get("goal_contract"));
        goal.put("status", "CANCELLED");
        goal.put("terminal_at", "2026-10-18T00:00:00Z");
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(31)), Map.of(), config));
        assertThat(cast(state.get("goal_contract"))).containsEntry("status", "CANCELLED");
        Files.writeString(request, "{\"title\":\"Build index\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R2\",\"text\":\"Index records and IDs\"}],"
                + "\"supersedes\":[\"R1\"],\"criteria\":[{\"id\":\"AC-2\",\"text\":\"Expanded index check\"}]}");
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(31)), Map.of(), config));
        Map<String,Object> revised = cast(state.get("goal_contract"));
        assertThat(revised.get("status")).isEqualTo("ACTIVE");
        assertThat(revised.get("resumed_from_status")).isEqualTo("CANCELLED");
        assertThat(state).doesNotContainKey("validation_checkpoint");
    }

    @Test void constraintsKeepProvenanceUnlessExplicitlySupersededAndObjectiveChangesClarify() throws Exception {
        Path request = root.resolve("request.json");
        Files.writeString(request, "{\"title\":\"Build index\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Index records\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Index check passes\"}],"
                + "\"constraints\":[\"No network\"]}");
        LocalRequestGitHub intake = new LocalRequestGitHub(request, 50);
        Map<String, Object> state = new LinkedHashMap<>();
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(51)), Map.of(), Map.of()));
        Map<?, ?> oldConstraint = (Map<?, ?>) ((List<?>) cast(state.get("goal_contract")).get("constraint_items")).getFirst();
        String oldId = String.valueOf(oldConstraint.get("id"));
        Files.writeString(request, "{\"title\":\"Build index\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Index records\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Index check passes\"}],"
                + "\"constraints\":[\"Use mock transport\"],\"supersedes\":[\"" + oldId + "\"]}");
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(51)), Map.of(), Map.of()));
        Map<String, Object> revised = cast(state.get("goal_contract"));
        assertThat(revised.get("constraints")).isEqualTo(List.of("Use mock transport"));
        assertThat((List<?>) revised.get("supersession_history")).anySatisfy(row -> {
            Map<?, ?> history = (Map<?, ?>) row;
            assertThat(history.get("kind")).isEqualTo("constraint");
            assertThat(history.get("id")).isEqualTo(oldId);
        });

        Files.writeString(request, "{\"title\":\"Rebuild index service\",\"intent\":\"implement_story\","
                + "\"requirements\":[{\"id\":\"R1\",\"text\":\"Index records\"}],"
                + "\"criteria\":[{\"id\":\"AC-1\",\"text\":\"Index check passes\"}],"
                + "\"constraints\":[\"Use mock transport\"]}");
        GoalContract.observe(state, GoalContract.capture(cast(intake.issue(51)), Map.of(), Map.of()));
        assertThat(cast(state.get("goal_contract")).get("status")).isEqualTo("NEEDS_CLARIFICATION");
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value) {
        return (Map<String,Object>) value;
    }
}
