package com.rahmatwahab.codexautomation.scheduler;

/**
 * Decides whether a scheduled invocation may start work.
 */
public interface JobControlGate {
    JobControlDecision evaluate();
}
