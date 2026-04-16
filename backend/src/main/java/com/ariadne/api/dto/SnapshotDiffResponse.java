package com.ariadne.api.dto;

import com.ariadne.snapshot.Snapshot;
import com.ariadne.snapshot.SnapshotDiff;

import java.time.OffsetDateTime;
import java.util.List;

public record SnapshotDiffResponse(
        long id,
        OffsetDateTime diffedAt,
        SnapshotSummaryResponse baseSnapshot,
        SnapshotSummaryResponse targetSnapshot,
        int totalChanges,
        int addedCount,
        int removedCount,
        int modifiedCount,
        List<NodeDiffResponse> addedNodes,
        List<NodeDiffResponse> removedNodes,
        List<NodeDiffResponse> modifiedNodes,
        List<EdgeDiffResponse> addedEdges,
        List<EdgeDiffResponse> removedEdges,
        List<EdgeDiffResponse> modifiedEdges
) {

    public static SnapshotDiffResponse of(
            SnapshotDiff diff,
            Snapshot baseSnapshot,
            Snapshot targetSnapshot,
            List<NodeDiffResponse> addedNodes,
            List<NodeDiffResponse> removedNodes,
            List<NodeDiffResponse> modifiedNodes,
            List<EdgeDiffResponse> addedEdges,
            List<EdgeDiffResponse> removedEdges,
            List<EdgeDiffResponse> modifiedEdges
    ) {
        return new SnapshotDiffResponse(
                diff.getId(),
                diff.getDiffedAt(),
                SnapshotSummaryResponse.from(baseSnapshot),
                SnapshotSummaryResponse.from(targetSnapshot),
                diff.getTotalChanges(),
                diff.getAddedCount(),
                diff.getRemovedCount(),
                diff.getModifiedCount(),
                addedNodes,
                removedNodes,
                modifiedNodes,
                addedEdges,
                removedEdges,
                modifiedEdges
        );
    }
}
