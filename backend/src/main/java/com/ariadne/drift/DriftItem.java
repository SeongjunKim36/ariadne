package com.ariadne.drift;

import com.ariadne.snapshot.PropertyChange;

import java.util.Map;

public record DriftItem(
        DriftItemStatus status,
        String terraformAddress,
        String resourceType,
        String arn,
        String resourceId,
        String name,
        String summary,
        Map<String, Object> desiredData,
        Map<String, Object> actualData,
        Map<String, PropertyChange> propertyChanges
) {
}
