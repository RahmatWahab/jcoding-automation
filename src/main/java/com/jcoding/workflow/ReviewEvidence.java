package com.jcoding.workflow;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact worker response, including its process outcome; never reconstruct finding text. */
record ReviewEvidence(int exitCode, String output) {
    ReviewEvidence {
        output = output == null ? "" : output;
    }

    boolean passed() {
        String evidence = field("RESULT_EVIDENCE");
        String blocker = field("RESULT_BLOCKER");
        return exitCode == 0 && "SUCCESS".equals(field("RESULT_STATUS"))
                && !evidence.isBlank()
                && !evidence.matches("(?i)(PROMPT_ABSENT|RENDER_FAILED|ERROR|MISSING|NONE|UNKNOWN|INVALID|INCOMPLETE|DEFERRED_TO_CI)")
                && (blocker.isBlank() || "NONE".equals(blocker))
                && !Pattern.compile("(?i)\\[(BLOCKER|CRITICAL)\\]").matcher(output).find();
    }

    private String field(String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + key + ":[ \\t]*([^\\r\\n]*)$").matcher(output);
        String value = "";
        boolean found = false;
        while (matcher.find()) {
            // Conflicting/duplicate footer fields are ambiguous, never a clean verdict.
            if (found) {
                return "INVALID";
            }
            found = true;
            value = matcher.group(1).trim();
        }
        return key.equals("RESULT_EVIDENCE") ? value : value.toUpperCase(Locale.ROOT);
    }
}
