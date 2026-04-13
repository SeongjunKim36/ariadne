package com.ariadne.collector.vpc;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.Subnet;
import com.ariadne.graph.relationship.GraphRelationship;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.util.ArrayList;

@Component
public class SubnetCollector extends BaseCollector {

    private final Ec2Client ec2Client;

    public SubnetCollector(Ec2Client ec2Client) {
        this.ec2Client = ec2Client;
    }

    @Override
    public String resourceType() {
        return "SUBNET";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var paginator = withRetry(() -> ec2Client.describeSubnetsPaginator());

        for (var subnet : paginator.subnets()) {
            var tags = toTagMap(subnet.tags());
            var subnetArn = ec2Arn(context, "subnet/" + subnet.subnetId());
            resources.add(new Subnet(
                    subnetArn,
                    subnet.subnetId(),
                    inferName(tags, subnet.subnetId()),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    subnet.cidrBlock(),
                    subnet.availabilityZone(),
                    subnet.mapPublicIpOnLaunch(),
                    subnet.availableIpAddressCount()
            ));
            relationships.add(GraphRelationship.belongsTo(
                    subnetArn,
                    ec2Arn(context, "vpc/" + subnet.vpcId())
            ));
        }

        return new CollectResult(resources, relationships);
    }
}
