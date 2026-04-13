package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("Subnet")
public final class Subnet extends AwsResource {

    private String cidrBlock;
    private String availabilityZone;
    private Boolean isPublic;
    private Integer availableIpCount;

    public Subnet() {
    }

    public Subnet(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String cidrBlock,
            String availabilityZone,
            Boolean isPublic,
            Integer availableIpCount
    ) {
        super(arn, resourceId, "SUBNET", name, region, accountId, environment, collectedAt, tags);
        this.cidrBlock = cidrBlock;
        this.availabilityZone = availabilityZone;
        this.isPublic = isPublic;
        this.availableIpCount = availableIpCount;
    }

    @Override
    public String graphLabel() {
        return "Subnet";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "cidrBlock", cidrBlock);
        put(properties, "availabilityZone", availabilityZone);
        put(properties, "isPublic", isPublic);
        put(properties, "availableIpCount", availableIpCount);
    }
}
