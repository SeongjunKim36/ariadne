package com.ariadne.snapshot;

import java.util.Map;

public record NodeChange(
        String arn,
        String name,
        String resourceType,
        ChangeType changeType,
        Map<String, Object> beforeData,
        Map<String, Object> afterData,
        Map<String, PropertyChange> propertyChanges
) {

    public static NodeChange added(Map<String, Object> current) {
        return new NodeChange(
                stringValue(current.get("arn")),
                stringValue(current.get("name")),
                stringValue(current.get("resourceType")),
                ChangeType.ADDED,
                Map.of(),
                Map.copyOf(current),
                Map.of()
        );
    }

    public static NodeChange removed(Map<String, Object> previous) {
        return new NodeChange(
                stringValue(previous.get("arn")),
                stringValue(previous.get("name")),
                stringValue(previous.get("resourceType")),
                ChangeType.REMOVED,
                Map.copyOf(previous),
                Map.of(),
                Map.of()
        );
    }

    public static NodeChange modified(
            Map<String, Object> previous,
            Map<String, Object> current,
            Map<String, PropertyChange> propertyChanges
    ) {
        return new NodeChange(
                stringValue(current.getOrDefault("arn", previous.get("arn"))),
                stringValue(current.getOrDefault("name", previous.get("name"))),
                stringValue(current.getOrDefault("resourceType", previous.get("resourceType"))),
                ChangeType.MODIFIED,
                Map.copyOf(previous),
                Map.copyOf(current),
                Map.copyOf(propertyChanges)
        );
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
