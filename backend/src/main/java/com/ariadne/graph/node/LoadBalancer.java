package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("LoadBalancer")
public final class LoadBalancer extends AwsResource {

    private String type;
    private String scheme;
    private String dnsName;
    private String state;
    private Integer listenerCount;
    private Integer targetGroupCount;

    public LoadBalancer() {
    }

    public LoadBalancer(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String type,
            String scheme,
            String dnsName,
            String state,
            Integer listenerCount,
            Integer targetGroupCount
    ) {
        super(arn, resourceId, "LOAD_BALANCER", name, region, accountId, environment, collectedAt, tags);
        this.type = type;
        this.scheme = scheme;
        this.dnsName = dnsName;
        this.state = state;
        this.listenerCount = listenerCount;
        this.targetGroupCount = targetGroupCount;
    }

    @Override
    public String graphLabel() {
        return "LoadBalancer";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "type", type);
        put(properties, "scheme", scheme);
        put(properties, "dnsName", dnsName);
        put(properties, "state", state);
        put(properties, "listenerCount", listenerCount);
        put(properties, "targetGroupCount", targetGroupCount);
    }
}
