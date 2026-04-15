package com.ariadne.llm;

import com.ariadne.security.RedactionEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class LlmDataSanitizer {

    private final RedactionEngine redactionEngine;

    LlmDataSanitizer(RedactionEngine redactionEngine) {
        this.redactionEngine = redactionEngine;
    }

    SanitizedGraphData sanitize(GraphData raw, TransmissionLevel transmissionLevel) {
        if (raw == null) {
            return new SanitizedGraphData("global", transmissionLevel, Map.of());
        }
        return new SanitizedGraphData(
                raw.scope(),
                transmissionLevel,
                sanitizeMap(raw.payload())
        );
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return Map.of();
        }

        var sanitized = new LinkedHashMap<String, Object>();
        for (var entry : rawValues.entrySet()) {
            var key = String.valueOf(entry.getKey());
            sanitized.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private List<Object> sanitizeList(List<?> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }

        var sanitized = new ArrayList<Object>(rawValues.size());
        for (var value : rawValues) {
            sanitized.add(sanitizeValue(null, value));
        }
        return Collections.unmodifiableList(sanitized);
    }

    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return redactionEngine.redactValue(key, stringValue);
        }
        if (value instanceof Map<?, ?> mapValue) {
            return sanitizeMap(mapValue);
        }
        if (value instanceof List<?> listValue) {
            return sanitizeList(listValue);
        }
        return value;
    }
}
