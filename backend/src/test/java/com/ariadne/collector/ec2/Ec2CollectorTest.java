package com.ariadne.collector.ec2;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Ec2CollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-13T00:00:00Z")
    );

    @Mock
    private Ec2Client ec2Client;

    @Mock
    private DescribeInstancesIterable paginator;

    @Test
    void collectsEc2NodeSubnetAndSecurityGroupRelationships() {
        var instance = software.amazon.awssdk.services.ec2.model.Instance.builder()
                .instanceId("i-1234")
                .instanceType(InstanceType.T3_MICRO)
                .subnetId("subnet-1234")
                .imageId("ami-12345678")
                .privateIpAddress("10.30.1.10")
                .publicIpAddress("203.0.113.10")
                .launchTime(Instant.parse("2026-04-13T01:00:00Z"))
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .securityGroups(
                        GroupIdentifier.builder().groupId("sg-1234").build(),
                        GroupIdentifier.builder().groupId("sg-5678").build()
                )
                .tags(
                        Tag.builder().key("Name").value("prod-api-1").build(),
                        Tag.builder().key("environment").value("prod").build()
                )
                .build();
        var reservation = Reservation.builder()
                .instances(instance)
                .build();

        when(ec2Client.describeInstancesPaginator()).thenReturn(paginator);
        when(paginator.reservations()).thenReturn(iterableOf(reservation));

        var result = new Ec2Collector(ec2Client).collect(CONTEXT);

        assertThat(result.resources()).hasSize(1);
        assertThat(result.relationships()).hasSize(3);

        var properties = result.resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "EC2")
                .containsEntry("name", "prod-api-1")
                .containsEntry("environment", "prod")
                .containsEntry("instanceType", "t3.micro")
                .containsEntry("state", "running")
                .containsEntry("privateIp", "10.30.1.10")
                .containsEntry("publicIp", "203.0.113.10")
                .containsEntry("amiId", "ami-12345678")
                .containsEntry("platform", "linux");

        assertThat(result.relationships())
                .extracting(relationship -> relationship.type(), relationship -> relationship.targetArn())
                .containsExactlyInAnyOrder(
                        tuple(RelationshipTypes.BELONGS_TO, "arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-1234"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-5678")
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
