package com.ariadne.graph.relationship;

import java.util.LinkedHashMap;
import java.util.Map;

public record GraphRelationship(
        String sourceArn,
        String targetArn,
        String type,
        Map<String, Object> properties
) {

    public GraphRelationship {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static GraphRelationship belongsTo(String sourceArn, String targetArn) {
        return new GraphRelationship(sourceArn, targetArn, RelationshipTypes.BELONGS_TO, Map.of());
    }

    public Map<String, Object> toRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("sourceArn", sourceArn);
        row.put("targetArn", targetArn);
        row.put("properties", properties);
        return row;
    }
}
