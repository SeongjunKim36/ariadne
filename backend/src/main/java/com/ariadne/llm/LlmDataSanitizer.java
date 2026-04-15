package com.ariadne.llm;

import com.ariadne.security.RedactionEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
class LlmDataSanitizer {

    private static final Set<String> ALWAYS_EXCLUDED_KEYS = Set.of(
            "secrets",
            "secretOptions",
            "repositoryCredentials"
    );

    private static final Set<String> NORMAL_EXCLUDED_KEYS = Set.of(
            "tags",
            "env",
            "envVars",
            "environmentVariables"
    );

    private static final Set<String> STRICT_EXCLUDED_KEYS = Set.of(
            "tags",
            "env",
            "envVars",
            "environmentVariables",
            "containers",
            "containerDefinitions",
            "proxyPasses",
            "upstreams",
            "rawConfig",
            "assumeRolePolicy",
            "attachedPolicies",
            "taskDefinition"
    );

    private static final Set<String> IDENTIFIER_KEYS = Set.of(
            "id",
            "name",
            "resourceId",
            "arn"
    );

    private final RedactionEngine redactionEngine;

    LlmDataSanitizer(RedactionEngine redactionEngine) {
        this.redactionEngine = redactionEngine;
    }

    SanitizedGraphData sanitize(GraphData raw, TransmissionLevel transmissionLevel) {
        if (raw == null) {
            return new SanitizedGraphData("global", transmissionLevel, Map.of());
        }
        var aliases = buildAliasRegistry(raw.payload());
        return new SanitizedGraphData(
                raw.scope(),
                transmissionLevel,
                sanitizeMap(raw.payload(), transmissionLevel, aliases, null)
        );
    }

    private Map<String, Object> sanitizeMap(
            Map<?, ?> rawValues,
            TransmissionLevel transmissionLevel,
            AliasRegistry aliases,
            String currentNodeAlias
    ) {
        if (rawValues == null || rawValues.isEmpty()) {
            return Map.of();
        }

        var sanitized = new LinkedHashMap<String, Object>();
        for (var entry : rawValues.entrySet()) {
            var key = String.valueOf(entry.getKey());
            if (shouldExcludeKey(key, transmissionLevel)) {
                continue;
            }

            if (isGraphNodeRecord(rawValues)) {
                sanitized.put(key, sanitizeGraphNodeField(key, entry.getValue(), transmissionLevel, aliases));
                continue;
            }

            if (isGraphEdgeRecord(rawValues)) {
                sanitized.put(key, sanitizeGraphEdgeField(key, entry.getValue(), transmissionLevel, aliases));
                continue;
            }

            if (transmissionLevel == TransmissionLevel.STRICT && currentNodeAlias != null && IDENTIFIER_KEYS.contains(key)) {
                sanitized.put(key, currentNodeAlias);
                continue;
            }

            var sanitizedValue = sanitizeValue(key, entry.getValue(), transmissionLevel, aliases, currentNodeAlias);
            if (transmissionLevel == TransmissionLevel.STRICT
                    && sanitizedValue == null
                    && (entry.getValue() instanceof List<?> || entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            sanitized.put(key, sanitizedValue);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private List<Object> sanitizeList(
            List<?> rawValues,
            TransmissionLevel transmissionLevel,
            AliasRegistry aliases,
            String currentNodeAlias
    ) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }

        var sanitized = new ArrayList<Object>(rawValues.size());
        for (int index = 0; index < rawValues.size(); index++) {
            var value = rawValues.get(index);
            if (transmissionLevel == TransmissionLevel.STRICT && value instanceof Map<?, ?> mapValue && isGraphEdgeRecord(mapValue)) {
                sanitized.add(sanitizeStrictEdgeRecord(asMap(mapValue), index + 1, aliases));
                continue;
            }
            if (transmissionLevel == TransmissionLevel.STRICT && currentNodeAlias != null && value instanceof String stringValue) {
                sanitized.add(redactScalar(null, stringValue, transmissionLevel, aliases, currentNodeAlias));
                continue;
            }
            sanitized.add(sanitizeValue(null, value, transmissionLevel, aliases, currentNodeAlias));
        }
        return Collections.unmodifiableList(sanitized);
    }

    private Object sanitizeValue(
            String key,
            Object value,
            TransmissionLevel transmissionLevel,
            AliasRegistry aliases,
            String currentNodeAlias
    ) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return redactScalar(key, stringValue, transmissionLevel, aliases, currentNodeAlias);
        }
        if (value instanceof Map<?, ?> mapValue) {
            return sanitizeMap(mapValue, transmissionLevel, aliases, currentNodeAlias);
        }
        if (value instanceof List<?> listValue) {
            if (transmissionLevel == TransmissionLevel.STRICT) {
                return sanitizeStrictList(key, listValue, aliases, currentNodeAlias);
            }
            return sanitizeList(listValue, transmissionLevel, aliases, currentNodeAlias);
        }
        return value;
    }

