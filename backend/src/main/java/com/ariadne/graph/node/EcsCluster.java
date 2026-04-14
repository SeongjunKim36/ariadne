package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("EcsCluster")
public final class EcsCluster extends AwsResource {

    private String status;
    private Integer runningTaskCount;
    private Integer registeredContainerInstanceCount;
    private Integer activeServiceCount;

    public EcsCluster() {
    }

    public EcsCluster(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String status,
            Integer runningTaskCount,
            Integer registeredContainerInstanceCount,
            Integer activeServiceCount
    ) {
        super(arn, resourceId, "ECS_CLUSTER", name, region, accountId, environment, collectedAt, tags);
        this.status = status;
        this.runningTaskCount = runningTaskCount;
        this.registeredContainerInstanceCount = registeredContainerInstanceCount;
        this.activeServiceCount = activeServiceCount;
    }

    @Override
    public String graphLabel() {
        return "EcsCluster";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "status", status);
        put(properties, "runningTaskCount", runningTaskCount);
        put(properties, "registeredContainerInstanceCount", registeredContainerInstanceCount);
        put(properties, "activeServiceCount", activeServiceCount);
    }
}
