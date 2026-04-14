package com.ariadne.api.dto;

import java.util.List;
import java.util.Map;

public record ResourceDetailResponse(
        ResourceNode resource,
        List<Connection> connections
) {

    public record ResourceNode(
            String id,
            String type,
            Map<String, Object> data,
            String parentNode
    ) {
    }

    public record Connection(
            String direction,
            String relationshipType,
            Map<String, Object> relationshipData,
            ResourceNode node
    ) {
    }
}
