package com.ariadne.api.dto;

import java.time.OffsetDateTime;

public record ArchitectureSummaryResponse(
        String summary,
        String language,
        OffsetDateTime generatedAt
) {
}
