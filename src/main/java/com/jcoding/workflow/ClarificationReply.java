package com.jcoding.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bound, trusted issue-comment answer to a pending clarification. */
final class ClarificationReply {
    private static final Pattern BLOCK = Pattern.compile("(?s)```jcoding-intent\\s*\\n(.*?)```");
    private static final Set<String> TRUSTED = Set.of("OWNER", "MEMBER", "COLLABORATOR");
    private ClarificationReply() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> latest(Object raw, String requirementsHash) {
        return latest(raw, requirementsHash, "");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> latest(Object raw, String requirementsHash, String requester) {
        return latest(raw, requirementsHash, requester, Set.of());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> latest(Object raw, String requirementsHash, String requester, Set<String> delegated) {
        String marker = "<!-- jcoding-answer:" + requirementsHash.substring(0, Math.min(16, requirementsHash.length())) + " -->";
        Map<String, Object> answer = Map.of();
        String latest = "";
        for (Object page : raw instanceof List<?> pages ? pages : List.of()) {
            for (Object item : page instanceof List<?> comments ? comments : List.of()) {
                if (!(item instanceof Map<?, ?> comment)) continue;
                String body = Engine.asString(comment.get("body"));
                String association = Engine.asString(comment.get("author_association"));
                if (association.isBlank()) association = Engine.asString(comment.get("authorAssociation"));
                String author = "";
                if (comment.get("author") instanceof Map<?, ?> user) author = Engine.asString(user.get("login"));
                if (!TRUSTED.contains(association) || !body.contains(marker)
                        || author.isBlank() || (!author.equals(requester) && !delegated.contains(author))) continue;
                var block = BLOCK.matcher(body);
                if (!block.find()) continue;
                String json = block.group(1);
                if (block.find()) continue;
                String created = Engine.asString(comment.get("created_at"));
                if (created.isBlank()) created = Engine.asString(comment.get("createdAt"));
                if (created.compareTo(latest) < 0) continue;
                try {
                    Map<String, Object> contract = new ObjectMapper().readValue(json, Map.class);
                    if (contract.isEmpty()) continue;
                    answer = Map.of("id", Engine.asString(comment.get("id")), "body", body, "contract", contract);
                    latest = created;
                } catch (java.io.IOException ignored) { /* Invalid answers never alter the active contract. */ }
            }
        }
        return answer;
    }
}
