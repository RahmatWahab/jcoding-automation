package com.rahmatwahab.codexautomation.execution;

import java.time.Duration;
import java.util.List;

/**
 * Boundary for timeout-bounded external process execution.
 */
public interface ProcessRunner {
    ProcessResult run(List<String> command, Duration timeout);
}
