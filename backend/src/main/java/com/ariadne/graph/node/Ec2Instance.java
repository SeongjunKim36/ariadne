package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("Ec2Instance")
public final class Ec2Instance extends AwsResource {

    private String instanceType;
    private String state;
    private String privateIp;
    private String publicIp;
    private String amiId;
    private OffsetDateTime launchTime;
    private String platform;

    public Ec2Instance() {
    }

    public Ec2Instance(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String instanceType,
            String state,
            String privateIp,
            String publicIp,
            String amiId,
            OffsetDateTime launchTime,
            String platform
    ) {
        super(arn, resourceId, "EC2", name, region, accountId, environment, collectedAt, tags);
        this.instanceType = instanceType;
        this.state = state;
        this.privateIp = privateIp;
        this.publicIp = publicIp;
        this.amiId = amiId;
        this.launchTime = launchTime;
        this.platform = platform;
    }

    @Override
    public String graphLabel() {
        return "Ec2Instance";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "instanceType", instanceType);
        put(properties, "state", state);
        put(properties, "privateIp", privateIp);
        put(properties, "publicIp", publicIp);
        put(properties, "amiId", amiId);
        put(properties, "launchTime", launchTime);
        put(properties, "platform", platform);
    }
}
