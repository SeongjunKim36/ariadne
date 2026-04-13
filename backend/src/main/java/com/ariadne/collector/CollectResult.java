package com.ariadne.collector;

import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.relationship.GraphRelationship;

import java.util.ArrayList;
import java.util.List;

public record CollectResult(
        List<AwsResource> resources,
        List<GraphRelationship> relationships
) {

    public CollectResult {
        resources = List.copyOf(resources);
        relationships = List.copyOf(relationships);
    }

    public static CollectResult empty() {
        return new CollectResult(List.of(), List.of());
    }

    public CollectResult merge(CollectResult other) {
        var mergedResources = new ArrayList<>(resources);
        mergedResources.addAll(other.resources);

        var mergedRelationships = new ArrayList<>(relationships);
        mergedRelationships.addAll(other.relationships);

        return new CollectResult(mergedResources, mergedRelationships);
    }
}
