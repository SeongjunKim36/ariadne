package com.ariadne.scan;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ScanService {

    private final ScanRunRepository scanRunRepository;
    private final ScanExecutor scanExecutor;

    public ScanService(ScanRunRepository scanRunRepository, ScanExecutor scanExecutor) {
        this.scanRunRepository = scanRunRepository;
        this.scanExecutor = scanExecutor;
    }

    public ScanRun triggerScan() {
        var scanRun = new ScanRun(UUID.randomUUID(), ScanStatus.RUNNING, OffsetDateTime.now(ZoneOffset.UTC));
        scanRunRepository.save(scanRun);
        scanExecutor.executeScan(scanRun.getScanId());
        return scanRun;
    }

    public ScanRun getScan(java.util.UUID scanId) {
        return scanRunRepository.findById(scanId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown scan id: " + scanId));
    }

    public java.util.Optional<ScanRun> findLatestScan() {
        return scanRunRepository.findTopByOrderByStartedAtDesc();
    }
}
