package com.ariadne.api.dto;

import com.ariadne.snapshot.TimelineEntry;

import java.time.OffsetDateTime;

public record TimelineEntryResponse(
        long snapshotId,
        OffsetDateTime capturedAt,
        Long baseSnapshotId,
        int totalChanges,
        int addedCount,
        int removedCount,
        int modifiedCount,
        String triggerSource,
        int nodeCount,
        int edgeCount
) {

    public static TimelineEntryResponse from(TimelineEntry entry) {
        return new TimelineEntryResponse(
                entry.snapshotId(),
                entry.capturedAt(),
                entry.baseSnapshotId(),
                entry.totalChanges(),
                entry.addedCount(),
                entry.removedCount(),
                entry.modifiedCount(),
                entry.triggerSource(),
                entry.nodeCount(),
                entry.edgeCount()
        );
    }
}
