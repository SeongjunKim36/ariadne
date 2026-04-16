package com.ariadne.collector.iam;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfile;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.paginators.ListClustersIterable;
import software.amazon.awssdk.services.ecs.paginators.ListServicesIterable;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionResponse;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.Policy;
import software.amazon.awssdk.services.iam.model.PolicyVersion;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListFunctionsRequest;
import software.amazon.awssdk.services.lambda.paginators.ListFunctionsIterable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamRoleCollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-14T00:00:00Z")
    );

    @Mock
    private Ec2Client ec2Client;

    @Mock
    private EcsClient ecsClient;

    @Mock
    private IamClient iamClient;

    @Mock
    private LambdaClient lambdaClient;

    @Mock
    private DescribeInstancesIterable ec2Paginator;

    @Mock
    private ListFunctionsIterable lambdaPaginator;

    @Mock
    private ListClustersIterable clusterPaginator;

    @Mock
    private ListServicesIterable servicePaginator;

    @Test
    void collectsConnectedIamRolesAcrossComputeServices() {
        var ec2RoleArn = "arn:aws:iam::123456789012:role/service-role/prod-ec2-role";
        var lambdaRoleArn = "arn:aws:iam::123456789012:role/service-role/prod-lambda-role";
        var ecsTaskRoleArn = "arn:aws:iam::123456789012:role/service-role/prod-task-role";
        var ecsExecutionRoleArn = "arn:aws:iam::123456789012:role/service-role/prod-execution-role";
        var instanceProfileArn = "arn:aws:iam::123456789012:instance-profile/prod-app-profile";
        var serviceArn = "arn:aws:ecs:ap-northeast-2:123456789012:service/prod-cluster/prod-api";
        var functionArn = "arn:aws:lambda:ap-northeast-2:123456789012:function:prod-worker";
        var ec2Arn = "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-abc123";

        when(ec2Client.describeInstancesPaginator(any(DescribeInstancesRequest.class))).thenReturn(ec2Paginator);
        when(ec2Paginator.reservations()).thenReturn(iterableOf(Reservation.builder()
                .instances(Instance.builder()
                        .instanceId("i-abc123")
                        .iamInstanceProfile(IamInstanceProfile.builder()
                                .arn(instanceProfileArn)
                                .id("AIP123")
                                .build())
                        .build())
                .build()));

        when(lambdaClient.listFunctionsPaginator(any(ListFunctionsRequest.class))).thenReturn(lambdaPaginator);
        when(lambdaPaginator.functions()).thenReturn(iterableOf(FunctionConfiguration.builder()
                .functionArn(functionArn)
                .functionName("prod-worker")
                .role(lambdaRoleArn)
                .build()));

        when(ecsClient.listClustersPaginator()).thenReturn(clusterPaginator);
        when(clusterPaginator.clusterArns()).thenReturn(iterableOf("arn:aws:ecs:ap-northeast-2:123456789012:cluster/prod-cluster"));
        when(ecsClient.listServicesPaginator(any(ListServicesRequest.class))).thenReturn(servicePaginator);
        when(servicePaginator.serviceArns()).thenReturn(iterableOf(serviceArn));
        when(ecsClient.describeServices(any(DescribeServicesRequest.class))).thenReturn(DescribeServicesResponse.builder()
                .services(Service.builder()
                        .serviceArn(serviceArn)
                        .serviceName("prod-api")
                        .taskDefinition("prod-api:17")
                        .build())
                .build());
        when(ecsClient.describeTaskDefinition(any(DescribeTaskDefinitionRequest.class))).thenReturn(DescribeTaskDefinitionResponse.builder()
                .taskDefinition(TaskDefinition.builder()
                        .taskDefinitionArn("arn:aws:ecs:ap-northeast-2:123456789012:task-definition/prod-api:17")
                        .taskRoleArn(ecsTaskRoleArn)
                        .executionRoleArn(ecsExecutionRoleArn)
                        .build())
                .build());

        when(iamClient.getInstanceProfile(any(GetInstanceProfileRequest.class))).thenReturn(GetInstanceProfileResponse.builder()
                .instanceProfile(software.amazon.awssdk.services.iam.model.InstanceProfile.builder()
                        .instanceProfileName("prod-app-profile")
                        .roles(software.amazon.awssdk.services.iam.model.Role.builder()
                                .arn(ec2RoleArn)
                                .roleName("prod-ec2-role")
                                .build())
                        .build())
                .build());

        when(iamClient.getRole(any(GetRoleRequest.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, GetRoleRequest.class);
            var roleName = request.roleName();
            var roleArn = switch (roleName) {
                case "prod-ec2-role" -> ec2RoleArn;
                case "prod-lambda-role" -> lambdaRoleArn;
                case "prod-task-role" -> ecsTaskRoleArn;
                case "prod-execution-role" -> ecsExecutionRoleArn;
                default -> throw new IllegalArgumentException("Unexpected role name " + roleName);
            };
            return GetRoleResponse.builder()
                    .role(software.amazon.awssdk.services.iam.model.Role.builder()
                            .arn(roleArn)
                            .roleName(roleName)
                            .assumeRolePolicyDocument("%7B%22Version%22%3A%222012-10-17%22%7D")
                            .tags(software.amazon.awssdk.services.iam.model.Tag.builder()
                                    .key("environment")
                                    .value("prod")
                                    .build())
                            .build())
                    .build();
        });

        when(iamClient.listAttachedRolePolicies(any(ListAttachedRolePoliciesRequest.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, ListAttachedRolePoliciesRequest.class);
            return software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesResponse.builder()
                    .attachedPolicies(AttachedPolicy.builder()
                            .policyName("ManagedPolicyFor-" + request.roleName())
                            .policyArn("arn:aws:iam::aws:policy/ManagedPolicyFor-" + request.roleName())
                            .build())
                    .build();
        });
        when(iamClient.getPolicy(any(GetPolicyRequest.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, GetPolicyRequest.class);
            return GetPolicyResponse.builder()
                    .policy(Policy.builder()
                            .arn(request.policyArn())
                            .defaultVersionId("v1")
                            .build())
                    .build();
        });
        when(iamClient.getPolicyVersion(any(GetPolicyVersionRequest.class))).thenReturn(GetPolicyVersionResponse.builder()
                .policyVersion(PolicyVersion.builder()
                        .document("%7B%22Version%22%3A%222012-10-17%22,%22Statement%22:%5B%7B%22Action%22:%5B%22s3:GetObject%22%5D,%22Resource%22:%5B%22arn:aws:s3:::example-bucket/*%22%5D%7D%5D%7D")
                        .build())
                .build());
        when(iamClient.listRolePolicies(any(ListRolePoliciesRequest.class))).thenReturn(ListRolePoliciesResponse.builder()
                .policyNames(List.of())
                .build());

        var result = new IamRoleCollector(ec2Client, ecsClient, iamClient, lambdaClient).collect(CONTEXT);

        assertThat(result.resources()).hasSize(4);
        assertThat(result.relationships()).hasSize(4);
        assertThat(result.resources())
                .extracting(resource -> resource.getResourceType(), resource -> resource.getResourceId())
                .containsExactlyInAnyOrder(
                        tuple("IAM_ROLE", "prod-ec2-role"),
                        tuple("IAM_ROLE", "prod-lambda-role"),
                        tuple("IAM_ROLE", "prod-task-role"),
                        tuple("IAM_ROLE", "prod-execution-role")
                );

        var lambdaRoleProperties = result.resources().stream()
                .filter(resource -> "prod-lambda-role".equals(resource.getResourceId()))
                .findFirst()
                .orElseThrow()
                .toProperties();

        assertThat(lambdaRoleProperties)
                .containsEntry("roleName", "prod-lambda-role")
                .containsEntry("environment", "prod")
                .containsEntry("assumeRolePolicy", "{\"Version\":\"2012-10-17\"}")
                .containsEntry("attachedPolicies", List.of("ManagedPolicyFor-prod-lambda-role"));

        assertThat(result.relationships())
                .extracting(GraphRelationship::sourceArn, GraphRelationship::targetArn, GraphRelationship::type)
                .containsExactlyInAnyOrder(
                        tuple(ec2Arn, ec2RoleArn, RelationshipTypes.HAS_ROLE),
                        tuple(functionArn, lambdaRoleArn, RelationshipTypes.HAS_ROLE),
                        tuple(serviceArn, ecsTaskRoleArn, RelationshipTypes.HAS_ROLE),
                        tuple(serviceArn, ecsExecutionRoleArn, RelationshipTypes.HAS_ROLE)
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
