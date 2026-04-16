package com.ariadne.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "snapshot")
public class Snapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime capturedAt;

    @Column(nullable = false, length = 32)
    private String accountId;

    @Column(nullable = false, length = 64)
    private String region;

    @Column(nullable = false)
    private int nodeCount;

    @Column(nullable = false)
    private int edgeCount;

    @Column(nullable = false)
    private long scanDurationMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SnapshotTrigger triggerSource;

    private UUID scanId;

    @Column(columnDefinition = "jsonb", nullable = false)
    private String graphJson;

    @Column(columnDefinition = "jsonb")
    private String metadataJson;

    protected Snapshot() {
    }

    public Snapshot(
            OffsetDateTime capturedAt,
            String accountId,
            String region,
            int nodeCount,
            int edgeCount,
            long scanDurationMs,
            SnapshotTrigger triggerSource,
            UUID scanId,
            String graphJson,
            String metadataJson
    ) {
        this.capturedAt = capturedAt;
        this.accountId = accountId;
        this.region = region;
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.scanDurationMs = scanDurationMs;
        this.triggerSource = triggerSource;
        this.scanId = scanId;
        this.graphJson = graphJson;
        this.metadataJson = metadataJson;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getRegion() {
        return region;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public long getScanDurationMs() {
        return scanDurationMs;
    }

    public SnapshotTrigger getTriggerSource() {
        return triggerSource;
    }

    public UUID getScanId() {
        return scanId;
    }

    public String getGraphJson() {
        return graphJson;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public long estimatedStorageBytes() {
        long graphBytes = graphJson == null ? 0 : graphJson.length();
        long metadataBytes = metadataJson == null ? 0 : metadataJson.length();
        return graphBytes + metadataBytes;
    }
}
