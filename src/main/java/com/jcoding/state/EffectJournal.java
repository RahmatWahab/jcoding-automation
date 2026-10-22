package com.jcoding.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Read-back, then intent, mutation, read-back, receipt. Uncertain non-idempotent effects never replay blindly. */
public final class EffectJournal {
    private final StateStore store;
    public EffectJournal(StateStore store) { this.store = store; }

    public static String key(String kind, Map<String, Object> payload) {
        try {
            String text = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(Map.of("kind", kind, "payload", payload));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exc) { throw new IllegalArgumentException("Cannot identify effect", exc); }
    }

    public Object execute(String kind, Map<String, Object> payload, boolean replaySafe,
                          Function<String, Object> readBack, Function<String, Object> mutation, Runnable authorize) {
        String key = key(kind, payload);
        authorize.run();
        // A reservation remains uncertain across crashes, including a lost response.
        Object observed = readBack.apply(key);
        boolean fresh = store.reserveEffect(key, kind, payload);
        if (observed != null) {
            store.finishEffect(key, "VERIFIED", observed);
            return observed;
        }
        if (!fresh && !replaySafe && !store.consumeEffectRetry(key)) {
            store.finishEffect(key, "UNKNOWN", Map.of("reason", "Awaiting authoritative read-back"));
            throw new IllegalStateException("External effect " + key + " is uncertain; refusing duplicate mutation");
        }
        authorize.run(); // Recheck cancellation and policy immediately before the mutation.
        try {
            mutation.apply(key);
            observed = readBack.apply(key);
            if (observed == null) throw new IllegalStateException("External effect lacks authoritative confirmation");
            store.finishEffect(key, "VERIFIED", observed);
            return observed;
        } catch (RuntimeException exc) {
            // The remote operation may have succeeded despite a timeout or lost response.
            store.finishEffect(key, "UNKNOWN", Map.of("reason", "Mutation outcome requires reconciliation"));
            throw exc;
        }
    }
}
