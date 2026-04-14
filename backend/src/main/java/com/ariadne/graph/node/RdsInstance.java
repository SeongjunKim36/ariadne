package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("RdsInstance")
public final class RdsInstance extends AwsResource {

    private String engine;
    private String engineVersion;
    private String instanceClass;
    private String status;
    private String endpoint;
    private Boolean multiAz;
    private Integer storageGb;
    private Boolean encrypted;

    public RdsInstance() {
    }

    public RdsInstance(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String engine,
            String engineVersion,
            String instanceClass,
            String status,
            String endpoint,
            Boolean multiAz,
            Integer storageGb,
            Boolean encrypted
    ) {
        super(arn, resourceId, "RDS", name, region, accountId, environment, collectedAt, tags);
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.instanceClass = instanceClass;
        this.status = status;
        this.endpoint = endpoint;
        this.multiAz = multiAz;
        this.storageGb = storageGb;
        this.encrypted = encrypted;
    }

    @Override
    public String graphLabel() {
        return "RdsInstance";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "engine", engine);
        put(properties, "engineVersion", engineVersion);
        put(properties, "instanceClass", instanceClass);
        put(properties, "status", status);
        put(properties, "endpoint", endpoint);
        put(properties, "multiAz", multiAz);
        put(properties, "storageGb", storageGb);
        put(properties, "encrypted", encrypted);
    }
}
