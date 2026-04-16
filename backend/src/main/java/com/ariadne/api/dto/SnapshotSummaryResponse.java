package com.ariadne.api.dto;

import com.ariadne.snapshot.Snapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SnapshotSummaryResponse(
        long id,
        OffsetDateTime capturedAt,
        String accountId,
        String region,
        int nodeCount,
        int edgeCount,
        long scanDurationMs,
        String triggerSource,
        UUID scanId
) {

    public static SnapshotSummaryResponse from(Snapshot snapshot) {
        return new SnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getCapturedAt(),
                snapshot.getAccountId(),
                snapshot.getRegion(),
                snapshot.getNodeCount(),
                snapshot.getEdgeCount(),
                snapshot.getScanDurationMs(),
                snapshot.getTriggerSource().name(),
                snapshot.getScanId()
        );
    }
}
