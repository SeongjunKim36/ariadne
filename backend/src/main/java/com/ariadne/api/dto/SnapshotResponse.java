package com.ariadne.api.dto;

import java.util.Map;

public record SnapshotResponse(
        SnapshotSummaryResponse snapshot,
        GraphResponse graph,
        Map<String, Object> metadata
) {
}
