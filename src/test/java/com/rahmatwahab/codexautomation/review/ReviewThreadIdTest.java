package com.rahmatwahab.codexautomation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReviewThreadIdTest {
    @Test
    @DisplayName("Should accept GitHub review thread identifiers")
    void shouldAcceptGitHubReviewThreadIdentifiers() {
        var id = new ReviewThreadId("PRRT_kwDOUYOyIM6hzeIa");

        assertThat(id.value()).isEqualTo("PRRT_kwDOUYOyIM6hzeIa");
    }

    @Test
    @DisplayName("Should reject review comment identifiers at thread boundary")
    void shouldRejectReviewCommentIdentifiersAtThreadBoundary() {
        assertThatThrownBy(() -> new ReviewThreadId("PRRC_kwDOUYOyIM7uRZW5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRRT_");
    }
}
