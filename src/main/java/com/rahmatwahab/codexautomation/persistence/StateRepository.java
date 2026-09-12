package com.rahmatwahab.codexautomation.persistence;

/**
 * Boundary for durable workflow state.
 */
public interface StateRepository {
    WorkflowState load();

    void save(WorkflowState state);
}
