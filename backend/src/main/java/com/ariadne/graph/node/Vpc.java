package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("Vpc")
public final class Vpc extends AwsResource {

    private String cidrBlock;
    private Boolean isDefault;
    private String state;

    public Vpc() {
    }

    public Vpc(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String cidrBlock,
            Boolean isDefault,
            String state
    ) {
        super(arn, resourceId, "VPC", name, region, accountId, environment, collectedAt, tags);
        this.cidrBlock = cidrBlock;
        this.isDefault = isDefault;
        this.state = state;
    }

    @Override
    public String graphLabel() {
        return "Vpc";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "cidrBlock", cidrBlock);
        put(properties, "isDefault", isDefault);
        put(properties, "state", state);
    }
}
