package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("S3Bucket")
public final class S3Bucket extends AwsResource {

    private OffsetDateTime creationDate;
    private Boolean versioningEnabled;
    private String encryptionType;
    private Boolean publicAccessBlocked;

    public S3Bucket() {
    }

    public S3Bucket(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            OffsetDateTime creationDate,
            Boolean versioningEnabled,
            String encryptionType,
            Boolean publicAccessBlocked
    ) {
        super(arn, resourceId, "S3_BUCKET", name, region, accountId, environment, collectedAt, tags);
        this.creationDate = creationDate;
        this.versioningEnabled = versioningEnabled;
        this.encryptionType = encryptionType;
        this.publicAccessBlocked = publicAccessBlocked;
    }

    @Override
    public String graphLabel() {
        return "S3Bucket";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "creationDate", creationDate);
        put(properties, "versioningEnabled", versioningEnabled);
        put(properties, "encryptionType", encryptionType);
        put(properties, "publicAccessBlocked", publicAccessBlocked);
    }
}
