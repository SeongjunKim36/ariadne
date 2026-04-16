package com.ariadne.api.dto;

import com.ariadne.drift.TerraformDriftRun;

import java.time.OffsetDateTime;
import java.util.List;

public record DriftReportResponse(
        long id,
        OffsetDateTime generatedAt,
        String sourceKind,
        String sourceName,
        int totalItems,
        int missingCount,
        int modifiedCount,
        int unmanagedCount,
        List<DriftItemResponse> items
) {

    public static DriftReportResponse of(TerraformDriftRun run, List<DriftItemResponse> items) {
        return new DriftReportResponse(
                run.getId(),
                run.getGeneratedAt(),
                run.getSourceKind().name(),
                run.getSourceName(),
                run.getTotalItems(),
                run.getMissingCount(),
                run.getModifiedCount(),
                run.getUnmanagedCount(),
                items
        );
    }
}
