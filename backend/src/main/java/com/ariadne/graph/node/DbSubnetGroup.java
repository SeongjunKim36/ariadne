package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("DbSubnetGroup")
public final class DbSubnetGroup extends AwsResource {

    private String groupName;
    private String description;
    private String status;
    private Integer subnetCount;

    public DbSubnetGroup() {
    }

    public DbSubnetGroup(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String groupName,
            String description,
            String status,
            Integer subnetCount
    ) {
        super(arn, resourceId, "DB_SUBNET_GROUP", name, region, accountId, environment, collectedAt, tags);
        this.groupName = groupName;
        this.description = description;
        this.status = status;
        this.subnetCount = subnetCount;
    }

    @Override
    public String graphLabel() {
        return "DbSubnetGroup";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "groupName", groupName);
        put(properties, "description", description);
        put(properties, "status", status);
        put(properties, "subnetCount", subnetCount);
    }
}
