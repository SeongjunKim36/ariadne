package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Node("IamRole")
public final class IamRole extends AwsResource {

    private String roleName;
    private String assumeRolePolicy;
    private List<String> attachedPolicies;
    private OffsetDateTime lastUsedAt;
    private Boolean hasWildcardAction;
    private Boolean hasWildcardWriteResource;
    private Boolean hasCrossAccountAssume;
    private Boolean hasAdministratorAccess;
    private Boolean hasPassRoleWildcard;

    public IamRole() {
    }

    public IamRole(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String roleName,
            String assumeRolePolicy,
            List<String> attachedPolicies
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
                roleName,
                assumeRolePolicy,
                attachedPolicies,
                null,
                false,
                false,
                false,
                false,
                false
        );
    }

    public IamRole(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String roleName,
            String assumeRolePolicy,
            List<String> attachedPolicies,
            OffsetDateTime lastUsedAt,
            Boolean hasWildcardAction,
            Boolean hasWildcardWriteResource,
            Boolean hasCrossAccountAssume,
            Boolean hasAdministratorAccess,
            Boolean hasPassRoleWildcard
    ) {
        super(arn, resourceId, "IAM_ROLE", name, region, accountId, environment, collectedAt, tags);
        this.roleName = roleName;
        this.assumeRolePolicy = assumeRolePolicy;
        this.attachedPolicies = attachedPolicies;
        this.lastUsedAt = lastUsedAt;
        this.hasWildcardAction = hasWildcardAction;
        this.hasWildcardWriteResource = hasWildcardWriteResource;
        this.hasCrossAccountAssume = hasCrossAccountAssume;
        this.hasAdministratorAccess = hasAdministratorAccess;
        this.hasPassRoleWildcard = hasPassRoleWildcard;
    }

    @Override
    public String graphLabel() {
        return "IamRole";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "roleName", roleName);
        put(properties, "assumeRolePolicy", assumeRolePolicy);
        put(properties, "attachedPolicies", attachedPolicies);
        put(properties, "lastUsedAt", lastUsedAt);
        put(properties, "hasWildcardAction", hasWildcardAction);
        put(properties, "hasWildcardWriteResource", hasWildcardWriteResource);
        put(properties, "hasCrossAccountAssume", hasCrossAccountAssume);
        put(properties, "hasAdministratorAccess", hasAdministratorAccess);
        put(properties, "hasPassRoleWildcard", hasPassRoleWildcard);
    }
}
