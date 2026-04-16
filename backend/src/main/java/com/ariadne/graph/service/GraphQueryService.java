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
import java.util.Locale;
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

    public GraphResponse fetchGraph(String environment, Set<String> resourceTypes, String vpcId, String tier) {
        var normalizedEnvironment = normalizeFilterValue(environment);
        var normalizedTier = normalizeFilterValue(tier);
        var normalizedResourceTypes = normalizeResourceTypes(resourceTypes);

        if (vpcId == null || vpcId.isBlank()) {
            return fetchGraphWithoutVpcFilter(normalizedEnvironment, normalizedResourceTypes, normalizedTier);
        }
        return fetchGraphWithVpcFilter(normalizedEnvironment, normalizedResourceTypes, vpcId, normalizedTier);
    }

    private GraphResponse fetchGraphWithoutVpcFilter(String environment, Set<String> resourceTypes, String tier) {
        var baseNodesByArn = fetchFilteredNodes(environment, resourceTypes, tier);
        if (baseNodesByArn.isEmpty()) {
            return emptyGraphResponse();
        }

        var parentByChild = fetchParentRelationships();
        var visibleArns = expandParentHierarchy(baseNodesByArn.keySet(), parentByChild);
        var nodesByArn = fetchNodesByArn(visibleArns);
        var edgeRows = fetchEdgesWithin(visibleArns);
        return toGraphResponse(nodesByArn, parentByChild, edgeRows);
    }

    private GraphResponse fetchGraphWithVpcFilter(String environment, Set<String> resourceTypes, String vpcId, String tier) {
        var nodeRows = neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE n.stale = false
                        RETURN properties(n) AS properties
                        ORDER BY n.name, n.resourceId
                        """)
                .fetch()
                .all();

        var edgeRows = neo4jClient.query("""
                        MATCH (source:AwsResource)-[r]->(target:AwsResource)
                        WHERE source.stale = false
                          AND target.stale = false
                        RETURN source.arn AS sourceArn,
                               target.arn AS targetArn,
                               type(r) AS relationshipType,
                               properties(r) AS properties
                        """)
                .fetch()
                .all();

        var nodesByArn = new LinkedHashMap<String, Map<String, Object>>();
        var propertiesByArn = new HashMap<String, Map<String, Object>>();
        var parentByChild = new HashMap<String, String>();
        var neighborsByArn = new HashMap<String, Set<String>>();
        var resourceTypeByArn = new HashMap<String, String>();

        for (var edgeRow : edgeRows) {
            var sourceArn = (String) edgeRow.get("sourceArn");
            var targetArn = (String) edgeRow.get("targetArn");
            var relationshipType = (String) edgeRow.get("relationshipType");
            if (GraphViewSupport.isParentRelationship(relationshipType)) {
                parentByChild.put(sourceArn, targetArn);
            }
            neighborsByArn.computeIfAbsent(sourceArn, ignored -> new HashSet<>()).add(targetArn);
            neighborsByArn.computeIfAbsent(targetArn, ignored -> new HashSet<>()).add(sourceArn);
        }

        for (var nodeRow : nodeRows) {
            var properties = asProperties(nodeRow);
            var arn = (String) properties.get("arn");
            propertiesByArn.put(arn, properties);
            var resourceType = normalizedResourceType(properties.get("resourceType"));
            resourceTypeByArn.put(arn, resourceType);
        }

        for (var entry : propertiesByArn.entrySet()) {
            var arn = entry.getKey();
            var properties = entry.getValue();
            if (matchesFilters(properties, resourceTypes, environment, tier)
                    && belongsToVpc(properties, parentByChild, propertiesByArn, neighborsByArn, vpcId)) {
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
                edges.add(toGraphEdge(edgeRow));
            }
        }

        return new GraphResponse(nodes, edges, latestMetadata(nodes.size(), edges.size()));
    }

    private LinkedHashMap<String, Map<String, Object>> fetchFilteredNodes(
            String environment,
            Set<String> resourceTypes,
            String tier
    ) {
        var rows = neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE n.stale = false
                          AND NOT n.resourceType IN $detailOnlyResourceTypes
                          AND ($environment = '' OR n.environment = $environment OR n.resourceType = 'CIDR_SOURCE')
                          AND ($tier = '' OR n.tier = $tier)
                          AND ($resourceTypesEmpty OR n.resourceType IN $resourceTypes)
                        RETURN properties(n) AS properties
                        ORDER BY n.name, n.resourceId
                        """)
                .bind(new ArrayList<>(GraphViewSupport.detailOnlyResourceTypes()))
                .to("detailOnlyResourceTypes")
                .bind(environment)
                .to("environment")
                .bind(tier)
                .to("tier")
                .bind(resourceTypes.isEmpty())
                .to("resourceTypesEmpty")
                .bind(new ArrayList<>(resourceTypes))
                .to("resourceTypes")
                .fetch()
                .all();

        var nodesByArn = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows) {
            var properties = asProperties(row);
            nodesByArn.put((String) properties.get("arn"), properties);
        }
        return nodesByArn;
    }

    private Map<String, String> fetchParentRelationships() {
        var rows = neo4jClient.query("""
                        MATCH (source:AwsResource)-[r]->(target:AwsResource)
                        WHERE source.stale = false
                          AND target.stale = false
                          AND type(r) IN $parentRelationshipTypes
                        RETURN source.arn AS sourceArn,
                               target.arn AS targetArn
                        """)
                .bind(new ArrayList<>(GraphViewSupport.parentRelationshipTypes()))
                .to("parentRelationshipTypes")
                .fetch()
                .all();

        var parentByChild = new HashMap<String, String>();
        for (var row : rows) {
            parentByChild.put((String) row.get("sourceArn"), (String) row.get("targetArn"));
        }
        return parentByChild;
    }

    private Set<String> expandParentHierarchy(Set<String> initialArns, Map<String, String> parentByChild) {
        var visibleArns = new HashSet<>(initialArns);
        var queue = new ArrayDeque<>(initialArns);

        while (!queue.isEmpty()) {
            var childArn = queue.removeFirst();
            var parentArn = parentByChild.get(childArn);
            if (parentArn != null && visibleArns.add(parentArn)) {
                queue.addLast(parentArn);
            }
        }

        return visibleArns;
    }

    private LinkedHashMap<String, Map<String, Object>> fetchNodesByArn(Set<String> arns) {
        if (arns.isEmpty()) {
            return new LinkedHashMap<>();
        }

        var rows = neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE n.arn IN $arns
                        RETURN properties(n) AS properties
                        ORDER BY n.name, n.resourceId
                        """)
                .bind(new ArrayList<>(arns))
                .to("arns")
                .fetch()
                .all();

        var nodesByArn = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : rows) {
            var properties = asProperties(row);
            nodesByArn.put((String) properties.get("arn"), properties);
        }
        return nodesByArn;
    }

    private List<Map<String, Object>> fetchEdgesWithin(Set<String> arns) {
        if (arns.isEmpty()) {
            return List.of();
        }

        return List.copyOf(neo4jClient.query("""
                        MATCH (source:AwsResource)-[r]->(target:AwsResource)
                        WHERE source.stale = false
                          AND target.stale = false
                          AND source.arn IN $arns
                          AND target.arn IN $arns
                        RETURN source.arn AS sourceArn,
                               target.arn AS targetArn,
                               type(r) AS relationshipType,
                               properties(r) AS properties
                        """)
                .bind(new ArrayList<>(arns))
                .to("arns")
                .fetch()
                .all());
    }

    private GraphResponse toGraphResponse(
            Map<String, Map<String, Object>> nodesByArn,
            Map<String, String> parentByChild,
            List<Map<String, Object>> edgeRows
    ) {
        var resourceTypeByArn = new HashMap<String, String>();
        for (var entry : nodesByArn.entrySet()) {
            resourceTypeByArn.put(entry.getKey(), normalizedResourceType(entry.getValue().get("resourceType")));
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

        var edges = edgeRows.stream()
                .map(this::toGraphEdge)
                .toList();

        return new GraphResponse(nodes, edges, latestMetadata(nodes.size(), edges.size()));
    }

    private GraphResponse.GraphEdge toGraphEdge(Map<String, Object> edgeRow) {
        var sourceArn = (String) edgeRow.get("sourceArn");
        var targetArn = (String) edgeRow.get("targetArn");
        var relationshipType = (String) edgeRow.get("relationshipType");
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) edgeRow.get("properties");
        return new GraphResponse.GraphEdge(
                sourceArn + "-" + relationshipType + "-" + targetArn,
                sourceArn,
                targetArn,
                relationshipType,
                properties == null ? Map.of() : properties
        );
    }

    private GraphResponse.GraphMetadata latestMetadata(int totalNodes, int totalEdges) {
        var latestCompleted = scanRunRepository.findTopByStatusOrderByCompletedAtDesc(ScanStatus.COMPLETED).orElse(null);
        return new GraphResponse.GraphMetadata(
                totalNodes,
                totalEdges,
                latestCompleted == null ? null : latestCompleted.getCompletedAt(),
                latestCompleted == null ? 0 : latestCompleted.getDurationMs()
        );
    }

    private GraphResponse emptyGraphResponse() {
        return new GraphResponse(List.of(), List.of(), latestMetadata(0, 0));
    }

    private boolean matchesFilters(Map<String, Object> properties, Set<String> resourceTypes, String environment, String tier) {
        var resourceType = normalizedResourceType(properties.get("resourceType"));
        if (GraphViewSupport.isDetailOnlyResourceType(resourceType)) {
            return false;
        }
        var matchesEnvironment = environment.isBlank()
                || environment.equals(String.valueOf(properties.getOrDefault("environment", "")).toLowerCase(Locale.ROOT))
                || "CIDR_SOURCE".equals(resourceType);
        var matchesType = resourceTypes.isEmpty() || resourceTypes.contains(resourceType);
        var matchesTier = tier.isBlank()
                || tier.equals(String.valueOf(properties.getOrDefault("tier", "")).toLowerCase(Locale.ROOT));
        return matchesEnvironment && matchesType && matchesTier;
    }

    private boolean belongsToVpc(
            Map<String, Object> properties,
            Map<String, String> parentByChild,
            Map<String, Map<String, Object>> propertiesByArn,
            Map<String, Set<String>> neighborsByArn,
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
                    : propertiesByArn.get(currentArn);
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

            for (var neighborArn : neighborsByArn.getOrDefault(currentArn, Set.of())) {
                if (!visited.contains(neighborArn)) {
                    queue.add(neighborArn);
                }
            }
        }

        return false;
    }

    private LinkedHashMap<String, Object> asProperties(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) row.get("properties");
        return new LinkedHashMap<>(properties);
    }

    private Set<String> normalizeResourceTypes(Set<String> resourceTypes) {
        if (resourceTypes == null || resourceTypes.isEmpty()) {
            return Set.of();
        }
        return resourceTypes.stream()
                .map(this::normalizeFilterValue)
                .map(String::toUpperCase)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private String normalizedResourceType(Object resourceType) {
        if (resourceType == null) {
            return "";
        }
        return String.valueOf(resourceType).toUpperCase(Locale.ROOT);
    }

    private String normalizeFilterValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
