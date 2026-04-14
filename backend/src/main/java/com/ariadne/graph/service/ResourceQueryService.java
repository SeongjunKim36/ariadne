package com.ariadne.graph.service;

import com.ariadne.api.dto.ResourceDetailResponse;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class ResourceQueryService {

    private final Neo4jClient neo4jClient;

    public ResourceQueryService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    public ResourceDetailResponse findByArn(String arn) {
        var nodeRow = neo4jClient.query("""
                        MATCH (n:AwsResource {arn: $arn})
                        WHERE coalesce(n.stale, false) = false
                        OPTIONAL MATCH (n)-[r]->(parent:AwsResource)
                        WHERE type(r) IN [$belongsTo, $inSubnetGroup]
                          AND coalesce(parent.stale, false) = false
                        RETURN properties(n) AS properties,
                               parent.arn AS parentArn,
                               parent.resourceType AS parentResourceType
                        ORDER BY parent.resourceType
                        LIMIT 1
                        """)
                .bind(arn)
                .to("arn")
                .bind(RelationshipTypes.BELONGS_TO)
                .to("belongsTo")
                .bind(RelationshipTypes.IN_SUBNET_GROUP)
                .to("inSubnetGroup")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("Unknown active resource arn: " + arn));

        var resource = toResourceNode(
                GraphViewSupport.asProperties(nodeRow.get("properties")),
                (String) nodeRow.get("parentArn"),
                (String) nodeRow.get("parentResourceType")
        );

        var connectionRows = neo4jClient.query("""
                        MATCH (source:AwsResource)-[r]-(target:AwsResource)
                        WHERE (source.arn = $arn OR target.arn = $arn)
                          AND coalesce(source.stale, false) = false
                          AND coalesce(target.stale, false) = false
                        WITH source,
                             target,
                             r,
                             CASE
                                 WHEN source.arn = $arn THEN target
                                 ELSE source
                             END AS connected,
                             CASE
                                 WHEN source.arn = $arn THEN 'outgoing'
                                 ELSE 'incoming'
                             END AS direction
                        RETURN properties(connected) AS properties,
                               direction AS direction,
                               type(r) AS relationshipType,
                               properties(r) AS relationshipData
                        ORDER BY connected.name, connected.resourceId
                        """)
                .bind(arn)
                .to("arn")
                .fetch()
                .all();

        var connections = new ArrayList<ResourceDetailResponse.Connection>();
        for (var row : connectionRows) {
            var connectedProperties = new LinkedHashMap<>(GraphViewSupport.asProperties(row.get("properties")));
            connections.add(new ResourceDetailResponse.Connection(
                    (String) row.get("direction"),
                    (String) row.get("relationshipType"),
                    row.get("relationshipData") == null ? Map.of() : GraphViewSupport.asProperties(row.get("relationshipData")),
                    new ResourceDetailResponse.ResourceNode(
                            (String) connectedProperties.get("arn"),
                            GraphViewSupport.toFrontendType((String) connectedProperties.get("resourceType")),
                            connectedProperties,
                            null
                    )
            ));
        }

        return new ResourceDetailResponse(resource, List.copyOf(connections));
    }

    public ResourceDetailResponse findByResourceId(String resourceId) {
        var row = neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE n.resourceId = $resourceId
                          AND coalesce(n.stale, false) = false
                        RETURN n.arn AS arn
                        ORDER BY n.collectedAt DESC, n.resourceType
                        LIMIT 1
                        """)
                .bind(resourceId)
                .to("resourceId")
                .fetch()
                .one()
                .orElseThrow(() -> new NoSuchElementException("Unknown active resource id: " + resourceId));

        return findByArn((String) row.get("arn"));
    }

    private ResourceDetailResponse.ResourceNode toResourceNode(
            Map<String, Object> properties,
            String parentArn,
            String parentResourceType
    ) {
        return new ResourceDetailResponse.ResourceNode(
                (String) properties.get("arn"),
                GraphViewSupport.toFrontendType((String) properties.get("resourceType")),
                new LinkedHashMap<>(properties),
                GraphViewSupport.isGroupParent(parentResourceType) ? parentArn : null
        );
    }
}
