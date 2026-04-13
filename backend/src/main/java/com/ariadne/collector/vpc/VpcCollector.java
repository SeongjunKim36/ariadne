package com.ariadne.collector.vpc;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.Vpc;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.util.ArrayList;

@Component
public class VpcCollector extends BaseCollector {

    private final Ec2Client ec2Client;

    public VpcCollector(Ec2Client ec2Client) {
        this.ec2Client = ec2Client;
    }

    @Override
    public String resourceType() {
        return "VPC";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var paginator = withRetry(() -> ec2Client.describeVpcsPaginator());
        for (var vpc : paginator.vpcs()) {
            var tags = toTagMap(vpc.tags());
            resources.add(new Vpc(
                    ec2Arn(context, "vpc/" + vpc.vpcId()),
                    vpc.vpcId(),
                    inferName(tags, vpc.vpcId()),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    vpc.cidrBlock(),
                    vpc.isDefault(),
                    vpc.stateAsString()
            ));
        }
        return new CollectResult(resources, java.util.List.of());
    }
}
