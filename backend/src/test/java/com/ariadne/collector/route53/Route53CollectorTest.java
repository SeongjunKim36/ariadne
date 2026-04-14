package com.ariadne.collector.route53;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.paginators.DescribeLoadBalancersIterable;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.Endpoint;
import software.amazon.awssdk.services.rds.paginators.DescribeDBInstancesIterable;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.HostedZone;
import software.amazon.awssdk.services.route53.model.HostedZoneConfig;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.route53.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.ResourceTagSet;
import software.amazon.awssdk.services.route53.model.Tag;
import software.amazon.awssdk.services.route53.paginators.ListHostedZonesIterable;
import software.amazon.awssdk.services.route53.paginators.ListResourceRecordSetsIterable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Route53CollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-14T00:00:00Z")
    );

    @Mock
    private Route53Client route53Client;

    @Mock
    private ElasticLoadBalancingV2Client elbClient;

    @Mock
    private Ec2Client ec2Client;

    @Mock
    private RdsClient rdsClient;

    @Mock
    private ListHostedZonesIterable zonePaginator;

    @Mock
    private ListResourceRecordSetsIterable recordSetPaginator;

    @Mock
    private DescribeLoadBalancersIterable loadBalancerPaginator;

    @Mock
    private DescribeInstancesIterable instancePaginator;

    @Mock
    private DescribeDBInstancesIterable dbInstancePaginator;

    @Test
    void collectsRoute53ZoneAndLinksRecordsToResources() {
        when(elbClient.describeLoadBalancersPaginator()).thenReturn(loadBalancerPaginator);
        when(loadBalancerPaginator.loadBalancers()).thenReturn(iterableOf(LoadBalancer.builder()
                .loadBalancerArn("arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-web/123")
                .dnsName("prod-web-123.ap-northeast-2.elb.amazonaws.com")
                .build()));

        when(ec2Client.describeInstancesPaginator()).thenReturn(instancePaginator);
        when(instancePaginator.reservations()).thenReturn(iterableOf(Reservation.builder()
                .instances(Instance.builder()
                        .instanceId("i-1234")
                        .publicIpAddress("203.0.113.10")
                        .build())
                .build()));

        when(rdsClient.describeDBInstancesPaginator()).thenReturn(dbInstancePaginator);
        when(dbInstancePaginator.dbInstances()).thenReturn(iterableOf(DBInstance.builder()
                .dbInstanceArn("arn:aws:rds:ap-northeast-2:123456789012:db:prod-db")
                .endpoint(Endpoint.builder().address("prod-db.cluster.local").port(5432).build())
                .build()));

        when(route53Client.listHostedZonesPaginator()).thenReturn(zonePaginator);
        when(zonePaginator.hostedZones()).thenReturn(iterableOf(HostedZone.builder()
                .id("/hostedzone/Z1234")
                .name("example.com.")
                .config(HostedZoneConfig.builder().privateZone(false).build())
                .resourceRecordSetCount(3L)
                .build()));
        when(route53Client.listTagsForResource(any(ListTagsForResourceRequest.class))).thenReturn(ListTagsForResourceResponse.builder()
                .resourceTagSet(ResourceTagSet.builder()
                        .tags(
                                Tag.builder().key("Name").value("example.com").build(),
                                Tag.builder().key("environment").value("prod").build()
                        )
                        .build())
                .build());
        when(route53Client.listResourceRecordSetsPaginator(any(ListResourceRecordSetsRequest.class))).thenReturn(recordSetPaginator);
        when(recordSetPaginator.resourceRecordSets()).thenReturn(iterableOf(
                ResourceRecordSet.builder()
                        .name("api.example.com.")
                        .type("A")
                        .aliasTarget(AliasTarget.builder()
                                .dnsName("prod-web-123.ap-northeast-2.elb.amazonaws.com.")
                                .build())
                        .build(),
                ResourceRecordSet.builder()
                        .name("worker.example.com.")
                        .type("A")
                        .resourceRecords(ResourceRecord.builder().value("203.0.113.10").build())
                        .build(),
                ResourceRecordSet.builder()
                        .name("db.example.com.")
                        .type("CNAME")
                        .resourceRecords(ResourceRecord.builder().value("prod-db.cluster.local").build())
                        .build()
        ));

        var result = new Route53Collector(route53Client, elbClient, ec2Client, rdsClient).collect(CONTEXT);

        assertThat(result.resources()).hasSize(1);
        assertThat(result.relationships()).hasSize(3);

        var properties = result.resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "ROUTE53_ZONE")
                .containsEntry("region", "global")
                .containsEntry("hostedZoneId", "Z1234")
                .containsEntry("domainName", "example.com")
                .containsEntry("recordCount", 3L)
                .containsEntry("environment", "prod");

        assertThat(result.relationships())
                .extracting(relationship -> relationship.type(), relationship -> relationship.targetArn())
                .containsExactlyInAnyOrder(
                        tuple(RelationshipTypes.HAS_RECORD, "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/prod-web/123"),
                        tuple(RelationshipTypes.HAS_RECORD, "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234"),
                        tuple(RelationshipTypes.HAS_RECORD, "arn:aws:rds:ap-northeast-2:123456789012:db:prod-db")
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
