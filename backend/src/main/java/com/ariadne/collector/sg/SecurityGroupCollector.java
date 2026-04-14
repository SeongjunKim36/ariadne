package com.ariadne.collector.sg;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.SecurityGroup;
import com.ariadne.graph.relationship.GraphRelationship;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.util.ArrayList;

@Component
public class SecurityGroupCollector extends BaseCollector {

    private final Ec2Client ec2Client;

    public SecurityGroupCollector(Ec2Client ec2Client) {
        this.ec2Client = ec2Client;
    }

    @Override
    public String resourceType() {
        return "SECURITY_GROUP";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var paginator = withRetry(() -> ec2Client.describeSecurityGroupsPaginator());

        for (var securityGroup : paginator.securityGroups()) {
            var tags = toTagMap(securityGroup.tags());
            var securityGroupArn = ec2Arn(context, "security-group/" + securityGroup.groupId());
            var fallbackName = hasText(securityGroup.groupName()) ? securityGroup.groupName() : securityGroup.groupId();
            resources.add(new SecurityGroup(
                    securityGroupArn,
                    securityGroup.groupId(),
                    inferName(tags, fallbackName),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    securityGroup.groupId(),
                    securityGroup.description(),
                    securityGroup.ipPermissions() == null ? 0 : securityGroup.ipPermissions().size(),
                    securityGroup.ipPermissionsEgress() == null ? 0 : securityGroup.ipPermissionsEgress().size()
            ));

            if (hasText(securityGroup.vpcId())) {
                relationships.add(GraphRelationship.belongsTo(
                        securityGroupArn,
                        ec2Arn(context, "vpc/" + securityGroup.vpcId())
                ));
            }
        }

        return new CollectResult(resources, relationships);
    }
}
