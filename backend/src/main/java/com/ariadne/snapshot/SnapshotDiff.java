package com.ariadne.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

@Entity
@Table(name = "snapshot_diff")
public class SnapshotDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long baseSnapshotId;

    @Column(nullable = false)
    private Long targetSnapshotId;

    @Column(nullable = false)
    private OffsetDateTime diffedAt;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String addedNodesJson;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String removedNodesJson;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String modifiedNodesJson;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String addedEdgesJson;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String removedEdgesJson;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String modifiedEdgesJson;

    @Column(nullable = false)
    private int totalChanges;

    @Column(nullable = false)
    private int addedCount;

    @Column(nullable = false)
    private int removedCount;

    @Column(nullable = false)
    private int modifiedCount;

    protected SnapshotDiff() {
    }

    public SnapshotDiff(
            Long baseSnapshotId,
            Long targetSnapshotId,
            OffsetDateTime diffedAt,
            String addedNodesJson,
            String removedNodesJson,
            String modifiedNodesJson,
            String addedEdgesJson,
            String removedEdgesJson,
            String modifiedEdgesJson,
            int totalChanges,
            int addedCount,
            int removedCount,
            int modifiedCount
    ) {
        this.baseSnapshotId = baseSnapshotId;
        this.targetSnapshotId = targetSnapshotId;
        this.diffedAt = diffedAt;
        this.addedNodesJson = addedNodesJson;
        this.removedNodesJson = removedNodesJson;
        this.modifiedNodesJson = modifiedNodesJson;
        this.addedEdgesJson = addedEdgesJson;
        this.removedEdgesJson = removedEdgesJson;
        this.modifiedEdgesJson = modifiedEdgesJson;
        this.totalChanges = totalChanges;
        this.addedCount = addedCount;
        this.removedCount = removedCount;
        this.modifiedCount = modifiedCount;
    }

    public Long getId() {
        return id;
    }

    public Long getBaseSnapshotId() {
        return baseSnapshotId;
    }

    public Long getTargetSnapshotId() {
        return targetSnapshotId;
    }

    public OffsetDateTime getDiffedAt() {
        return diffedAt;
    }

    public String getAddedNodesJson() {
        return addedNodesJson;
    }

    public String getRemovedNodesJson() {
        return removedNodesJson;
    }

    public String getModifiedNodesJson() {
        return modifiedNodesJson;
    }

    public String getAddedEdgesJson() {
        return addedEdgesJson;
    }

    public String getRemovedEdgesJson() {
        return removedEdgesJson;
    }

    public String getModifiedEdgesJson() {
        return modifiedEdgesJson;
    }

    public int getTotalChanges() {
        return totalChanges;
    }

    public int getAddedCount() {
        return addedCount;
    }

    public int getRemovedCount() {
        return removedCount;
    }

    public int getModifiedCount() {
        return modifiedCount;
    }
}
