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
        super(arn, resourceId, "IAM_ROLE", name, region, accountId, environment, collectedAt, tags);
        this.roleName = roleName;
        this.assumeRolePolicy = assumeRolePolicy;
        this.attachedPolicies = attachedPolicies;
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
    }
}
