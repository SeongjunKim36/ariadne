package com.ariadne.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimelineServiceTest {

    @Mock
    private SnapshotService snapshotService;

    @Test
    void buildsEntriesFromDiffsWithinTheRequestedRange() {
        var timelineService = new TimelineService(snapshotService);
        var from = OffsetDateTime.parse("2026-04-15T09:00:00Z");
        var to = OffsetDateTime.parse("2026-04-16T09:00:00Z");
        var base = snapshot(1L, "2026-04-15T09:00:00Z", SnapshotTrigger.SCHEDULED);
        var target = snapshot(2L, "2026-04-16T09:00:00Z", SnapshotTrigger.MANUAL_SCAN);
        var diff = new SnapshotDiff(1L, 2L, OffsetDateTime.parse("2026-04-16T09:01:00Z"), "[]", "[]", "[]", "[]", "[]", "[]", 4, 1, 1, 2);

        when(snapshotService.listSnapshots("custom", from, to, 0, 200)).thenReturn(List.of(target, base));
        when(snapshotService.listTimelineDiffs("custom", from, to)).thenReturn(List.of(diff));

        var entries = timelineService.listEntries("custom", from, to);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.snapshotId()).isEqualTo(2L);
            assertThat(entry.baseSnapshotId()).isEqualTo(1L);
            assertThat(entry.totalChanges()).isEqualTo(4);
            assertThat(entry.triggerSource()).isEqualTo("MANUAL_SCAN");
        });
        verify(snapshotService).listSnapshots("custom", from, to, 0, 200);
        verify(snapshotService).listTimelineDiffs("custom", from, to);
    }

    @Test
    void fallsBackToLatestSnapshotWhenNoDiffExists() {
        var timelineService = new TimelineService(snapshotService);
        var latest = snapshot(7L, "2026-04-16T09:00:00Z", SnapshotTrigger.EVENTBRIDGE);

        when(snapshotService.listSnapshots("24h", null, null, 0, 200)).thenReturn(List.of(latest));
        when(snapshotService.listTimelineDiffs("24h", null, null)).thenReturn(List.of());
        when(snapshotService.latestSnapshot()).thenReturn(Optional.of(latest));

        var entries = timelineService.listEntries("24h", null, null);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.snapshotId()).isEqualTo(7L);
            assertThat(entry.totalChanges()).isZero();
            assertThat(entry.triggerSource()).isEqualTo("EVENTBRIDGE");
        });
    }

    private Snapshot snapshot(long id, String capturedAt, SnapshotTrigger trigger) {
        var snapshot = new Snapshot(
                OffsetDateTime.parse(capturedAt),
                "123456789012",
                "ap-northeast-2",
                8,
                12,
                1500,
                trigger,
                null,
                "{}",
                "{}"
        );
        ReflectionTestUtils.setField(snapshot, "id", id);
        return snapshot;
    }
}
