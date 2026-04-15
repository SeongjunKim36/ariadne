package com.ariadne.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record SanitizedGraphData(
        String scope,
        TransmissionLevel transmissionLevel,
        Map<String, Object> payload
) {

    SanitizedGraphData {
        scope = scope == null || scope.isBlank() ? "global" : scope;
        transmissionLevel = transmissionLevel == null ? TransmissionLevel.STRICT : transmissionLevel;
        payload = immutableMap(payload);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
