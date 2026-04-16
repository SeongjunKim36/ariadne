package com.ariadne.semantic;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.graph.service.GraphQueryService;
import com.ariadne.llm.GraphData;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SemanticGraphService {

    private final GraphQueryService graphQueryService;

    public SemanticGraphService(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    public GraphResponse fetchFullGraph() {
        return graphQueryService.fetchGraph(null, Set.of(), null, null);
    }

    public GraphData asGraphData(String scope) {
        return toGraphData(scope, fetchFullGraph());
    }

    public GraphData toGraphData(String scope, GraphResponse response) {
        var nodes = new ArrayList<Map<String, Object>>();
        for (var node : response.nodes()) {
            var serializedNode = new LinkedHashMap<String, Object>();
            serializedNode.put("id", node.id());
            serializedNode.put("type", node.type());
            serializedNode.put("data", node.data());
            if (node.parentNode() != null) {
                serializedNode.put("parentNode", node.parentNode());
            }
            nodes.add(serializedNode);
        }

        var edges = new ArrayList<Map<String, Object>>();
        for (var edge : response.edges()) {
            var serializedEdge = new LinkedHashMap<String, Object>();
            serializedEdge.put("id", edge.id());
            serializedEdge.put("source", edge.source());
            serializedEdge.put("target", edge.target());
            serializedEdge.put("type", edge.type());
            serializedEdge.put("data", edge.data());
            edges.add(serializedEdge);
        }

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("totalNodes", response.metadata().totalNodes());
        metadata.put("totalEdges", response.metadata().totalEdges());
        metadata.put("collectedAt", response.metadata().collectedAt());
        metadata.put("scanDurationMs", response.metadata().scanDurationMs());

        var payload = new LinkedHashMap<String, Object>();
        payload.put("nodes", nodes);
        payload.put("edges", edges);
        payload.put("metadata", metadata);
        return new GraphData(scope, payload);
    }

    public GraphResponse buildSubgraph(Set<String> seedArns, int depth) {
        if (seedArns == null || seedArns.isEmpty()) {
            return new GraphResponse(List.of(), List.of(), fetchFullGraph().metadata());
        }

        var fullGraph = fetchFullGraph();
        var nodesById = new LinkedHashMap<String, GraphResponse.GraphNode>();
        for (var node : fullGraph.nodes()) {
            nodesById.put(node.id(), node);
        }

        var neighbors = new LinkedHashMap<String, Set<String>>();
        for (var edge : fullGraph.edges()) {
            neighbors.computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>()).add(edge.target());
            neighbors.computeIfAbsent(edge.target(), ignored -> new LinkedHashSet<>()).add(edge.source());
        }

        var visibleIds = new LinkedHashSet<String>();
        var queue = new ArrayDeque<NodeDepth>();
        for (var seedArn : seedArns) {
            queue.add(new NodeDepth(seedArn, 0));
        }

        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (!visibleIds.add(current.arn())) {
                continue;
            }

            var node = nodesById.get(current.arn());
            if (node == null) {
                continue;
            }

            if (node.parentNode() != null) {
                visibleIds.add(node.parentNode());
            }

            if (current.depth() >= depth) {
                continue;
            }

            for (var neighborArn : neighbors.getOrDefault(current.arn(), Set.of())) {
                queue.add(new NodeDepth(neighborArn, current.depth() + 1));
            }
        }

        var nodes = fullGraph.nodes().stream()
                .filter(node -> visibleIds.contains(node.id()))
                .toList();
        var edges = fullGraph.edges().stream()
                .filter(edge -> visibleIds.contains(edge.source()) && visibleIds.contains(edge.target()))
                .toList();
        return new GraphResponse(
                nodes,
                edges,
                new GraphResponse.GraphMetadata(nodes.size(), edges.size(), fullGraph.metadata().collectedAt(), fullGraph.metadata().scanDurationMs())
        );
    }

    private record NodeDepth(String arn, int depth) {
    }
}
