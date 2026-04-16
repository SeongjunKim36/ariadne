package com.ariadne.snapshot;

import java.time.OffsetDateTime;

public record TimelineEntry(
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
}
