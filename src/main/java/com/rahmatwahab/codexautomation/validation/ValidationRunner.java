package com.rahmatwahab.codexautomation.validation;

/**
 * Boundary for business-value validation plans.
 */
public interface ValidationRunner {
    ValidationEvidence run(ValidationPlan plan);
}
