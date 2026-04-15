package com.ariadne.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record GraphData(
        String scope,
        Map<String, Object> payload
) {

    public GraphData {
        scope = scope == null || scope.isBlank() ? "global" : scope;
        payload = immutableMap(payload);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
