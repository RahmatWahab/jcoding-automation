package com.rahmatwahab.codexautomation.review;

import java.util.Objects;

/**
 * Stable GitHub review-thread identifier.
 */
public record ReviewThreadId(String value) {
    public ReviewThreadId {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.startsWith("PRRT_")) {
            throw new IllegalArgumentException("review thread id must start with PRRT_");
        }
    }
}
