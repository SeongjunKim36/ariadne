package com.ariadne.collector.ec2;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.Ec2Instance;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.time.ZoneOffset;
import java.util.ArrayList;

@Component
public class Ec2Collector extends BaseCollector {

    private final Ec2Client ec2Client;

    public Ec2Collector(Ec2Client ec2Client) {
        this.ec2Client = ec2Client;
    }

    @Override
    public String resourceType() {
        return "EC2";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var paginator = withRetry(() -> ec2Client.describeInstancesPaginator());

        for (var reservation : paginator.reservations()) {
            for (var instance : reservation.instances()) {
                var tags = toTagMap(instance.tags());
                var instanceArn = ec2Arn(context, "instance/" + instance.instanceId());

                resources.add(new Ec2Instance(
                        instanceArn,
                        instance.instanceId(),
                        inferName(tags, instance.instanceId()),
                        context.region(),
                        context.accountId(),
                        inferEnvironment(tags),
                        context.collectedAt(),
                        tags,
                        instance.instanceTypeAsString(),
                        instance.state() == null ? null : instance.state().nameAsString(),
                        instance.privateIpAddress(),
                        instance.publicIpAddress(),
                        instance.imageId(),
                        instance.launchTime() == null ? null : instance.launchTime().atOffset(ZoneOffset.UTC),
                        resolvePlatform(instance.platformAsString(), instance.platformDetails())
                ));

                if (hasText(instance.subnetId())) {
                    relationships.add(GraphRelationship.belongsTo(
                            instanceArn,
                            ec2Arn(context, "subnet/" + instance.subnetId())
                    ));
                }

                for (var groupIdentifier : instance.securityGroups()) {
                    if (hasText(groupIdentifier.groupId())) {
                        relationships.add(new GraphRelationship(
                                instanceArn,
                                ec2Arn(context, "security-group/" + groupIdentifier.groupId()),
                                RelationshipTypes.HAS_SG,
                                java.util.Map.of()
                        ));
                    }
                }
            }
        }

        return new CollectResult(resources, relationships);
    }

    private String resolvePlatform(String platform, String platformDetails) {
        if (hasText(platform)) {
            return platform.toLowerCase();
        }
        if (hasText(platformDetails)) {
            return platformDetails.toLowerCase();
        }
        return "linux";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
