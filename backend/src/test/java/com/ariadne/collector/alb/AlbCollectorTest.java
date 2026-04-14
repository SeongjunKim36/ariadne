package com.ariadne.collector.alb;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerSchemeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerState;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TagDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealth;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.paginators.DescribeListenersIterable;
import software.amazon.awssdk.services.elasticloadbalancingv2.paginators.DescribeLoadBalancersIterable;
import software.amazon.awssdk.services.elasticloadbalancingv2.paginators.DescribeTargetGroupsIterable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlbCollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-14T00:00:00Z")
    );

    @Mock
    private ElasticLoadBalancingV2Client elbClient;

    @Mock
    private DescribeLoadBalancersIterable loadBalancerPaginator;

    @Mock
    private DescribeTargetGroupsIterable targetGroupPaginator;

    @Mock
    private DescribeListenersIterable listenerPaginator;

    @Test
    void collectsAlbNodeAndRouteRelationships() {
        var loadBalancer = LoadBalancer.builder()
                .loadBalancerArn("arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-web/1234567890")
                .loadBalancerName("prod-web")
                .scheme(LoadBalancerSchemeEnum.INTERNET_FACING)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .dnsName("prod-web-123.ap-northeast-2.elb.amazonaws.com")
                .vpcId("vpc-1234")
                .securityGroups("sg-1234", "sg-5678")
                .state(LoadBalancerState.builder().code(LoadBalancerStateEnum.ACTIVE).build())
                .build();
        var targetGroup = TargetGroup.builder()
                .targetGroupArn("arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/prod-web/abcdef")
                .protocol("HTTP")
                .port(80)
                .targetType("instance")
                .build();
        var listener = Listener.builder()
                .listenerArn("listener-arn")
                .port(443)
                .defaultActions(Action.builder().type("forward").build())
                .build();

        when(elbClient.describeLoadBalancersPaginator()).thenReturn(loadBalancerPaginator);
        when(loadBalancerPaginator.loadBalancers()).thenReturn(iterableOf(loadBalancer));
        when(elbClient.describeTargetGroupsPaginator(any(DescribeTargetGroupsRequest.class))).thenReturn(targetGroupPaginator);
        when(targetGroupPaginator.targetGroups()).thenReturn(iterableOf(targetGroup));
        when(elbClient.describeListenersPaginator(any(DescribeListenersRequest.class))).thenReturn(listenerPaginator);
        when(listenerPaginator.listeners()).thenReturn(iterableOf(listener));
        when(elbClient.describeTags(any(DescribeTagsRequest.class))).thenReturn(DescribeTagsResponse.builder()
                .tagDescriptions(TagDescription.builder()
                        .resourceArn(loadBalancer.loadBalancerArn())
                        .tags(
                                Tag.builder().key("Name").value("prod-web-alb").build(),
                                Tag.builder().key("environment").value("prod").build()
                        )
                        .build())
                .build());
        when(elbClient.describeTargetHealth(any(DescribeTargetHealthRequest.class))).thenReturn(DescribeTargetHealthResponse.builder()
                .targetHealthDescriptions(TargetHealthDescription.builder()
                        .target(TargetDescription.builder().id("i-1234").port(80).build())
                        .targetHealth(TargetHealth.builder().state(TargetHealthStateEnum.HEALTHY).build())
                        .build())
                .build());

        var result = new AlbCollector(elbClient).collect(CONTEXT);

        assertThat(result.resources()).hasSize(1);
        assertThat(result.relationships()).hasSize(4);

        var properties = result.resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "LOAD_BALANCER")
                .containsEntry("name", "prod-web-alb")
                .containsEntry("environment", "prod")
                .containsEntry("type", "application")
                .containsEntry("scheme", "internet-facing")
                .containsEntry("dnsName", "prod-web-123.ap-northeast-2.elb.amazonaws.com")
                .containsEntry("state", "active")
                .containsEntry("listenerCount", 1)
                .containsEntry("targetGroupCount", 1);

        assertThat(result.relationships())
                .extracting(relationship -> relationship.type(), relationship -> relationship.targetArn())
                .containsExactlyInAnyOrder(
                        tuple(RelationshipTypes.BELONGS_TO, "arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-5678"),
                        tuple(RelationshipTypes.ROUTES_TO, "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234")
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
