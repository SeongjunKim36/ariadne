package com.ariadne.graph.service;

import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class GraphInferenceService {

    private final Neo4jClient neo4jClient;

    public GraphInferenceService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Transactional
    public int refreshLikelyUsesRelationships() {
        neo4jClient.query("""
                        MATCH (:AwsResource)-[rel:LIKELY_USES]->(:AwsResource)
                        DELETE rel
                        """)
                .run();

        var row = neo4jClient.query("""
                        MATCH (compute:AwsResource)-[:HAS_SG]->(sg:SecurityGroup)<-[:HAS_SG]-(rds:RdsInstance)
                        WHERE coalesce(compute.stale, false) = false
                          AND coalesce(sg.stale, false) = false
                          AND coalesce(rds.stale, false) = false
                          AND compute.resourceType IN ['EC2', 'ECS_SERVICE']
                          AND (
                              coalesce(compute.environment, 'unknown') = 'unknown'
                              OR coalesce(rds.environment, 'unknown') = 'unknown'
                              OR compute.environment = rds.environment
                          )
                        WITH compute,
                             rds,
                             collect(DISTINCT coalesce(sg.groupId, sg.resourceId)) AS sharedGroupIds
                        WITH compute,
                             rds,
                             sharedGroupIds,
                             CASE
                                 WHEN rds.endpoint IS NOT NULL AND rds.endpoint CONTAINS ':' THEN toInteger(split(rds.endpoint, ':')[1])
                                 WHEN rds.engine IN ['postgres', 'aurora-postgresql'] THEN 5432
                                 WHEN rds.engine IN ['mysql', 'mariadb', 'aurora', 'aurora-mysql'] THEN 3306
                                 WHEN rds.engine STARTS WITH 'sqlserver' THEN 1433
                                 WHEN rds.engine STARTS WITH 'oracle' THEN 1521
                                 ELSE null
                             END AS dbPort
                        MERGE (compute)-[rel:LIKELY_USES]->(rds)
                        SET rel.confidence = CASE
                                WHEN size(sharedGroupIds) > 1 THEN 'high'
                                ELSE 'medium'
                            END,
                            rel.reason = 'shared-sg-db-port',
                            rel.sharedGroupIds = sharedGroupIds,
                            rel.port = dbPort
                        RETURN count(rel) AS relationshipCount
                        """)
                .fetch()
                .one()
                .orElse(Map.of("relationshipCount", 0L));

        var count = row.get("relationshipCount");
        if (count instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
