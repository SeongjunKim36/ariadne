package com.ariadne.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AuditExplanationResponse(
        OffsetDateTime generatedAt,
        String summary,
        List<String> priorities,
        List<String> actions
) {
}
