package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("SecurityGroup")
public final class SecurityGroup extends AwsResource {

    private String groupId;
    private String description;
    private Integer inboundRuleCount;
    private Integer outboundRuleCount;

    public SecurityGroup() {
    }

    public SecurityGroup(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String groupId,
            String description,
            Integer inboundRuleCount,
            Integer outboundRuleCount
    ) {
        super(arn, resourceId, "SECURITY_GROUP", name, region, accountId, environment, collectedAt, tags);
        this.groupId = groupId;
        this.description = description;
        this.inboundRuleCount = inboundRuleCount;
        this.outboundRuleCount = outboundRuleCount;
    }

    @Override
    public String graphLabel() {
        return "SecurityGroup";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "groupId", groupId);
        put(properties, "description", description);
        put(properties, "inboundRuleCount", inboundRuleCount);
        put(properties, "outboundRuleCount", outboundRuleCount);
    }
}
