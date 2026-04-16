package com.ariadne.api.dto;

public record ResourceSummaryResponse(
        String arn,
        String name,
        String resourceType,
        String environment
) {
}
