package com.jcoding.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** One policy boundary for controller side effects, including resumed operations. */
public final class ActionPolicy {
    private final Map<String, Object> config;
    private final boolean dryRun;
    public ActionPolicy(Map<String, Object> config) {
        this(config, Engine.asBool(Config.section(config, "automation").get("dry_run"), true));
    }
    public ActionPolicy(Map<String, Object> config, boolean dryRun) { this.config = config; this.dryRun = dryRun; }

    public void checkActive() {
        Object path = config.get("_job_control_path");
        if (path == null) return; // Embedded controller: caller owns cancellation.
        try {
            Map<?, ?> control = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(Path.of(path.toString())), Map.class);
            if (Boolean.TRUE.equals(control.get("cancel"))
                    || (!Boolean.TRUE.equals(config.get("_force_run")) && !Boolean.TRUE.equals(control.get("job_start")))) {
                throw new Cancelled();
            }
        } catch (java.io.IOException | IllegalArgumentException exc) {
            throw new Cancelled();
        }
    }

    public boolean explicitlyCancelled() {
        Object path = config.get("_job_control_path");
        if (path == null) return false;
        try {
            Map<?, ?> control = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(Files.readString(Path.of(path.toString())), Map.class);
            return Boolean.TRUE.equals(control.get("cancel"));
        } catch (java.io.IOException exc) { return false; }
    }

    public void require(String action) {
        checkActive();
        if (dryRun) {
            throw new IllegalStateException("Dry-run forbids mutation: " + action);
        }
        String flag = switch (action) {
            case "comment", "reply" -> "allow_comments";
            case "resolve" -> "allow_thread_resolution";
            case "create_pr" -> "allow_pr_creation";
            case "merge" -> "auto_merge";
            default -> "allow_push";
        };
        Map<String, Object> workflow = Config.section(config, "workflow");
        if (!Boolean.TRUE.equals(workflow.get(flag))
                || (action.equals("merge") && !Boolean.TRUE.equals(workflow.get("allow_push")))) {
            throw new IllegalStateException("Policy forbids " + action + ": " + flag);
        }
    }

    public static final class Cancelled extends IllegalStateException {
        public Cancelled() { super("Execution cancelled by job control; work is preserved"); }
    }
}
