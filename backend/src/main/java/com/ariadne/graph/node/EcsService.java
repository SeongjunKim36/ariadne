package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("EcsService")
public final class EcsService extends AwsResource {

    private Integer desiredCount;
    private Integer runningCount;
    private Integer pendingCount;
    private String launchType;
    private String taskDefinition;

    public EcsService() {
    }

    public EcsService(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            Integer desiredCount,
            Integer runningCount,
            Integer pendingCount,
            String launchType,
            String taskDefinition
    ) {
        super(arn, resourceId, "ECS_SERVICE", name, region, accountId, environment, collectedAt, tags);
        this.desiredCount = desiredCount;
        this.runningCount = runningCount;
        this.pendingCount = pendingCount;
        this.launchType = launchType;
        this.taskDefinition = taskDefinition;
    }

    @Override
    public String graphLabel() {
        return "EcsService";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "desiredCount", desiredCount);
        put(properties, "runningCount", runningCount);
        put(properties, "pendingCount", pendingCount);
        put(properties, "launchType", launchType);
        put(properties, "taskDefinition", taskDefinition);
    }
}
