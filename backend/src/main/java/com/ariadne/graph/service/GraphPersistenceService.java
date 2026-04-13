package com.ariadne.graph.service;

import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import jakarta.annotation.PostConstruct;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                "CREATE INDEX idx_resource_id IF NOT EXISTS FOR (n:AwsResource) ON (n.resourceId)",
                "CREATE INDEX idx_resource_type IF NOT EXISTS FOR (n:AwsResource) ON (n.resourceType)",
                "CREATE INDEX idx_environment IF NOT EXISTS FOR (n:AwsResource) ON (n.environment)",
                "CREATE INDEX idx_vpc_id IF NOT EXISTS FOR (n:Vpc) ON (n.resourceId)"
        )) {
            neo4jClient.query(statement).run();
        }
    }

    @Transactional
    public void save(CollectResult result) {
        saveResources(result.resources());
        saveRelationships(result.relationships());
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
                    SET n += row.properties
                    """.formatted(label);

            neo4jClient.query(query)
                    .bind(rows)
                    .to("rows")
                    .run();
        });
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
