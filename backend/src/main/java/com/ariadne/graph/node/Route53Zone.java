package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("Route53Zone")
public final class Route53Zone extends AwsResource {

    private String hostedZoneId;
    private String domainName;
    private Boolean isPrivate;
    private Long recordCount;
    private String linkedService;

    public Route53Zone() {
    }

    public Route53Zone(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String hostedZoneId,
            String domainName,
            Boolean isPrivate,
            Long recordCount,
            String linkedService
    ) {
        super(arn, resourceId, "ROUTE53_ZONE", name, region, accountId, environment, collectedAt, tags);
        this.hostedZoneId = hostedZoneId;
        this.domainName = domainName;
        this.isPrivate = isPrivate;
        this.recordCount = recordCount;
        this.linkedService = linkedService;
    }

    @Override
    public String graphLabel() {
        return "Route53Zone";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "hostedZoneId", hostedZoneId);
        put(properties, "domainName", domainName);
        put(properties, "isPrivate", isPrivate);
        put(properties, "recordCount", recordCount);
        put(properties, "linkedService", linkedService);
    }
}
