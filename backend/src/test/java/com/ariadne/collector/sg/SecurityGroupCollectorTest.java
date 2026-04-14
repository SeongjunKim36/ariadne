package com.ariadne.collector.sg;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.GetManagedPrefixListEntriesRequest;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.Ipv6Range;
import software.amazon.awssdk.services.ec2.model.ManagedPrefixList;
import software.amazon.awssdk.services.ec2.model.PrefixListEntry;
import software.amazon.awssdk.services.ec2.model.PrefixListId;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.UserIdGroupPair;
import software.amazon.awssdk.services.ec2.paginators.DescribeManagedPrefixListsIterable;
import software.amazon.awssdk.services.ec2.paginators.DescribeSecurityGroupsIterable;
import software.amazon.awssdk.services.ec2.paginators.GetManagedPrefixListEntriesIterable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void collectsSecurityGroupRuleGraphWithCidrsAndPrefixLists() {
        var appSecurityGroup = software.amazon.awssdk.services.ec2.model.SecurityGroup.builder()
                .groupId("sg-app")
                .groupName("app-sg")
                .description("app access")
                .vpcId("vpc-1234")
                .tags(
                        Tag.builder().key("Name").value("prod-app-sg").build(),
                        Tag.builder().key("environment").value("prod").build()
                )
                .build();

        var dbSecurityGroup = software.amazon.awssdk.services.ec2.model.SecurityGroup.builder()
                .groupId("sg-1234")
                .groupName("db-sg")
                .description("database access")
                .vpcId("vpc-1234")
                .ipPermissions(IpPermission.builder()
                        .ipProtocol("tcp")
                        .fromPort(5432)
                        .toPort(5432)
                        .userIdGroupPairs(
                                UserIdGroupPair.builder().groupId("sg-app").description("app to db").build(),
                                UserIdGroupPair.builder().groupId("sg-1234").description("intra-db").build()
                        )
                        .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").description("public db").build())
                        .ipv6Ranges(Ipv6Range.builder().cidrIpv6("::/0").description("public db v6").build())
                        .prefixListIds(PrefixListId.builder().prefixListId("pl-123").description("s3 managed").build())
                        .build())
                .ipPermissionsEgress(
                        IpPermission.builder()
                                .ipProtocol("tcp")
                                .fromPort(443)
                                .toPort(443)
                                .ipRanges(IpRange.builder().cidrIp("10.0.0.0/8").description("private egress").build())
                                .userIdGroupPairs(UserIdGroupPair.builder().groupId("sg-app").description("db to app").build())
                                .build()
                )
                .tags(
                        Tag.builder().key("Name").value("prod-db-sg").build(),
                        Tag.builder().key("environment").value("prod").build()
                )
                .build();

        @SuppressWarnings("unchecked")
        var prefixListPaginator = (DescribeManagedPrefixListsIterable) org.mockito.Mockito.mock(DescribeManagedPrefixListsIterable.class);
        @SuppressWarnings("unchecked")
        var prefixListEntriesPaginator = (GetManagedPrefixListEntriesIterable) org.mockito.Mockito.mock(GetManagedPrefixListEntriesIterable.class);

        when(ec2Client.describeSecurityGroupsPaginator()).thenReturn(paginator);
        when(paginator.securityGroups()).thenReturn(iterableOf(appSecurityGroup, dbSecurityGroup));
        when(ec2Client.describeManagedPrefixListsPaginator(any(software.amazon.awssdk.services.ec2.model.DescribeManagedPrefixListsRequest.class)))
                .thenReturn(prefixListPaginator);
        when(prefixListPaginator.prefixLists()).thenReturn(iterableOf(
                ManagedPrefixList.builder()
                        .prefixListId("pl-123")
                        .prefixListName("com.amazonaws.ap-northeast-2.s3")
                        .build()
        ));
        when(ec2Client.getManagedPrefixListEntriesPaginator(any(GetManagedPrefixListEntriesRequest.class)))
                .thenReturn(prefixListEntriesPaginator);
        when(prefixListEntriesPaginator.entries()).thenReturn(iterableOf(
                PrefixListEntry.builder()
                        .cidr("52.95.255.0/24")
                        .description("S3 managed range")
                        .build()
        ));

        var result = new SecurityGroupCollector(ec2Client).collect(CONTEXT);

        assertThat(new SecurityGroupCollector(ec2Client).managedResourceTypes())
                .isEqualTo(Set.of("SECURITY_GROUP", "CIDR_SOURCE"));
        assertThat(result.resources()).hasSize(6);
        assertThat(result.relationships()).hasSize(9);

        var properties = result.resources().stream()
                .map(resource -> resource.toProperties())
                .filter(resource -> "sg-1234".equals(resource.get("resourceId")))
                .findFirst()
                .orElseThrow();
        assertThat(properties)
                .containsEntry("resourceType", "SECURITY_GROUP")
                .containsEntry("name", "prod-db-sg")
                .containsEntry("environment", "prod")
                .containsEntry("groupId", "sg-1234")
                .containsEntry("description", "database access")
                .containsEntry("inboundRuleCount", 1)
                .containsEntry("outboundRuleCount", 1);

        var publicCidr = result.resources().stream()
                .map(resource -> resource.toProperties())
                .filter(resource -> "0.0.0.0/0".equals(resource.get("cidr")))
                .findFirst()
                .orElseThrow();
        assertThat(publicCidr)
                .containsEntry("resourceType", "CIDR_SOURCE")
                .containsEntry("label", "Public Internet")
                .containsEntry("riskLevel", "HIGH")
                .containsEntry("isPublic", true);

        assertThat(result.relationships())
                .extracting(com.ariadne.graph.relationship.GraphRelationship::type)
                .containsExactlyInAnyOrder(
                        RelationshipTypes.BELONGS_TO,
                        RelationshipTypes.BELONGS_TO,
                        RelationshipTypes.ALLOWS_FROM,
                        RelationshipTypes.ALLOWS_SELF,
                        RelationshipTypes.ALLOWS_TO,
                        RelationshipTypes.ALLOWS_TO,
                        RelationshipTypes.ALLOWS_TO,
                        RelationshipTypes.EGRESS_TO,
                        RelationshipTypes.EGRESS_TO
                );

        var prefixListEdge = result.relationships().stream()
                .filter(relationship -> relationship.type().equals(RelationshipTypes.ALLOWS_TO))
                .filter(relationship -> "52.95.255.0/24".equals(relationship.properties().get("description"))
                        || "pl-123".equals(relationship.properties().get("prefixListId")))
                .findFirst()
                .orElseThrow();

        assertThat(prefixListEdge.properties())
                .containsEntry("sourceKind", "prefix-list")
                .containsEntry("prefixListId", "pl-123")
                .containsEntry("prefixListName", "com.amazonaws.ap-northeast-2.s3")
                .containsEntry("port", "5432")
                .containsEntry("protocol", "tcp");
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
