package com.rahmatwahab.codexautomation.story;

import java.net.URI;

/**
 * Story metadata used by workflow decisions.
 */
public record StorySummary(int number, String title, URI url, boolean complete) {
}
