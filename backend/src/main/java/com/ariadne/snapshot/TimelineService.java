package com.ariadne.snapshot;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TimelineService {

    private final SnapshotService snapshotService;

    public TimelineService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public List<TimelineEntry> listEntries(String period) {
        return listEntries(period, null, null);
    }

    public List<TimelineEntry> listEntries(String period, OffsetDateTime from, OffsetDateTime to) {
        var snapshots = snapshotService.listSnapshots(period, from, to, 0, 200).stream()
                .collect(Collectors.toMap(Snapshot::getId, Function.identity()));
        var diffs = snapshotService.listTimelineDiffs(period, from, to);
        var entries = new ArrayList<TimelineEntry>();

        for (var diff : diffs) {
            var targetSnapshot = snapshots.get(diff.getTargetSnapshotId());
            if (targetSnapshot == null) {
                continue;
            }
            entries.add(new TimelineEntry(
                    targetSnapshot.getId(),
                    targetSnapshot.getCapturedAt(),
                    diff.getBaseSnapshotId(),
                    diff.getTotalChanges(),
                    diff.getAddedCount(),
                    diff.getRemovedCount(),
                    diff.getModifiedCount(),
                    targetSnapshot.getTriggerSource().name(),
                    targetSnapshot.getNodeCount(),
                    targetSnapshot.getEdgeCount()
            ));
        }

        if (entries.isEmpty()) {
            snapshotService.latestSnapshot().ifPresent(snapshot -> entries.add(new TimelineEntry(
                    snapshot.getId(),
                    snapshot.getCapturedAt(),
                    null,
                    0,
                    0,
                    0,
                    0,
                    snapshot.getTriggerSource().name(),
                    snapshot.getNodeCount(),
                    snapshot.getEdgeCount()
            )));
        }

        return entries;
    }
}
