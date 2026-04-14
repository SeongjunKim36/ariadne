package com.ariadne.collector.iam;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.IamRole;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListFunctionsRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class IamRoleCollector extends BaseCollector {

    private static final int ECS_DESCRIBE_BATCH_SIZE = 10;
    private static final String GLOBAL_REGION = "global";

    private final Ec2Client ec2Client;
    private final EcsClient ecsClient;
    private final IamClient iamClient;
    private final LambdaClient lambdaClient;

    public IamRoleCollector(
            Ec2Client ec2Client,
            EcsClient ecsClient,
            IamClient iamClient,
            LambdaClient lambdaClient
    ) {
        this.ec2Client = ec2Client;
        this.ecsClient = ecsClient;
        this.iamClient = iamClient;
        this.lambdaClient = lambdaClient;
    }

    @Override
    public String resourceType() {
        return "IAM_ROLE";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new LinkedHashMap<String, AwsResource>();
        var relationships = new LinkedHashSet<GraphRelationship>();
        var roleArns = new LinkedHashSet<String>();
        var instanceProfileRoleCache = new LinkedHashMap<String, List<String>>();
        var taskDefinitionRoleCache = new LinkedHashMap<String, List<String>>();

        collectEc2Roles(context, roleArns, relationships, instanceProfileRoleCache);
        collectLambdaRoles(roleArns, relationships);
        collectEcsRoles(roleArns, relationships, taskDefinitionRoleCache);

        for (var roleArn : roleArns) {
            var role = fetchRole(context, roleArn);
            if (role != null) {
                resources.put(role.getArn(), role);
            }
        }

        return new CollectResult(
                List.copyOf(resources.values()),
                List.copyOf(relationships)
        );
    }

    private void collectEc2Roles(
            AwsCollectContext context,
            Set<String> roleArns,
            Set<GraphRelationship> relationships,
            Map<String, List<String>> instanceProfileRoleCache
    ) {
        var paginator = withRetry(() -> ec2Client.describeInstancesPaginator(DescribeInstancesRequest.builder().build()));
        for (Reservation reservation : paginator.reservations()) {
            for (var instance : reservation.instances()) {
                if (!hasText(instance.instanceId()) || instance.iamInstanceProfile() == null) {
                    continue;
                }
                var instanceProfileArn = instance.iamInstanceProfile().arn();
                if (!hasText(instanceProfileArn)) {
                    continue;
                }
                var instanceArn = ec2Arn(context, "instance/" + instance.instanceId());
                for (var roleArn : resolveInstanceProfileRoles(instanceProfileArn, instanceProfileRoleCache)) {
                    addRoleRelationship(instanceArn, roleArn, roleArns, relationships);
                }
            }
        }
    }

    private void collectLambdaRoles(
            Set<String> roleArns,
            Set<GraphRelationship> relationships
    ) {
        var paginator = withRetry(() -> lambdaClient.listFunctionsPaginator(ListFunctionsRequest.builder().build()));
        for (var function : paginator.functions()) {
            if (hasText(function.functionArn()) && hasText(function.role())) {
                addRoleRelationship(function.functionArn(), function.role(), roleArns, relationships);
            }
        }
    }

    private void collectEcsRoles(
            Set<String> roleArns,
            Set<GraphRelationship> relationships,
            Map<String, List<String>> taskDefinitionRoleCache
    ) {
        var clusterPaginator = withRetry(ecsClient::listClustersPaginator);
        for (var clusterArn : clusterPaginator.clusterArns()) {
            var servicePaginator = withRetry(() -> ecsClient.listServicesPaginator(ListServicesRequest.builder()
                    .cluster(clusterArn)
                    .build()));
            var serviceArns = new ArrayList<String>();
            for (var serviceArn : servicePaginator.serviceArns()) {
                serviceArns.add(serviceArn);
            }

            for (var serviceBatch : chunked(serviceArns, ECS_DESCRIBE_BATCH_SIZE)) {
                var response = withRetry(() -> ecsClient.describeServices(DescribeServicesRequest.builder()
                        .cluster(clusterArn)
                        .services(serviceBatch)
                        .build()));
                for (var service : response.services()) {
                    if (!hasText(service.serviceArn()) || !hasText(service.taskDefinition())) {
                        continue;
                    }
                    for (var roleArn : resolveTaskDefinitionRoles(service.taskDefinition(), taskDefinitionRoleCache)) {
                        addRoleRelationship(service.serviceArn(), roleArn, roleArns, relationships);
                    }
                }
            }
        }
    }

    private List<String> resolveInstanceProfileRoles(String instanceProfileArn, Map<String, List<String>> cache) {
        var cached = cache.get(instanceProfileArn);
        if (cached != null) {
            return cached;
        }

        var instanceProfileName = nameFromArn(instanceProfileArn, "instance-profile/");
        if (!hasText(instanceProfileName)) {
            cache.put(instanceProfileArn, List.of());
            return List.of();
        }

        var response = withRetry(() -> iamClient.getInstanceProfile(GetInstanceProfileRequest.builder()
                .instanceProfileName(instanceProfileName)
                .build()));

        var resolvedRoles = response.instanceProfile() == null
                ? List.<String>of()
                : response.instanceProfile().roles().stream()
                        .map(software.amazon.awssdk.services.iam.model.Role::arn)
                        .filter(this::hasText)
                        .distinct()
                        .toList();

        cache.put(instanceProfileArn, resolvedRoles);
        return resolvedRoles;
    }

    private List<String> resolveTaskDefinitionRoles(String taskDefinitionReference, Map<String, List<String>> cache) {
        var cached = cache.get(taskDefinitionReference);
        if (cached != null) {
            return cached;
        }

        var response = withRetry(() -> ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(taskDefinitionReference)
                .build()));

        var taskDefinition = response.taskDefinition();
        var resolvedRoles = taskDefinition == null
                ? List.<String>of()
                : List.of(taskDefinition.taskRoleArn(), taskDefinition.executionRoleArn()).stream()
                        .filter(this::hasText)
                        .distinct()
                        .toList();

        cache.put(taskDefinitionReference, resolvedRoles);
        if (taskDefinition != null && hasText(taskDefinition.taskDefinitionArn())) {
            cache.put(taskDefinition.taskDefinitionArn(), resolvedRoles);
        }
        return resolvedRoles;
    }

    private IamRole fetchRole(AwsCollectContext context, String roleArn) {
        var roleName = nameFromArn(roleArn, "role/");
        if (!hasText(roleName)) {
            return null;
        }

        var roleResponse = withRetry(() -> iamClient.getRole(GetRoleRequest.builder()
                .roleName(roleName)
                .build()));
        var role = roleResponse.role();
        if (role == null || !hasText(role.arn())) {
            return null;
        }

        var tags = toTagMap(role.tags(),
                software.amazon.awssdk.services.iam.model.Tag::key,
                software.amazon.awssdk.services.iam.model.Tag::value);
        var attachedPoliciesResponse = withRetry(() -> iamClient.listAttachedRolePolicies(ListAttachedRolePoliciesRequest.builder()
                .roleName(role.roleName())
                .build()));
        var attachedPolicies = attachedPoliciesResponse.attachedPolicies().stream()
                .map(AttachedPolicy::policyName)
                .filter(this::hasText)
                .sorted()
                .toList();

        return new IamRole(
                role.arn(),
                role.roleName(),
                inferName(tags, role.roleName()),
                GLOBAL_REGION,
                context.accountId(),
                inferEnvironment(tags),
                context.collectedAt(),
                tags,
                role.roleName(),
                decodePolicy(role.assumeRolePolicyDocument()),
                attachedPolicies
        );
    }

    private void addRoleRelationship(
            String sourceArn,
            String roleArn,
            Set<String> roleArns,
            Set<GraphRelationship> relationships
    ) {
        if (!hasText(sourceArn) || !hasText(roleArn)) {
            return;
        }
        roleArns.add(roleArn);
        relationships.add(new GraphRelationship(
                sourceArn,
                roleArn,
                RelationshipTypes.HAS_ROLE,
                Map.of()
        ));
    }

    private String nameFromArn(String arn, String prefix) {
        if (!hasText(arn)) {
            return null;
        }
        String candidate = arn;
        var marker = arn.indexOf(prefix);
        if (marker >= 0) {
            candidate = arn.substring(marker + prefix.length());
        }
        var lastSlash = candidate.lastIndexOf('/');
        return lastSlash >= 0 ? candidate.substring(lastSlash + 1) : candidate;
    }

    private String decodePolicy(String assumeRolePolicy) {
        if (!hasText(assumeRolePolicy)) {
            return null;
        }
        return URLDecoder.decode(assumeRolePolicy, StandardCharsets.UTF_8);
    }
}
