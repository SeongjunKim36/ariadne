package com.ariadne.collector.ecs;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeClustersResponse;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.ecs.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.paginators.ListClustersIterable;
import software.amazon.awssdk.services.ecs.paginators.ListServicesIterable;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.paginators.DescribeTargetGroupsIterable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcsCollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-14T00:00:00Z")
    );

    @Mock
    private EcsClient ecsClient;

    @Mock
    private ElasticLoadBalancingV2Client elbClient;

    @Mock
    private ListClustersIterable clusterPaginator;

    @Mock
    private ListServicesIterable servicePaginator;

    @Mock
    private DescribeTargetGroupsIterable targetGroupPaginator;

    @Test
    void collectsEcsClusterServiceAndRelationships() {
        var clusterArn = "arn:aws:ecs:ap-northeast-2:123456789012:cluster/prod-cluster";
        var serviceArn = "arn:aws:ecs:ap-northeast-2:123456789012:service/prod-cluster/prod-api";
        var loadBalancerArn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-api/abcdef";
        var targetGroupArn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/prod-api/123456";

        when(elbClient.describeTargetGroupsPaginator()).thenReturn(targetGroupPaginator);
        when(targetGroupPaginator.targetGroups()).thenReturn(iterableOf(TargetGroup.builder()
                .targetGroupArn(targetGroupArn)
                .loadBalancerArns(loadBalancerArn)
                .protocol("HTTP")
                .build()));

        when(ecsClient.listClustersPaginator()).thenReturn(clusterPaginator);
        when(clusterPaginator.clusterArns()).thenReturn(iterableOf(clusterArn));
        when(ecsClient.describeClusters(any(DescribeClustersRequest.class))).thenReturn(DescribeClustersResponse.builder()
                .clusters(Cluster.builder()
                        .clusterArn(clusterArn)
                        .clusterName("prod-cluster")
                        .status("ACTIVE")
                        .runningTasksCount(3)
                        .registeredContainerInstancesCount(0)
                        .activeServicesCount(1)
                        .build())
                .build());
        when(ecsClient.listServicesPaginator(any(ListServicesRequest.class))).thenReturn(servicePaginator);
        when(servicePaginator.serviceArns()).thenReturn(iterableOf(serviceArn));
        when(ecsClient.describeServices(any(DescribeServicesRequest.class))).thenReturn(DescribeServicesResponse.builder()
                .services(Service.builder()
                        .serviceArn(serviceArn)
                        .serviceName("prod-api")
                        .desiredCount(2)
                        .runningCount(2)
                        .pendingCount(0)
                        .launchType("FARGATE")
                        .taskDefinition("prod-api:5")
                        .networkConfiguration(NetworkConfiguration.builder()
                                .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                        .securityGroups("sg-1234")
                                        .subnets("subnet-1234")
                                        .build())
                                .build())
                        .loadBalancers(software.amazon.awssdk.services.ecs.model.LoadBalancer.builder()
                                .targetGroupArn(targetGroupArn)
                                .containerPort(8080)
                                .build())
                        .build())
                .build());
        when(ecsClient.listTagsForResource(any(ListTagsForResourceRequest.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, ListTagsForResourceRequest.class);
            if (clusterArn.equals(request.resourceArn())) {
                return ListTagsForResourceResponse.builder()
                        .tags(
                                Tag.builder().key("Name").value("prod-cluster").build(),
                                Tag.builder().key("environment").value("prod").build()
                        )
                        .build();
            }
            return ListTagsForResourceResponse.builder()
                    .tags(
                            Tag.builder().key("Name").value("prod-api").build(),
                            Tag.builder().key("environment").value("prod").build()
                    )
                    .build();
        });

        var result = new EcsCollector(ecsClient, elbClient).collect(CONTEXT);

        assertThat(result.resources()).hasSize(2);
        assertThat(result.relationships()).hasSize(3);

        assertThat(result.resources())
                .extracting(resource -> resource.getResourceType(), resource -> resource.getName())
                .containsExactlyInAnyOrder(
                        tuple("ECS_CLUSTER", "prod-cluster"),
                        tuple("ECS_SERVICE", "prod-api")
                );

        var clusterProperties = result.resources().stream()
                .filter(resource -> "ECS_CLUSTER".equals(resource.getResourceType()))
                .findFirst()
                .orElseThrow()
                .toProperties();
        assertThat(clusterProperties)
                .containsEntry("status", "ACTIVE")
                .containsEntry("runningTaskCount", 3)
                .containsEntry("activeServiceCount", 1);

        var serviceProperties = result.resources().stream()
                .filter(resource -> "ECS_SERVICE".equals(resource.getResourceType()))
                .findFirst()
                .orElseThrow()
                .toProperties();
        assertThat(serviceProperties)
                .containsEntry("desiredCount", 2)
                .containsEntry("runningCount", 2)
                .containsEntry("launchType", "FARGATE")
                .containsEntry("taskDefinition", "prod-api:5");

        assertThat(result.relationships())
                .extracting(relationship -> relationship.type(), relationship -> relationship.targetArn())
                .containsExactlyInAnyOrder(
                        tuple(RelationshipTypes.RUNS_IN, clusterArn),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234"),
                        tuple(RelationshipTypes.ROUTES_TO, serviceArn)
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
