package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Node("LoadBalancer")
public final class LoadBalancer extends AwsResource {

    private String type;
    private String scheme;
    private String dnsName;
    private String state;
    private Integer listenerCount;
    private Integer targetGroupCount;
    private List<Integer> listenerPorts;
    private List<String> listenerProtocols;

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
        this(
                arn,
                resourceId,
                name,
                region,
                accountId,
                environment,
                collectedAt,
                tags,
                type,
                scheme,
                dnsName,
                state,
                listenerCount,
                targetGroupCount,
                List.of(),
                List.of()
        );
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
            Integer targetGroupCount,
            List<Integer> listenerPorts,
            List<String> listenerProtocols
    ) {
        super(arn, resourceId, "LOAD_BALANCER", name, region, accountId, environment, collectedAt, tags);
        this.type = type;
        this.scheme = scheme;
        this.dnsName = dnsName;
        this.state = state;
        this.listenerCount = listenerCount;
        this.targetGroupCount = targetGroupCount;
        this.listenerPorts = listenerPorts;
        this.listenerProtocols = listenerProtocols;
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
        put(properties, "listenerPorts", listenerPorts);
        put(properties, "listenerProtocols", listenerProtocols);
    }
}
