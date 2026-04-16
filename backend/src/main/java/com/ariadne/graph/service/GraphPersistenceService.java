package com.ariadne.graph.service;

import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import jakarta.annotation.PostConstruct;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphPersistenceService {

    private final Neo4jClient neo4jClient;

    public GraphPersistenceService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @PostConstruct
    void initializeSchema() {
        for (var statement : List.of(
                "CREATE CONSTRAINT unique_arn IF NOT EXISTS FOR (n:AwsResource) REQUIRE n.arn IS UNIQUE",
                "CREATE INDEX idx_stale IF NOT EXISTS FOR (n:AwsResource) ON (n.stale)",
                "CREATE INDEX idx_resource_id IF NOT EXISTS FOR (n:AwsResource) ON (n.resourceId)",
                "CREATE INDEX idx_resource_type IF NOT EXISTS FOR (n:AwsResource) ON (n.resourceType)",
                "CREATE INDEX idx_environment IF NOT EXISTS FOR (n:AwsResource) ON (n.environment)",
                "CREATE INDEX idx_tier IF NOT EXISTS FOR (n:AwsResource) ON (n.tier)",
                "CREATE INDEX idx_vpc_id IF NOT EXISTS FOR (n:Vpc) ON (n.resourceId)",
                "CREATE INDEX idx_sg_group_id IF NOT EXISTS FOR (n:SecurityGroup) ON (n.groupId)",
                "CREATE INDEX idx_cidr_source_cidr IF NOT EXISTS FOR (n:CidrSource) ON (n.cidr)",
                "CREATE INDEX idx_lb_dns_name IF NOT EXISTS FOR (n:LoadBalancer) ON (n.dnsName)",
                "CREATE INDEX idx_route53_zone_id IF NOT EXISTS FOR (n:Route53Zone) ON (n.hostedZoneId)"
        )) {
            neo4jClient.query(statement).run();
        }
        neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE n.stale IS NULL
                        SET n.stale = false
                        """)
                .run();
    }

    @Transactional
    public void save(CollectResult result, OffsetDateTime collectedAt, Set<String> managedResourceTypes) {
        saveResources(result.resources());
        saveRelationships(result.relationships());
        markMissingResourcesAsStale(result.resources(), collectedAt, managedResourceTypes);
    }

    private void saveResources(List<AwsResource> resources) {
        var rowsByLabel = resources.stream()
                .collect(Collectors.groupingBy(
                        AwsResource::graphLabel,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        rowsByLabel.forEach((label, group) -> {
            var rows = group.stream()
                    .map(resource -> Map.<String, Object>of(
                            "arn", resource.getArn(),
                            "properties", resource.toProperties()
                    ))
                    .toList();

            var query = """
                    UNWIND $rows AS row
                    MERGE (n:AwsResource {arn: row.arn})
                    SET n:%s
                    SET n.stale = false,
                        n.staleSince = null
                    SET n += row.properties
                    """.formatted(label);

            neo4jClient.query(query)
                    .bind(rows)
                    .to("rows")
                    .run();
        });
    }

    private void markMissingResourcesAsStale(
            List<AwsResource> resources,
            OffsetDateTime collectedAt,
            Set<String> managedResourceTypes
    ) {
        if (managedResourceTypes == null || managedResourceTypes.isEmpty()) {
            return;
        }

        var activeArns = resources.stream()
                .map(AwsResource::getArn)
                .toList();

        neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE n.resourceType IN $managedResourceTypes
                          AND NOT n.arn IN $activeArns
                        SET n.stale = true,
                            n.staleSince = coalesce(n.staleSince, $collectedAt)
                        WITH collect(n) AS staleNodes
                        UNWIND staleNodes AS staleNode
                        OPTIONAL MATCH (staleNode)-[rel]-()
                        DELETE rel
                        """)
                .bind(managedResourceTypes)
                .to("managedResourceTypes")
                .bind(activeArns)
                .to("activeArns")
                .bind(collectedAt)
                .to("collectedAt")
                .run();
    }

    private void saveRelationships(List<com.ariadne.graph.relationship.GraphRelationship> relationships) {
        var rowsByType = relationships.stream()
                .collect(Collectors.groupingBy(
                        com.ariadne.graph.relationship.GraphRelationship::type,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        rowsByType.forEach((type, group) -> {
            var rows = group.stream()
                    .map(com.ariadne.graph.relationship.GraphRelationship::toRow)
                    .toList();
            var sourceArns = group.stream()
                    .map(com.ariadne.graph.relationship.GraphRelationship::sourceArn)
                    .distinct()
                    .toList();

            neo4jClient.query("""
                            UNWIND $sourceArns AS sourceArn
                            MATCH (source:AwsResource {arn: sourceArn})-[rel:%s]->()
                            DELETE rel
                            """.formatted(type))
                    .bind(sourceArns)
                    .to("sourceArns")
                    .run();

            var query = """
                    UNWIND $rows AS row
                    MATCH (source:AwsResource {arn: row.sourceArn})
                    MATCH (target:AwsResource {arn: row.targetArn})
                    MERGE (source)-[rel:%s]->(target)
                    SET rel += row.properties
                    """.formatted(type);

            neo4jClient.query(query)
                    .bind(rows)
                    .to("rows")
                    .run();
        });
    }
}
