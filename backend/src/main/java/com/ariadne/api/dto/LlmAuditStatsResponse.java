package com.ariadne.api.dto;

public record LlmAuditStatsResponse(
        int totalCalls,
        int successfulCalls,
        int failedCalls,
        double averageNodesSent,
        double averageEdgesSent
) {
}
