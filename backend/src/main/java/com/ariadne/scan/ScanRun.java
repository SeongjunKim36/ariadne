package com.ariadne.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scan_run")
public class ScanRun {

    @Id
    private UUID scanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScanStatus status;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime completedAt;

    @Column(nullable = false)
    private int totalNodes;

    @Column(nullable = false)
    private int totalEdges;

    @Column(nullable = false)
    private long durationMs;

    @Column(length = 2048)
    private String errorMessage;

    @Column(length = 2048)
    private String warningMessage;

    protected ScanRun() {
    }

    public ScanRun(UUID scanId, ScanStatus status, OffsetDateTime startedAt) {
        this.scanId = scanId;
        this.status = status;
        this.startedAt = startedAt;
    }

    public UUID getScanId() {
        return scanId;
    }

    public ScanStatus getStatus() {
        return status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public int getTotalNodes() {
        return totalNodes;
    }

    public int getTotalEdges() {
        return totalEdges;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getWarningMessage() {
        return warningMessage;
    }

    public void markCompleted(
            OffsetDateTime completedAt,
            int totalNodes,
            int totalEdges,
            long durationMs,
            String warningMessage
    ) {
        this.status = ScanStatus.COMPLETED;
        this.completedAt = completedAt;
        this.totalNodes = totalNodes;
        this.totalEdges = totalEdges;
        this.durationMs = durationMs;
        this.errorMessage = null;
        this.warningMessage = warningMessage;
    }

    public void markFailed(OffsetDateTime completedAt, long durationMs, String errorMessage) {
        this.status = ScanStatus.FAILED;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
        this.warningMessage = null;
    }
}
