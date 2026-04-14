package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("EcsTaskDefinition")
public final class EcsTaskDefinition extends AwsResource {

    private String family;
    private Integer revision;
    private String cpu;
    private String memory;
    private String networkMode;
    private String taskRoleArn;
    private String executionRoleArn;
    private String requiresCompatibilities;
    private Integer containerCount;
    private String containers;

    public EcsTaskDefinition() {
    }

    public EcsTaskDefinition(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String family,
            Integer revision,
            String cpu,
            String memory,
            String networkMode,
            String taskRoleArn,
            String executionRoleArn,
            String requiresCompatibilities,
            Integer containerCount,
            String containers
    ) {
        super(arn, resourceId, "ECS_TASK_DEFINITION", name, region, accountId, environment, collectedAt, tags);
        this.family = family;
        this.revision = revision;
        this.cpu = cpu;
        this.memory = memory;
        this.networkMode = networkMode;
        this.taskRoleArn = taskRoleArn;
        this.executionRoleArn = executionRoleArn;
        this.requiresCompatibilities = requiresCompatibilities;
        this.containerCount = containerCount;
        this.containers = containers;
    }

    @Override
    public String graphLabel() {
        return "EcsTaskDefinition";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "family", family);
        put(properties, "revision", revision);
        put(properties, "cpu", cpu);
        put(properties, "memory", memory);
        put(properties, "networkMode", networkMode);
        put(properties, "taskRoleArn", taskRoleArn);
        put(properties, "executionRoleArn", executionRoleArn);
        put(properties, "requiresCompatibilities", requiresCompatibilities);
        put(properties, "containerCount", containerCount);
        put(properties, "containers", containers);
    }
}
