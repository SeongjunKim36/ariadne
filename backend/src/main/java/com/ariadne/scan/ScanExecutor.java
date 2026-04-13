package com.ariadne.scan;

import com.ariadne.collector.CollectorOrchestrator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class ScanExecutor {

    private final CollectorOrchestrator collectorOrchestrator;
    private final ScanRunRepository scanRunRepository;

    public ScanExecutor(CollectorOrchestrator collectorOrchestrator, ScanRunRepository scanRunRepository) {
        this.collectorOrchestrator = collectorOrchestrator;
        this.scanRunRepository = scanRunRepository;
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
                    java.time.Duration.between(startedAt, completedAt).toMillis()
            );
        } catch (RuntimeException exception) {
            var completedAt = OffsetDateTime.now(ZoneOffset.UTC);
            scanRun.markFailed(
                    completedAt,
                    java.time.Duration.between(startedAt, completedAt).toMillis(),
                    exception.getMessage()
            );
        }

        scanRunRepository.save(scanRun);
    }
}
