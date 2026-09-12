package com.rahmatwahab.codexautomation.story;

import java.util.List;
import java.util.Optional;

/**
 * Selects the next dependency-eligible story for an EPIC.
 */
public interface StorySelector {
    Optional<StorySummary> selectNext(List<StorySummary> stories);
}
