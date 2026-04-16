package com.ariadne.api.dto;

import com.ariadne.drift.DriftItem;

import java.util.Map;

public record DriftItemResponse(
        String status,
        String terraformAddress,
        String resourceType,
        String arn,
        String resourceId,
        String name,
        String summary,
        Map<String, Object> desiredData,
        Map<String, Object> actualData,
        Map<String, NodeDiffResponse.PropertyChangeResponse> propertyChanges
) {

    public static DriftItemResponse from(DriftItem item) {
        return new DriftItemResponse(
                item.status().name(),
                item.terraformAddress(),
                item.resourceType(),
                item.arn(),
                item.resourceId(),
                item.name(),
                item.summary(),
                item.desiredData(),
                item.actualData(),
                item.propertyChanges().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> NodeDiffResponse.PropertyChangeResponse.from(entry.getValue()),
                                (left, right) -> left,
                                java.util.LinkedHashMap::new
                        ))
        );
    }
}
