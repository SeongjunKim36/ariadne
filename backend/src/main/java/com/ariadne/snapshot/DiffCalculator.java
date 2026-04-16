package com.ariadne.snapshot;

import com.ariadne.api.dto.GraphResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class DiffCalculator {

    private static final Set<String> VOLATILE_NODE_KEYS = Set.of(
            "collectedAt",
            "stale",
            "staleSince"
    );

    private final ObjectMapper objectMapper;

    public DiffCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SnapshotDiff compute(Snapshot base, Snapshot target) {
        var baseGraph = parseGraph(base.getGraphJson());
        var targetGraph = parseGraph(target.getGraphJson());

        var baseNodes = indexNodes(baseGraph.nodes());
        var targetNodes = indexNodes(targetGraph.nodes());

        var addedNodes = new ArrayList<NodeChange>();
        var removedNodes = new ArrayList<NodeChange>();
        var modifiedNodes = new ArrayList<NodeChange>();

        var allNodeIds = new LinkedHashSet<String>();
        allNodeIds.addAll(baseNodes.keySet());
        allNodeIds.addAll(targetNodes.keySet());

        for (var nodeId : allNodeIds) {
            var previous = baseNodes.get(nodeId);
            var current = targetNodes.get(nodeId);
            if (previous == null && current != null) {
                addedNodes.add(NodeChange.added(normalizeNode(current)));
                continue;
            }
            if (previous != null && current == null) {
                removedNodes.add(NodeChange.removed(normalizeNode(previous)));
                continue;
            }

            var propertyChanges = compareProperties(normalizeNode(previous), normalizeNode(current));
            if (!propertyChanges.isEmpty()) {
                modifiedNodes.add(NodeChange.modified(normalizeNode(previous), normalizeNode(current), propertyChanges));
            }
        }

        var baseEdges = indexEdges(baseGraph.edges());
        var targetEdges = indexEdges(targetGraph.edges());

        var addedEdges = new ArrayList<EdgeChange>();
        var removedEdges = new ArrayList<EdgeChange>();
        var modifiedEdges = new ArrayList<EdgeChange>();

        var allEdgeIds = new LinkedHashSet<String>();
        allEdgeIds.addAll(baseEdges.keySet());
        allEdgeIds.addAll(targetEdges.keySet());

        for (var edgeId : allEdgeIds) {
            var previous = baseEdges.get(edgeId);
            var current = targetEdges.get(edgeId);
            if (previous == null && current != null) {
                addedEdges.add(EdgeChange.added(edgeId, current.source(), current.target(), current.type(), normalizeEdge(current)));
                continue;
            }
            if (previous != null && current == null) {
                removedEdges.add(EdgeChange.removed(edgeId, previous.source(), previous.target(), previous.type(), normalizeEdge(previous)));
                continue;
            }

            var propertyChanges = compareProperties(normalizeEdge(previous), normalizeEdge(current));
            if (!propertyChanges.isEmpty()) {
                modifiedEdges.add(EdgeChange.modified(
                        edgeId,
                        current.source(),
                        current.target(),
                        current.type(),
                        normalizeEdge(previous),
                        normalizeEdge(current),
                        propertyChanges
                ));
            }
        }

        int addedCount = addedNodes.size() + addedEdges.size();
        int removedCount = removedNodes.size() + removedEdges.size();
        int modifiedCount = modifiedNodes.size() + modifiedEdges.size();

        return new SnapshotDiff(
                base.getId(),
                target.getId(),
                OffsetDateTime.now(),
                toJson(addedNodes),
                toJson(removedNodes),
                toJson(modifiedNodes),
                toJson(addedEdges),
                toJson(removedEdges),
                toJson(modifiedEdges),
                addedCount + removedCount + modifiedCount,
                addedCount,
                removedCount,
                modifiedCount
        );
    }

    private GraphResponse parseGraph(String graphJson) {
        try {
            return objectMapper.readValue(graphJson, GraphResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse snapshot graph JSON.", exception);
        }
    }

    private Map<String, GraphResponse.GraphNode> indexNodes(List<GraphResponse.GraphNode> nodes) {
        var result = new LinkedHashMap<String, GraphResponse.GraphNode>();
        for (var node : nodes) {
            result.put(node.id(), node);
        }
        return result;
    }

    private Map<String, GraphResponse.GraphEdge> indexEdges(List<GraphResponse.GraphEdge> edges) {
        var result = new LinkedHashMap<String, GraphResponse.GraphEdge>();
        for (var edge : edges) {
            result.put(edgeKey(edge), edge);
        }
        return result;
    }

    private Map<String, PropertyChange> compareProperties(Map<String, Object> previous, Map<String, Object> current) {
        var changes = new LinkedHashMap<String, PropertyChange>();
        var keys = new LinkedHashSet<String>();
        keys.addAll(previous.keySet());
        keys.addAll(current.keySet());

        for (var key : keys) {
            var previousValue = previous.get(key);
            var currentValue = current.get(key);
            if (!jsonEquals(previousValue, currentValue)) {
                changes.put(key, new PropertyChange(previousValue, currentValue));
            }
        }

        return changes;
    }

    private boolean jsonEquals(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        return Objects.equals(toJsonNode(left), toJsonNode(right));
    }

    private JsonNode toJsonNode(Object value) {
        return objectMapper.valueToTree(value);
    }

    private Map<String, Object> normalizeNode(GraphResponse.GraphNode node) {
        var normalized = new LinkedHashMap<String, Object>();
        for (var entry : node.data().entrySet()) {
            if (!VOLATILE_NODE_KEYS.contains(entry.getKey())) {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        if (node.parentNode() != null) {
            normalized.put("parentNode", node.parentNode());
        }
        return normalized;
    }

    private Map<String, Object> normalizeEdge(GraphResponse.GraphEdge edge) {
        return new LinkedHashMap<>(edge.data());
    }

    private String edgeKey(GraphResponse.GraphEdge edge) {
        return edge.source() + "|" + edge.type() + "|" + edge.target();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