    private Object sanitizeStrictList(
            String key,
            List<?> listValue,
            AliasRegistry aliases,
            String currentNodeAlias
    ) {
        if ("nodes".equals(key) || "edges".equals(key)) {
            return sanitizeList(listValue, TransmissionLevel.STRICT, aliases, currentNodeAlias);
        }

        var containsOnlyScalars = listValue.stream().allMatch(this::isScalarValue);
        if (containsOnlyScalars) {
            return sanitizeList(listValue, TransmissionLevel.STRICT, aliases, currentNodeAlias);
        }

        return null;
    }

    private Map<String, Object> sanitizeStrictEdgeRecord(
            Map<String, Object> rawEdge,
            int edgeIndex,
            AliasRegistry aliases
    ) {
        var sanitized = new LinkedHashMap<String, Object>();
        sanitized.put("id", "EDGE-" + edgeIndex);
        sanitized.put("source", aliases.resolveNodeId(asString(rawEdge.get("source"))));
        sanitized.put("target", aliases.resolveNodeId(asString(rawEdge.get("target"))));
        sanitized.put("type", rawEdge.get("type"));

        var data = sanitizeMap(asMap(rawEdge.get("data")), TransmissionLevel.STRICT, aliases, null);
        if (!data.isEmpty()) {
            sanitized.put("data", data);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private Object sanitizeGraphNodeField(
            String key,
            Object value,
            TransmissionLevel transmissionLevel,
            AliasRegistry aliases
    ) {
        if (transmissionLevel != TransmissionLevel.STRICT) {
            return sanitizeValue(key, value, transmissionLevel, aliases, null);
        }

        return switch (key) {
            case "id" -> aliases.resolveNodeId(asString(value));
            case "parentNode" -> aliases.resolveNodeId(asString(value));
            case "data" -> sanitizeMap(asMap(value), transmissionLevel, aliases, aliases.resolveNodeAlias(asMap(value), null));
            default -> sanitizeValue(key, value, transmissionLevel, aliases, null);
        };
    }

    private Object sanitizeGraphEdgeField(
            String key,
            Object value,
            TransmissionLevel transmissionLevel,
            AliasRegistry aliases
    ) {
        if (transmissionLevel != TransmissionLevel.STRICT) {
            return sanitizeValue(key, value, transmissionLevel, aliases, null);
        }

        return switch (key) {
            case "id" -> "EDGE";
            case "source", "target" -> aliases.resolveNodeId(asString(value));
            case "data" -> sanitizeMap(asMap(value), transmissionLevel, aliases, null);
            default -> sanitizeValue(key, value, transmissionLevel, aliases, null);
        };
    }

    private Object redactScalar(
            String key,
            String value,
            TransmissionLevel transmissionLevel,
            AliasRegistry aliases,
            String currentNodeAlias
    ) {
        if (transmissionLevel == TransmissionLevel.STRICT && IDENTIFIER_KEYS.contains(normalize(key)) && currentNodeAlias != null) {
            return currentNodeAlias;
        }
        if ("source".equals(key) || "target".equals(key) || "parentNode".equals(key)) {
            return aliases.resolveNodeId(value);
        }
        if ("sourceArn".equals(key) || "targetArn".equals(key)) {
            return aliases.resolveNodeId(value);
        }
        return redactionEngine.redactValue(key, value);
    }

    private boolean shouldExcludeKey(String key, TransmissionLevel transmissionLevel) {
        var normalizedKey = normalize(key);
        if (ALWAYS_EXCLUDED_KEYS.contains(normalizedKey)) {
            return true;
        }
        if (transmissionLevel == TransmissionLevel.STRICT && STRICT_EXCLUDED_KEYS.contains(normalizedKey)) {
            return true;
        }
        return transmissionLevel == TransmissionLevel.NORMAL && NORMAL_EXCLUDED_KEYS.contains(normalizedKey);
    }

    private AliasRegistry buildAliasRegistry(Map<String, Object> payload) {
        var aliases = new AliasRegistry();
        if (payload == null || payload.isEmpty()) {
            return aliases;
        }

        var nodes = payload.get("nodes");
        if (!(nodes instanceof List<?> nodeList)) {
            return aliases;
        }

        for (var node : nodeList) {
            if (!(node instanceof Map<?, ?> nodeMap)) {
                continue;
            }

            var normalizedNode = asMap(nodeMap);
            var type = normalizeType(asString(normalizedNode.get("type")));
            var alias = aliases.nextAlias(type);
            aliases.register(asString(normalizedNode.get("id")), alias);
            aliases.register(asString(normalizedNode.get("parentNode")), aliases.resolveNodeId(asString(normalizedNode.get("parentNode"))));

            var data = asMap(normalizedNode.get("data"));
            aliases.register(asString(data.get("id")), alias);
            aliases.register(asString(data.get("resourceId")), alias);
            aliases.register(asString(data.get("arn")), alias);
            aliases.register(asString(data.get("name")), alias);
            aliases.registerFallback(normalizedNode, alias);
        }
        return aliases;
    }

    private boolean isGraphNodeRecord(Map<?, ?> value) {
        return value.containsKey("id") && value.containsKey("type") && value.containsKey("data");
    }

    private boolean isGraphEdgeRecord(Map<?, ?> value) {
        return value.containsKey("id") && value.containsKey("source") && value.containsKey("target");
    }

    private boolean isScalarValue(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean;
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> mapValue) || mapValue.isEmpty()) {
            return Map.of();
        }

        var result = new LinkedHashMap<String, Object>();
        for (var entry : mapValue.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "NODE";
        }
        return type.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private static final class AliasRegistry {

        private final Map<String, String> aliases = new HashMap<>();
        private final Map<String, Integer> countsByType = new HashMap<>();

        String nextAlias(String normalizedType) {
            var next = countsByType.merge(normalizedType, 1, Integer::sum);
            return normalizedType + "-" + next;
        }

        void register(String identifier, String alias) {
            if (identifier == null || identifier.isBlank() || alias == null || alias.isBlank()) {
                return;
            }
            aliases.put(identifier, alias);
        }

        void registerFallback(Map<String, Object> node, String alias) {
            register(asString(node.get("id")), alias);
            register(asString(node.get("parentNode")), resolveNodeId(asString(node.get("parentNode"))));
        }

        String resolveNodeId(String identifier) {
            if (identifier == null || identifier.isBlank()) {
                return null;
            }
            return aliases.computeIfAbsent(identifier, ignored -> nextAlias("NODE_REF"));
        }

        String resolveNodeAlias(Map<String, Object> nodeData, String fallback) {
            if (nodeData == null || nodeData.isEmpty()) {
                return fallback;
            }
            for (var key : List.of("id", "resourceId", "arn", "name")) {
                var alias = aliases.get(asString(nodeData.get(key)));
                if (alias != null) {
                    return alias;
                }
            }
            return fallback;
        }

        private String asString(Object value) {
            if (value == null) {
                return null;
            }
            return String.valueOf(value);
        }
    }
}
