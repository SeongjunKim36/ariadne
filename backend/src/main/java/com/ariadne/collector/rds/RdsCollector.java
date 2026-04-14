package com.ariadne.collector.rds;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.DbSubnetGroup;
import com.ariadne.graph.node.RdsInstance;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.ListTagsForResourceRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class RdsCollector extends BaseCollector {

    private final RdsClient rdsClient;

    public RdsCollector(RdsClient rdsClient) {
        this.rdsClient = rdsClient;
    }

    @Override
    public String resourceType() {
        return "RDS";
    }

    @Override
    public Set<String> managedResourceTypes() {
        return Set.of("RDS", "DB_SUBNET_GROUP");
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();

        var subnetGroupPaginator = withRetry(rdsClient::describeDBSubnetGroupsPaginator);
        for (var subnetGroup : subnetGroupPaginator.dbSubnetGroups()) {
            var subnetGroupArn = resolveSubnetGroupArn(context, subnetGroup.dbSubnetGroupArn(), subnetGroup.dbSubnetGroupName());
            var tags = fetchTags(subnetGroupArn);
            var fallbackName = hasText(subnetGroup.dbSubnetGroupName()) ? subnetGroup.dbSubnetGroupName() : subnetGroupArn;

            resources.add(new DbSubnetGroup(
                    subnetGroupArn,
                    subnetGroup.dbSubnetGroupName(),
                    inferName(tags, fallbackName),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    subnetGroup.dbSubnetGroupName(),
                    subnetGroup.dbSubnetGroupDescription(),
                    subnetGroup.subnetGroupStatus(),
                    subnetGroup.subnets() == null ? 0 : subnetGroup.subnets().size()
            ));

            if (hasText(subnetGroup.vpcId())) {
                relationships.add(GraphRelationship.belongsTo(
                        subnetGroupArn,
                        ec2Arn(context, "vpc/" + subnetGroup.vpcId())
                ));
            }

            for (var subnet : subnetGroup.subnets()) {
                if (hasText(subnet.subnetIdentifier())) {
                    relationships.add(new GraphRelationship(
                            subnetGroupArn,
                            ec2Arn(context, "subnet/" + subnet.subnetIdentifier()),
                            RelationshipTypes.CONTAINS,
                            Map.of()
                    ));
                }
            }
        }

        var instancePaginator = withRetry(rdsClient::describeDBInstancesPaginator);
        for (var instance : instancePaginator.dbInstances()) {
            var instanceArn = resolveInstanceArn(context, instance.dbInstanceArn(), instance.dbInstanceIdentifier());
            var tags = fetchTags(instanceArn);
            var fallbackName = hasText(instance.dbInstanceIdentifier()) ? instance.dbInstanceIdentifier() : instanceArn;

            resources.add(new RdsInstance(
                    instanceArn,
                    instance.dbInstanceIdentifier(),
                    inferName(tags, fallbackName),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    instance.engine(),
                    instance.engineVersion(),
                    instance.dbInstanceClass(),
                    instance.dbInstanceStatus(),
                    formatEndpoint(instance.endpoint()),
                    instance.multiAZ(),
                    instance.allocatedStorage(),
                    instance.storageEncrypted()
            ));

            if (instance.dbSubnetGroup() != null && hasText(instance.dbSubnetGroup().dbSubnetGroupName())) {
                relationships.add(new GraphRelationship(
                        instanceArn,
                        resolveSubnetGroupArn(
                                context,
                                instance.dbSubnetGroup().dbSubnetGroupArn(),
                                instance.dbSubnetGroup().dbSubnetGroupName()
                        ),
                        RelationshipTypes.IN_SUBNET_GROUP,
                        Map.of()
                ));
            }

            for (var securityGroup : instance.vpcSecurityGroups()) {
                if (hasText(securityGroup.vpcSecurityGroupId())) {
                    relationships.add(new GraphRelationship(
                            instanceArn,
                            ec2Arn(context, "security-group/" + securityGroup.vpcSecurityGroupId()),
                            RelationshipTypes.HAS_SG,
                            Map.of()
                    ));
                }
            }
        }

        return new CollectResult(resources, relationships);
    }

    private Map<String, String> fetchTags(String arn) {
        if (!hasText(arn)) {
            return Map.of();
        }

        var response = withRetry(() -> rdsClient.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceName(arn)
                .build()));

        var tags = new LinkedHashMap<String, String>();
        for (var tag : response.tagList()) {
            tags.put(tag.key(), tag.value());
        }
        return tags;
    }

    private String resolveInstanceArn(AwsCollectContext context, String arn, String identifier) {
        if (hasText(arn)) {
            return arn;
        }
        return "arn:aws:rds:%s:%s:db:%s".formatted(context.region(), context.accountId(), identifier);
    }

    private String resolveSubnetGroupArn(AwsCollectContext context, String arn, String groupName) {
        if (hasText(arn)) {
            return arn;
        }
        return "arn:aws:rds:%s:%s:subgrp:%s".formatted(context.region(), context.accountId(), groupName);
    }

    private String formatEndpoint(software.amazon.awssdk.services.rds.model.Endpoint endpoint) {
        if (endpoint == null || !hasText(endpoint.address())) {
            return null;
        }
        if (endpoint.port() == null) {
            return endpoint.address();
        }
        return endpoint.address() + ":" + endpoint.port();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
