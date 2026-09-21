package com.rahmatwahab.codexautomation;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic prompt composition.
 *
 * <p>Implements the corresponding workflow behavior
 * {@code execution_phase_for_prompt}). Expands {@code {{COMMON}}} with the
 * shared policy and appends the authoritative controller context as sorted,
 * pretty-printed JSON so worker prompts are stable and reproducible.</p>
 */
public final class PromptRenderer {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private static final ObjectWriter SORTED_PRETTY = MAPPER.writer(compactColonPrettyPrinter());

    /**
     * A pretty printer that emits stable JSON:
     * 2-space indentation, LF newlines, and no space before the key colon.
     */
    private static com.fasterxml.jackson.core.util.DefaultPrettyPrinter compactColonPrettyPrinter() {
        com.fasterxml.jackson.core.util.DefaultIndenter indenter =
                new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n");
        com.fasterxml.jackson.core.util.Separators separators =
                com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(
                                com.fasterxml.jackson.core.util.Separators.Spacing.AFTER);
        com.fasterxml.jackson.core.util.DefaultPrettyPrinter printer =
                new com.fasterxml.jackson.core.util.DefaultPrettyPrinter(separators);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    private static final Map<String, String> PHASE_BY_PROMPT = Map.of(
            "plan.md", "PLANNING",
            "implement.md", "IMPLEMENTING",
            "verify.md", "VERIFYING",
            "review.md", "REVIEWING_CODE",
            "feedback.md", "FIXING_FEEDBACK",
            "reconcile.md", "RECONCILING",
            "discover.md", "IMPLEMENTING",
            "final-review.md", "REVIEWING_CODE",
            "pr.md", "REVIEWING_CODE");

    private PromptRenderer() {
    }

    /** Compose a phase prompt: expand {@code {{COMMON}}} and append controller context JSON. */
    public static String render(Path root, String relative, Map<String, Object> context) {
        try {
            Path promptPath = root.resolve(relative);
            String text = Files.readString(promptPath);
            String common = Files.readString(root.resolve("prompts").resolve("common.md"));
            text = text.replace("{{COMMON}}", common);
            String contextText = SORTED_PRETTY.writeValueAsString(sortedDeep(context));
            return text + "\n\nCONTROLLER CONTEXT (authoritative; do not modify):\n```json\n"
                    + contextText + "\n```\n";
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    /** Map a prompt filename to its active execution phase (default IMPLEMENTING). */
    public static String executionPhaseForPrompt(String promptName) {
        return PHASE_BY_PROMPT.getOrDefault(promptName, "IMPLEMENTING");
    }

    /** Recursively sort map keys so JSON output is deterministic. */
    @SuppressWarnings("unchecked")
    private static Object sortedDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortedDeep(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (Object item : iterable) {
                list.add(sortedDeep(item));
            }
            return list;
        }
        return value;
    }
}
