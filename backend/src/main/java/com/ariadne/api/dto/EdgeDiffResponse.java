package com.ariadne.api.dto;

import com.ariadne.snapshot.EdgeChange;
import com.ariadne.snapshot.PropertyChange;

import java.util.Map;

public record EdgeDiffResponse(
        String edgeId,
        String sourceArn,
        String targetArn,
        String relationshipType,
        String changeType,
        Map<String, Object> beforeData,
        Map<String, Object> afterData,
        Map<String, NodeDiffResponse.PropertyChangeResponse> propertyChanges
) {

    public static EdgeDiffResponse from(EdgeChange change) {
        return new EdgeDiffResponse(
                change.edgeId(),
                change.sourceArn(),
                change.targetArn(),
                change.relationshipType(),
                change.changeType().name(),
                change.beforeData(),
                change.afterData(),
                change.propertyChanges().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> NodeDiffResponse.PropertyChangeResponse.from(entry.getValue()),
                                (left, right) -> left,
                                java.util.LinkedHashMap::new
                        ))
        );
    }
}
