package com.ariadne.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record GraphResponse(
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        GraphMetadata metadata
) {

    public record GraphNode(
            String id,
            String type,
            Map<String, Object> data,
            String parentNode
    ) {
    }

    public record GraphEdge(
            String id,
            String source,
            String target,
            String type,
            Map<String, Object> data
    ) {
    }

    public record GraphMetadata(
            int totalNodes,
            int totalEdges,
            OffsetDateTime collectedAt,
            long scanDurationMs
    ) {
    }
}
