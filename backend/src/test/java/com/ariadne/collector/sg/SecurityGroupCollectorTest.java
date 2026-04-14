package com.ariadne.collector.sg;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.paginators.DescribeSecurityGroupsIterable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityGroupCollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-13T00:00:00Z")
    );

    @Mock
    private Ec2Client ec2Client;

    @Mock
    private DescribeSecurityGroupsIterable paginator;

    @Test
    void collectsSecurityGroupNodeAndVpcRelationship() {
        var securityGroup = software.amazon.awssdk.services.ec2.model.SecurityGroup.builder()
                .groupId("sg-1234")
                .groupName("app-sg")
                .description("app access")
                .vpcId("vpc-1234")
                .ipPermissions(IpPermission.builder().ipProtocol("tcp").build())
                .ipPermissionsEgress(
                        IpPermission.builder().ipProtocol("-1").build(),
                        IpPermission.builder().ipProtocol("tcp").build()
                )
                .tags(
                        Tag.builder().key("Name").value("prod-app-sg").build(),
                        Tag.builder().key("environment").value("prod").build()
                )
                .build();

        when(ec2Client.describeSecurityGroupsPaginator()).thenReturn(paginator);
        when(paginator.securityGroups()).thenReturn(iterableOf(securityGroup));

        var result = new SecurityGroupCollector(ec2Client).collect(CONTEXT);

        assertThat(result.resources()).hasSize(1);
        assertThat(result.relationships()).hasSize(1);

        var properties = result.resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "SECURITY_GROUP")
                .containsEntry("name", "prod-app-sg")
                .containsEntry("environment", "prod")
                .containsEntry("groupId", "sg-1234")
                .containsEntry("description", "app access")
                .containsEntry("inboundRuleCount", 1)
                .containsEntry("outboundRuleCount", 2);

        var relationship = result.relationships().get(0);
        assertThat(relationship.type()).isEqualTo(RelationshipTypes.BELONGS_TO);
        assertThat(relationship.sourceArn()).isEqualTo("arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234");
        assertThat(relationship.targetArn()).isEqualTo("arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234");
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
