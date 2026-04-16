package com.ariadne.api.dto;

import com.ariadne.llm.LlmAuditLog;

import java.time.OffsetDateTime;

public record LlmAuditLogResponse(
        Long id,
        OffsetDateTime timestamp,
        String transmissionLevel,
        String queryText,
        String dataScope,
        int nodeCount,
        int edgeCount,
        String status,
        String failureMessage
) {

    public static LlmAuditLogResponse from(LlmAuditLog log) {
        return new LlmAuditLogResponse(
                log.getId(),
                log.getTimestamp(),
                log.getTransmissionLevel(),
                log.getQueryText(),
                log.getDataScope(),
                log.getNodeCount(),
                log.getEdgeCount(),
                log.getStatus().name(),
                log.getFailureMessage()
        );
    }
}
