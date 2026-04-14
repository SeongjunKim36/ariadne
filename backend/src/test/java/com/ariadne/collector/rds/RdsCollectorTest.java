package com.ariadne.collector.rds;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBSubnetGroup;
import software.amazon.awssdk.services.rds.model.Endpoint;
import software.amazon.awssdk.services.rds.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.rds.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.rds.model.Subnet;
import software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership;
import software.amazon.awssdk.services.rds.paginators.DescribeDBInstancesIterable;
import software.amazon.awssdk.services.rds.paginators.DescribeDBSubnetGroupsIterable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RdsCollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-13T00:00:00Z")
    );

    @Mock
    private RdsClient rdsClient;

    @Mock
    private DescribeDBSubnetGroupsIterable subnetGroupPaginator;

    @Mock
    private DescribeDBInstancesIterable instancePaginator;

    @Test
    void collectsRdsInstanceDbSubnetGroupAndRelationships() {
        var dbSubnetGroup = DBSubnetGroup.builder()
                .dbSubnetGroupName("prod-db-subnets")
                .dbSubnetGroupDescription("prod database subnets")
                .dbSubnetGroupArn("arn:aws:rds:ap-northeast-2:123456789012:subgrp:prod-db-subnets")
                .vpcId("vpc-1234")
                .subnetGroupStatus("Complete")
                .subnets(
                        Subnet.builder().subnetIdentifier("subnet-1234").build(),
                        Subnet.builder().subnetIdentifier("subnet-5678").build()
                )
                .build();

        var dbInstance = DBInstance.builder()
                .dbInstanceIdentifier("prod-db-1")
                .dbInstanceArn("arn:aws:rds:ap-northeast-2:123456789012:db:prod-db-1")
                .engine("postgres")
                .engineVersion("16.3")
                .dbInstanceClass("db.t3.micro")
                .dbInstanceStatus("available")
                .allocatedStorage(20)
                .storageEncrypted(true)
                .multiAZ(false)
                .endpoint(Endpoint.builder().address("prod-db-1.cluster.local").port(5432).build())
                .dbSubnetGroup(dbSubnetGroup)
                .vpcSecurityGroups(
                        VpcSecurityGroupMembership.builder().vpcSecurityGroupId("sg-1234").build(),
                        VpcSecurityGroupMembership.builder().vpcSecurityGroupId("sg-5678").build()
                )
                .build();

        when(rdsClient.describeDBSubnetGroupsPaginator()).thenReturn(subnetGroupPaginator);
        when(rdsClient.describeDBInstancesPaginator()).thenReturn(instancePaginator);
        when(subnetGroupPaginator.dbSubnetGroups()).thenReturn(iterableOf(dbSubnetGroup));
        when(instancePaginator.dbInstances()).thenReturn(iterableOf(dbInstance));
        when(rdsClient.listTagsForResource(any(ListTagsForResourceRequest.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, ListTagsForResourceRequest.class);
            if (request.resourceName().endsWith(":subgrp:prod-db-subnets")) {
                return ListTagsForResourceResponse.builder()
                        .tagList(
                                software.amazon.awssdk.services.rds.model.Tag.builder().key("Name").value("prod-db-subnets").build(),
                                software.amazon.awssdk.services.rds.model.Tag.builder().key("environment").value("prod").build()
                        )
                        .build();
            }

            return ListTagsForResourceResponse.builder()
                    .tagList(
                            software.amazon.awssdk.services.rds.model.Tag.builder().key("Name").value("prod-db-1").build(),
                            software.amazon.awssdk.services.rds.model.Tag.builder().key("environment").value("prod").build()
                    )
                    .build();
        });

        var result = new RdsCollector(rdsClient).collect(CONTEXT);

        assertThat(result.resources()).hasSize(2);
        assertThat(result.relationships()).hasSize(6);

        assertThat(result.resources())
                .extracting(resource -> resource.getResourceType(), resource -> resource.getName())
                .containsExactlyInAnyOrder(
                        tuple("DB_SUBNET_GROUP", "prod-db-subnets"),
                        tuple("RDS", "prod-db-1")
                );

        var rdsProperties = result.resources().stream()
                .filter(resource -> "RDS".equals(resource.getResourceType()))
                .findFirst()
                .orElseThrow()
                .toProperties();
        assertThat(rdsProperties)
                .containsEntry("engine", "postgres")
                .containsEntry("engineVersion", "16.3")
                .containsEntry("instanceClass", "db.t3.micro")
                .containsEntry("status", "available")
                .containsEntry("endpoint", "prod-db-1.cluster.local:5432")
                .containsEntry("storageGb", 20)
                .containsEntry("encrypted", true);

        var subnetGroupProperties = result.resources().stream()
                .filter(resource -> "DB_SUBNET_GROUP".equals(resource.getResourceType()))
                .findFirst()
                .orElseThrow()
                .toProperties();
        assertThat(subnetGroupProperties)
                .containsEntry("groupName", "prod-db-subnets")
                .containsEntry("description", "prod database subnets")
                .containsEntry("status", "Complete")
                .containsEntry("subnetCount", 2);

        assertThat(result.relationships())
                .extracting(relationship -> relationship.type(), relationship -> relationship.targetArn())
                .containsExactlyInAnyOrder(
                        tuple(RelationshipTypes.BELONGS_TO, "arn:aws:ec2:ap-northeast-2:123456789012:vpc/vpc-1234"),
                        tuple(RelationshipTypes.CONTAINS, "arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-1234"),
                        tuple(RelationshipTypes.CONTAINS, "arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-5678"),
                        tuple(RelationshipTypes.IN_SUBNET_GROUP, "arn:aws:rds:ap-northeast-2:123456789012:subgrp:prod-db-subnets"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-5678")
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
