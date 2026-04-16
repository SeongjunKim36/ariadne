package com.ariadne.snapshot;

import java.util.Map;

public record EdgeChange(
        String edgeId,
        String sourceArn,
        String targetArn,
        String relationshipType,
        ChangeType changeType,
        Map<String, Object> beforeData,
        Map<String, Object> afterData,
        Map<String, PropertyChange> propertyChanges
) {

    public static EdgeChange added(String edgeId, String sourceArn, String targetArn, String relationshipType, Map<String, Object> current) {
        return new EdgeChange(edgeId, sourceArn, targetArn, relationshipType, ChangeType.ADDED, Map.of(), Map.copyOf(current), Map.of());
    }

    public static EdgeChange removed(String edgeId, String sourceArn, String targetArn, String relationshipType, Map<String, Object> previous) {
        return new EdgeChange(edgeId, sourceArn, targetArn, relationshipType, ChangeType.REMOVED, Map.copyOf(previous), Map.of(), Map.of());
    }

    public static EdgeChange modified(
            String edgeId,
            String sourceArn,
            String targetArn,
            String relationshipType,
            Map<String, Object> previous,
            Map<String, Object> current,
            Map<String, PropertyChange> propertyChanges
    ) {
        return new EdgeChange(
                edgeId,
                sourceArn,
                targetArn,
                relationshipType,
                ChangeType.MODIFIED,
                Map.copyOf(previous),
                Map.copyOf(current),
                Map.copyOf(propertyChanges)
        );
    }
}
