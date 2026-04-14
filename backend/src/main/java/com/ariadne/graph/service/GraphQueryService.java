package com.ariadne.graph.service;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.scan.ScanRunRepository;
import com.ariadne.scan.ScanStatus;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GraphQueryService {

    private final Neo4jClient neo4jClient;
    private final ScanRunRepository scanRunRepository;

    public GraphQueryService(Neo4jClient neo4jClient, ScanRunRepository scanRunRepository) {
        this.neo4jClient = neo4jClient;
        this.scanRunRepository = scanRunRepository;
    }

    public GraphResponse fetchGraph(String environment, Set<String> resourceTypes, String vpcId) {
        var nodeRows = neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE coalesce(n.stale, false) = false
                        RETURN properties(n) AS properties, labels(n) AS labels
                        ORDER BY n.name, n.resourceId
                        """)
                .fetch()
                .all();

        var edgeRows = neo4jClient.query("""
                        MATCH (source:AwsResource)-[r]->(target:AwsResource)
                        WHERE coalesce(source.stale, false) = false
                          AND coalesce(target.stale, false) = false
                        RETURN source.arn AS sourceArn,
                               target.arn AS targetArn,
                               type(r) AS relationshipType,
                               properties(r) AS properties
                        """)
                .fetch()
                .all();

        var nodesByArn = new LinkedHashMap<String, Map<String, Object>>();
        var parentByChild = new HashMap<String, String>();
        var resourceTypeByArn = new HashMap<String, String>();

        for (var edgeRow : edgeRows) {
            var relationshipType = (String) edgeRow.get("relationshipType");
            if (GraphViewSupport.isParentRelationship(relationshipType)) {
                parentByChild.put((String) edgeRow.get("sourceArn"), (String) edgeRow.get("targetArn"));
            }
        }

        for (var nodeRow : nodeRows) {
            @SuppressWarnings("unchecked")
            var properties = new LinkedHashMap<>((Map<String, Object>) nodeRow.get("properties"));
            var arn = (String) properties.get("arn");
            var resourceType = ((String) properties.getOrDefault("resourceType", "")).toUpperCase(java.util.Locale.ROOT);
            resourceTypeByArn.put(arn, resourceType);
            if (matchesFilters(properties, resourceTypes, environment)
                    && belongsToVpc(properties, parentByChild, nodesByArn, nodeRows, vpcId)) {
                nodesByArn.put(arn, properties);
            }
        }

        var nodes = new ArrayList<GraphResponse.GraphNode>();
        for (var entry : nodesByArn.entrySet()) {
            var arn = entry.getKey();
            var properties = entry.getValue();
            var parentArn = parentByChild.get(arn);
            var parentResourceType = resourceTypeByArn.get(parentArn);
            nodes.add(new GraphResponse.GraphNode(
                    arn,
                    GraphViewSupport.toFrontendType((String) properties.get("resourceType")),
                    properties,
                    GraphViewSupport.isGroupParent(parentResourceType) ? parentArn : null
            ));
        }

        var edges = new ArrayList<GraphResponse.GraphEdge>();
        for (var edgeRow : edgeRows) {
            var sourceArn = (String) edgeRow.get("sourceArn");
            var targetArn = (String) edgeRow.get("targetArn");
            if (nodesByArn.containsKey(sourceArn) && nodesByArn.containsKey(targetArn)) {
                @SuppressWarnings("unchecked")
                var properties = (Map<String, Object>) edgeRow.get("properties");
                var relationshipType = (String) edgeRow.get("relationshipType");
                edges.add(new GraphResponse.GraphEdge(
                        sourceArn + "-" + relationshipType + "-" + targetArn,
                        sourceArn,
                        targetArn,
                        relationshipType,
                        properties == null ? Map.of() : properties
                ));
            }
        }

        var latestCompleted = scanRunRepository.findTopByStatusOrderByCompletedAtDesc(ScanStatus.COMPLETED).orElse(null);
        return new GraphResponse(
                nodes,
                edges,
                new GraphResponse.GraphMetadata(
                        nodes.size(),
                        edges.size(),
                        latestCompleted == null ? null : latestCompleted.getCompletedAt(),
                        latestCompleted == null ? 0 : latestCompleted.getDurationMs()
                )
        );
    }

    private boolean matchesFilters(Map<String, Object> properties, Set<String> resourceTypes, String environment) {
        var matchesEnvironment = environment == null
                || environment.isBlank()
                || environment.equalsIgnoreCase((String) properties.getOrDefault("environment", "unknown"));
        var matchesType = resourceTypes == null
                || resourceTypes.isEmpty()
                || resourceTypes.contains(((String) properties.getOrDefault("resourceType", "")).toUpperCase(java.util.Locale.ROOT));
        return matchesEnvironment && matchesType;
    }

    private boolean belongsToVpc(
            Map<String, Object> properties,
            Map<String, String> parentByChild,
            Map<String, Map<String, Object>> nodesByArn,
            java.util.Collection<Map<String, Object>> nodeRows,
            String vpcId
    ) {
        if (vpcId == null || vpcId.isBlank()) {
            return true;
        }

        var arn = (String) properties.get("arn");
        var queue = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        queue.add(arn);

        while (!queue.isEmpty()) {
            var currentArn = queue.removeFirst();
            if (!visited.add(currentArn)) {
                continue;
            }

            var currentProperties = currentArn.equals(arn)
                    ? properties
                    : findNodeProperties(currentArn, nodesByArn, nodeRows);
            if (currentProperties == null) {
                continue;
            }

            if ("VPC".equals(currentProperties.get("resourceType"))
                    && vpcId.equals(currentProperties.get("resourceId"))) {
                return true;
            }

            var parentArn = parentByChild.get(currentArn);
            if (parentArn != null) {
                queue.add(parentArn);
            }
        }

        return false;
    }

    private Map<String, Object> findNodeProperties(
            String arn,
            Map<String, Map<String, Object>> nodesByArn,
            java.util.Collection<Map<String, Object>> nodeRows
    ) {
        var cached = nodesByArn.get(arn);
        if (cached != null) {
            return cached;
        }

        for (var nodeRow : nodeRows) {
            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>) nodeRow.get("properties");
            if (arn.equals(properties.get("arn"))) {
                return properties;
            }
        }
        return null;
    }
}
