package com.ariadne.api.dto;

import com.ariadne.scan.ScanRun;
import com.ariadne.scan.ScanStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ScanStatusResponse(
        UUID scanId,
        ScanStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int totalNodes,
        int totalEdges,
        long durationMs,
        String errorMessage
) {

    public static ScanStatusResponse from(ScanRun scanRun) {
        return new ScanStatusResponse(
                scanRun.getScanId(),
                scanRun.getStatus(),
                scanRun.getStartedAt(),
                scanRun.getCompletedAt(),
                scanRun.getTotalNodes(),
                scanRun.getTotalEdges(),
                scanRun.getDurationMs(),
                scanRun.getErrorMessage()
        );
    }
}
