package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("CidrSource")
public final class CidrSource extends AwsResource {

    private String cidr;
    private String label;
    private Boolean isPublic;
    private String riskLevel;
    private String addressFamily;

    public CidrSource() {
    }

    public CidrSource(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            String cidr,
            String label,
            Boolean isPublic,
            String riskLevel,
            String addressFamily
    ) {
        super(arn, resourceId, "CIDR_SOURCE", name, region, accountId, environment, collectedAt, Map.of());
        this.cidr = cidr;
        this.label = label;
        this.isPublic = isPublic;
        this.riskLevel = riskLevel;
        this.addressFamily = addressFamily;
    }

    @Override
    public String graphLabel() {
        return "CidrSource";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "cidr", cidr);
        put(properties, "label", label);
        put(properties, "isPublic", isPublic);
        put(properties, "riskLevel", riskLevel);
        put(properties, "addressFamily", addressFamily);
    }
}
