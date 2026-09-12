package com.rahmatwahab.codexautomation.validation;

/**
 * Evidence summary produced by a validation run.
 */
public record ValidationEvidence(String planName, boolean passed) {
}
