package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
class LlmFieldAllowlist {

    private static final Set<String> ROOT_KEYS = Set.of("nodes", "edges", "metadata");
    private static final Set<String> NODE_KEYS = Set.of("id", "type", "parentNode", "data");
    private static final Set<String> EDGE_KEYS = Set.of("id", "source", "target", "type", "data");

    private final AriadneProperties ariadneProperties;

    LlmFieldAllowlist(AriadneProperties ariadneProperties) {
        this.ariadneProperties = ariadneProperties;
    }

    SanitizedGraphData apply(SanitizedGraphData sanitized) {
        if (sanitized == null) {
            return new SanitizedGraphData("global", TransmissionLevel.STRICT, Map.of());
        }

        return new SanitizedGraphData(
                sanitized.scope(),
                sanitized.transmissionLevel(),
                projectRoot(sanitized.payload(), allowedFields(sanitized.transmissionLevel()))
        );
    }

    private Map<String, Object> projectRoot(Map<String, Object> payload, Set<String> allowedFields) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }

        var projected = new LinkedHashMap<String, Object>();
        for (var entry : payload.entrySet()) {
            if (!ROOT_KEYS.contains(entry.getKey())) {
                continue;
            }

            projected.put(entry.getKey(), switch (entry.getKey()) {
                case "nodes" -> projectNodes(entry.getValue(), allowedFields);
                case "edges" -> projectEdges(entry.getValue(), allowedFields);
                case "metadata" -> entry.getValue();
                default -> null;
            });
        }
        return Collections.unmodifiableMap(projected);
    }

    private List<Map<String, Object>> projectNodes(Object rawValue, Set<String> allowedFields) {
        if (!(rawValue instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }

        var projected = new ArrayList<Map<String, Object>>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> node)) {
                continue;
            }

            var projectedNode = new LinkedHashMap<String, Object>();
            for (var entry : node.entrySet()) {
                var key = String.valueOf(entry.getKey());
                if (!NODE_KEYS.contains(key)) {
                    continue;
                }

                if ("data".equals(key)) {
                    projectedNode.put(key, projectDataMap(entry.getValue(), allowedFields));
                    continue;
                }
                projectedNode.put(key, entry.getValue());
            }
            projected.add(Collections.unmodifiableMap(projectedNode));
        }

        return List.copyOf(projected);
    }

    private List<Map<String, Object>> projectEdges(Object rawValue, Set<String> allowedFields) {
        if (!(rawValue instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }

        var projected = new ArrayList<Map<String, Object>>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> edge)) {
                continue;
            }

            var projectedEdge = new LinkedHashMap<String, Object>();
            for (var entry : edge.entrySet()) {
                var key = String.valueOf(entry.getKey());
                if (!EDGE_KEYS.contains(key)) {
                    continue;
                }

                if ("data".equals(key)) {
                    var dataMap = projectDataMap(entry.getValue(), allowedFields);
                    if (!dataMap.isEmpty()) {
                        projectedEdge.put(key, dataMap);
                    }
                    continue;
                }
                projectedEdge.put(key, entry.getValue());
            }
            projected.add(Collections.unmodifiableMap(projectedEdge));
        }

        return List.copyOf(projected);
    }

    private Map<String, Object> projectDataMap(Object rawValue, Set<String> allowedFields) {
        if (!(rawValue instanceof Map<?, ?> values) || values.isEmpty()) {
            return Map.of();
        }

        var projected = new LinkedHashMap<String, Object>();
        for (var entry : values.entrySet()) {
            var key = String.valueOf(entry.getKey());
            if (!allowedFields.contains(normalize(key))) {
                continue;
            }
            projected.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(projected);
    }

    private Set<String> allowedFields(TransmissionLevel transmissionLevel) {
        var allowed = new LinkedHashSet<String>();
        for (var field : ariadneProperties.getLlm().getAllowedFields()) {
            allowed.add(normalize(field));
        }
        if (transmissionLevel == TransmissionLevel.VERBOSE) {
            for (var field : ariadneProperties.getLlm().getVerboseAdditionalFields()) {
                allowed.add(normalize(field));
            }
        }
        return Set.copyOf(allowed);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
