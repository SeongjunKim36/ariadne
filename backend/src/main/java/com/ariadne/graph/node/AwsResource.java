package com.ariadne.graph.node;

import org.springframework.data.annotation.Id;
import org.springframework.data.neo4j.core.schema.CompositeProperty;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Node("AwsResource")
public abstract class AwsResource {

    @Id
    private String arn;
    private String resourceId;
    private String resourceType;
    private String name;
    private String region;
    private String accountId;
    private String environment;
    private OffsetDateTime collectedAt;
    private Boolean stale;
    private OffsetDateTime staleSince;

    @CompositeProperty(prefix = "tag")
    private Map<String, String> tags = new LinkedHashMap<>();

    protected AwsResource() {
    }

    protected AwsResource(
            String arn,
            String resourceId,
            String resourceType,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags
    ) {
        this.arn = arn;
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.name = name;
        this.region = region;
        this.accountId = accountId;
        this.environment = environment;
        this.collectedAt = collectedAt;
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
        this.stale = false;
    }

    public abstract String graphLabel();

    public Map<String, Object> toProperties() {
        var properties = new LinkedHashMap<String, Object>();
        put(properties, "arn", arn);
        put(properties, "resourceId", resourceId);
        put(properties, "resourceType", resourceType);
        put(properties, "name", name);
        put(properties, "region", region);
        put(properties, "accountId", accountId);
        put(properties, "environment", environment);
        put(properties, "collectedAt", collectedAt);
        put(properties, "stale", stale);
        put(properties, "staleSince", staleSince);
        tags.forEach((key, value) -> put(properties, "tag_" + normalizeTagKey(key), value));
        addTypeSpecificProperties(properties);
        return properties;
    }

    protected abstract void addTypeSpecificProperties(Map<String, Object> properties);

    protected void put(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private String normalizeTagKey(String key) {
        return key.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    public String getArn() {
        return arn;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getName() {
        return name;
    }

    public String getRegion() {
        return region;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getEnvironment() {
        return environment;
    }

    public OffsetDateTime getCollectedAt() {
        return collectedAt;
    }

    public Boolean getStale() {
        return stale;
    }

    public OffsetDateTime getStaleSince() {
        return staleSince;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
