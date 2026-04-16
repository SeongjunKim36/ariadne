package com.ariadne.scan;

import com.ariadne.collector.CollectorOrchestrator;
import com.ariadne.snapshot.SnapshotService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class ScanExecutor {

    private final CollectorOrchestrator collectorOrchestrator;
    private final ScanRunRepository scanRunRepository;
    private final SnapshotService snapshotService;

    public ScanExecutor(
            CollectorOrchestrator collectorOrchestrator,
            ScanRunRepository scanRunRepository,
            SnapshotService snapshotService
    ) {
        this.collectorOrchestrator = collectorOrchestrator;
        this.scanRunRepository = scanRunRepository;
        this.snapshotService = snapshotService;
    }

    @Async
    public void executeScan(java.util.UUID scanId) {
        var scanRun = scanRunRepository.findById(scanId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scan id: " + scanId));
        var startedAt = scanRun.getStartedAt();

        try {
            var summary = collectorOrchestrator.collectAll();
            var completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            scanRun.markCompleted(
                    completedAt,
                    summary.totalResources(),
                    summary.totalRelationships(),
                    java.time.Duration.between(startedAt, completedAt).toMillis(),
                    summary.warnings().isEmpty() ? null : String.join("\n", summary.warnings())
            );
            scanRunRepository.save(scanRun);
            try {
                snapshotService.captureAfterScan(scanRun);
            } catch (RuntimeException snapshotException) {
                scanRun.markCompleted(
                        completedAt,
                        summary.totalResources(),
                        summary.totalRelationships(),
                        java.time.Duration.between(startedAt, completedAt).toMillis(),
                        mergeWarnings(summary.warnings(), "Snapshot capture failed: " + snapshotException.getMessage())
                );
                scanRunRepository.save(scanRun);
            }
            return;
        } catch (RuntimeException exception) {
            var completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            scanRun.markFailed(
                    completedAt,
                    java.time.Duration.between(startedAt, completedAt).toMillis(),
                    AwsFailureMessageResolver.toUserMessage(exception)
            );
        }

        scanRunRepository.save(scanRun);
    }

    private String mergeWarnings(java.util.List<String> warnings, String extraWarning) {
        var mergedWarnings = new java.util.ArrayList<String>();
        if (warnings != null) {
            mergedWarnings.addAll(warnings);
        }
        mergedWarnings.add(extraWarning);
        return String.join("\n", mergedWarnings);
    }
}
