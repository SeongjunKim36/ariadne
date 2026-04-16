package com.ariadne.drift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

@Entity
@Table(name = "terraform_drift_run")
public class TerraformDriftRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime generatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TerraformStateSourceKind sourceKind;

    @Column(nullable = false, length = 512)
    private String sourceName;

    @Column(nullable = false)
    private int totalItems;

    @Column(nullable = false)
    private int missingCount;

    @Column(nullable = false)
    private int modifiedCount;

    @Column(nullable = false)
    private int unmanagedCount;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String reportJson;

    protected TerraformDriftRun() {
    }

    public TerraformDriftRun(
            OffsetDateTime generatedAt,
            TerraformStateSourceKind sourceKind,
            String sourceName,
            int totalItems,
            int missingCount,
            int modifiedCount,
            int unmanagedCount,
            String reportJson
    ) {
        this.generatedAt = generatedAt;
        this.sourceKind = sourceKind;
        this.sourceName = sourceName;
        this.totalItems = totalItems;
        this.missingCount = missingCount;
        this.modifiedCount = modifiedCount;
        this.unmanagedCount = unmanagedCount;
        this.reportJson = reportJson;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public TerraformStateSourceKind getSourceKind() {
        return sourceKind;
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getMissingCount() {
        return missingCount;
    }

    public int getModifiedCount() {
        return modifiedCount;
    }

    public int getUnmanagedCount() {
        return unmanagedCount;
    }

    public String getReportJson() {
        return reportJson;
    }
}
