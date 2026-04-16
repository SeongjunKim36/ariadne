package com.ariadne.api.dto;

import com.ariadne.snapshot.NodeChange;
import com.ariadne.snapshot.PropertyChange;

import java.util.Map;

public record NodeDiffResponse(
        String arn,
        String name,
        String resourceType,
        String changeType,
        Map<String, Object> beforeData,
        Map<String, Object> afterData,
        Map<String, PropertyChangeResponse> propertyChanges
) {

    public static NodeDiffResponse from(NodeChange change) {
        return new NodeDiffResponse(
                change.arn(),
                change.name(),
                change.resourceType(),
                change.changeType().name(),
                change.beforeData(),
                change.afterData(),
                change.propertyChanges().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> PropertyChangeResponse.from(entry.getValue()),
                                (left, right) -> left,
                                java.util.LinkedHashMap::new
                        ))
        );
    }

    public record PropertyChangeResponse(
            Object beforeValue,
            Object afterValue
    ) {
        public static PropertyChangeResponse from(PropertyChange change) {
            return new PropertyChangeResponse(change.beforeValue(), change.afterValue());
        }
    }
}
