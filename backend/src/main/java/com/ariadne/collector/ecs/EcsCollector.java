package com.ariadne.collector.ecs;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.EcsCluster;
import com.ariadne.graph.node.EcsService;
import com.ariadne.graph.node.EcsTaskDefinition;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.TaskDefinitionField;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EcsCollector extends BaseCollector {

    private static final int ECS_DESCRIBE_BATCH_SIZE = 10;
    private static final String REDACTED = "***REDACTED***";
    private static final List<String> SENSITIVE_ENV_NAME_TOKENS = List.of(
            "password",
            "passwd",
            "secret",
            "token",
            "key",
            "credential",
            "auth"
    );

    private final EcsClient ecsClient;
    private final ElasticLoadBalancingV2Client elbClient;
    private final ObjectMapper objectMapper;

    public EcsCollector(EcsClient ecsClient, ElasticLoadBalancingV2Client elbClient, ObjectMapper objectMapper) {
        this.ecsClient = ecsClient;
        this.elbClient = elbClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String resourceType() {
        return "ECS_CLUSTER";
    }

    @Override
    public Set<String> managedResourceTypes() {
        return Set.of("ECS_CLUSTER", "ECS_SERVICE", "ECS_TASK_DEFINITION");
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var targetGroupRoutes = loadBalancerRoutesByTargetGroup();
        var taskDefinitionCache = new LinkedHashMap<String, ResolvedTaskDefinition>();
        var taskDefinitionArns = new LinkedHashSet<String>();

        var clusterArns = new ArrayList<String>();
        var clusterArnPaginator = withRetry(ecsClient::listClustersPaginator);
        for (var clusterArn : clusterArnPaginator.clusterArns()) {
            clusterArns.add(clusterArn);
        }

        for (var clusterBatch : chunked(clusterArns, 100)) {
            var response = withRetry(() -> ecsClient.describeClusters(DescribeClustersRequest.builder()
                    .clusters(clusterBatch)
                    .build()));
            for (var cluster : response.clusters()) {
                var clusterArn = cluster.clusterArn();
                var tags = fetchTags(clusterArn);
                resources.add(new EcsCluster(
                        clusterArn,
                        cluster.clusterName(),
                        inferName(tags, cluster.clusterName()),
                        context.region(),
                        context.accountId(),
                        inferEnvironment(tags),
                        context.collectedAt(),
                        tags,
                        cluster.status(),
                        cluster.runningTasksCount(),
                        cluster.registeredContainerInstancesCount(),
                        cluster.activeServicesCount()
                ));

                collectServices(context, clusterArn, targetGroupRoutes, taskDefinitionCache, taskDefinitionArns, resources, relationships);
            }
        }

        return new CollectResult(resources, relationships);
    }

    private void collectServices(
            AwsCollectContext context,
            String clusterArn,
            Map<String, RouteInfo> targetGroupRoutes,
            Map<String, ResolvedTaskDefinition> taskDefinitionCache,
            Set<String> taskDefinitionArns,
            List<AwsResource> resources,
            List<GraphRelationship> relationships
    ) {
        var serviceArnPaginator = withRetry(() -> ecsClient.listServicesPaginator(ListServicesRequest.builder()
                .cluster(clusterArn)
                .build()));
        var serviceArns = new ArrayList<String>();
        for (var serviceArn : serviceArnPaginator.serviceArns()) {
            serviceArns.add(serviceArn);
        }

        for (var serviceBatch : chunked(serviceArns, ECS_DESCRIBE_BATCH_SIZE)) {
            var response = withRetry(() -> ecsClient.describeServices(DescribeServicesRequest.builder()
                    .cluster(clusterArn)
                    .services(serviceBatch)
                    .build()));
            for (var service : response.services()) {
                var serviceArn = service.serviceArn();
                var tags = fetchTags(serviceArn);
                var resolvedTaskDefinition = resolveTaskDefinition(context, service.taskDefinition(), taskDefinitionCache);
                resources.add(new EcsService(
                        serviceArn,
                        service.serviceName(),
                        inferName(tags, service.serviceName()),
                        context.region(),
                        context.accountId(),
                        inferEnvironment(tags),
                        context.collectedAt(),
                        tags,
                        service.desiredCount(),
                        service.runningCount(),
                        service.pendingCount(),
                        resolveLaunchType(service),
                        resolvedTaskDefinition == null ? service.taskDefinition() : resolvedTaskDefinition.resource().getResourceId()
                ));

                relationships.add(new GraphRelationship(
                        serviceArn,
                        clusterArn,
                        RelationshipTypes.RUNS_IN,
                        Map.of()
                ));

                if (resolvedTaskDefinition != null) {
                    if (taskDefinitionArns.add(resolvedTaskDefinition.arn())) {
                        resources.add(resolvedTaskDefinition.resource());
                    }
                    relationships.add(new GraphRelationship(
                            serviceArn,
                            resolvedTaskDefinition.arn(),
                            RelationshipTypes.USES_TASK_DEF,
                            Map.of()
                    ));
                }

                var awsvpcConfiguration = service.networkConfiguration() == null
                        ? null
                        : service.networkConfiguration().awsvpcConfiguration();
                if (awsvpcConfiguration != null) {
                    for (var securityGroupId : awsvpcConfiguration.securityGroups()) {
                        if (hasText(securityGroupId)) {
                            relationships.add(new GraphRelationship(
                                    serviceArn,
                                    ec2Arn(context, "security-group/" + securityGroupId),
                                    RelationshipTypes.HAS_SG,
                                    Map.of()
                            ));
                        }
                    }
                }

                for (var loadBalancer : service.loadBalancers()) {
                    if (!hasText(loadBalancer.targetGroupArn())) {
                        continue;
                    }
                    var routeInfo = targetGroupRoutes.get(loadBalancer.targetGroupArn());
                    if (routeInfo == null) {
                        continue;
                    }
                    var properties = routeProperties(loadBalancer.containerPort(), routeInfo.protocol());
                    for (var loadBalancerArn : routeInfo.loadBalancerArns()) {
                        relationships.add(new GraphRelationship(
                                loadBalancerArn,
                                serviceArn,
                                RelationshipTypes.ROUTES_TO,
                                properties
                        ));
                    }
                }
            }
        }
    }

    private Map<String, String> fetchTags(String resourceArn) {
        var response = withRetry(() -> ecsClient.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(resourceArn)
                .build()));
        return toTagMap(response.tags(), software.amazon.awssdk.services.ecs.model.Tag::key, software.amazon.awssdk.services.ecs.model.Tag::value);
    }

    private ResolvedTaskDefinition resolveTaskDefinition(
            AwsCollectContext context,
            String taskDefinitionReference,
            Map<String, ResolvedTaskDefinition> taskDefinitionCache
    ) {
        if (!hasText(taskDefinitionReference)) {
            return null;
        }

        var cached = taskDefinitionCache.get(taskDefinitionReference);
        if (cached != null) {
            return cached;
        }

        var response = withRetry(() -> ecsClient.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(taskDefinitionReference)
                .include(TaskDefinitionField.TAGS)
                .build()));

        var taskDefinition = response.taskDefinition();
        if (taskDefinition == null || !hasText(taskDefinition.taskDefinitionArn())) {
            return null;
        }

        var tags = toTagMap(response.tags(), software.amazon.awssdk.services.ecs.model.Tag::key, software.amazon.awssdk.services.ecs.model.Tag::value);
        var resourceId = taskDefinitionResourceId(taskDefinition);
        var resource = new EcsTaskDefinition(
                taskDefinition.taskDefinitionArn(),
                resourceId,
                inferName(tags, resourceId),
                context.region(),
                context.accountId(),
                inferEnvironment(tags),
                context.collectedAt(),
                tags,
                taskDefinition.family(),
                taskDefinition.revision(),
                taskDefinition.cpu(),
                taskDefinition.memory(),
                taskDefinition.networkModeAsString(),
                taskDefinition.taskRoleArn(),
                taskDefinition.executionRoleArn(),
                String.join(", ", taskDefinition.requiresCompatibilitiesAsStrings()),
                taskDefinition.containerDefinitions() == null ? 0 : taskDefinition.containerDefinitions().size(),
                serializeContainers(taskDefinition.containerDefinitions())
        );
        var resolved = new ResolvedTaskDefinition(taskDefinition.taskDefinitionArn(), resource);
        taskDefinitionCache.put(taskDefinitionReference, resolved);
        taskDefinitionCache.put(taskDefinition.taskDefinitionArn(), resolved);
        if (hasText(resourceId)) {
            taskDefinitionCache.put(resourceId, resolved);
        }
        return resolved;
    }

    private Map<String, RouteInfo> loadBalancerRoutesByTargetGroup() {
        var routes = new LinkedHashMap<String, RouteInfo>();
        var paginator = withRetry(elbClient::describeTargetGroupsPaginator);
        for (var targetGroup : paginator.targetGroups()) {
            routes.put(targetGroup.targetGroupArn(), new RouteInfo(
                    List.copyOf(targetGroup.loadBalancerArns()),
                    targetGroup.protocolAsString()
            ));
        }
        return routes;
    }

    private String resolveLaunchType(software.amazon.awssdk.services.ecs.model.Service service) {
        if (hasText(service.launchTypeAsString())) {
            return service.launchTypeAsString();
        }
        for (var strategyItem : service.capacityProviderStrategy()) {
            if (hasText(strategyItem.capacityProvider())) {
                return strategyItem.capacityProvider().toUpperCase(Locale.ROOT);
            }
        }
        return "UNKNOWN";
    }

    private Map<String, Object> routeProperties(Integer port, String protocol) {
        var properties = new LinkedHashMap<String, Object>();
        if (port != null) {
            properties.put("port", port);
        }
        if (hasText(protocol)) {
            properties.put("protocol", protocol);
        }
        return properties.isEmpty() ? Map.of() : Map.copyOf(properties);
    }

    private String taskDefinitionResourceId(TaskDefinition taskDefinition) {
        if (hasText(taskDefinition.family()) && taskDefinition.revision() != null) {
            return taskDefinition.family() + ":" + taskDefinition.revision();
        }
        var arn = taskDefinition.taskDefinitionArn();
        if (!hasText(arn)) {
            return null;
        }
        var separator = arn.lastIndexOf('/');
        return separator >= 0 ? arn.substring(separator + 1) : arn;
    }

    private String serializeContainers(List<ContainerDefinition> containerDefinitions) {
        var containers = new ArrayList<Map<String, Object>>();
        if (containerDefinitions != null) {
            for (var containerDefinition : containerDefinitions) {
                var container = new LinkedHashMap<String, Object>();
                putIfPresent(container, "name", containerDefinition.name());
                putIfPresent(container, "image", containerDefinition.image());
                putIfPresent(container, "cpu", containerDefinition.cpu());
                putIfPresent(container, "memory", containerDefinition.memory());
                putIfPresent(container, "memoryReservation", containerDefinition.memoryReservation());

                var portMappings = new ArrayList<Map<String, Object>>();
                for (var portMapping : containerDefinition.portMappings()) {
                    var serialized = new LinkedHashMap<String, Object>();
                    putIfPresent(serialized, "containerPort", portMapping.containerPort());
                    putIfPresent(serialized, "hostPort", portMapping.hostPort());
                    putIfPresent(serialized, "protocol", portMapping.protocolAsString());
                    putIfPresent(serialized, "name", portMapping.name());
                    portMappings.add(Map.copyOf(serialized));
                }
                if (!portMappings.isEmpty()) {
                    container.put("portMappings", List.copyOf(portMappings));
                }

                var environment = new ArrayList<Map<String, Object>>();
                for (var pair : containerDefinition.environment()) {
                    var serialized = new LinkedHashMap<String, Object>();
                    putIfPresent(serialized, "name", pair.name());
                    putIfPresent(serialized, "value", redactEnvironmentValue(pair.name(), pair.value()));
                    environment.add(Map.copyOf(serialized));
                }
                if (!environment.isEmpty()) {
                    container.put("environment", List.copyOf(environment));
                }

                containers.add(Map.copyOf(container));
            }
        }

        try {
            return objectMapper.writeValueAsString(containers);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize ECS task definition containers", exception);
        }
    }

    private String redactEnvironmentValue(String key, String value) {
        if (!hasText(value)) {
            return value;
        }
        if (!hasText(key)) {
            return value;
        }
        var normalizedKey = key.toLowerCase(Locale.ROOT);
        for (var token : SENSITIVE_ENV_NAME_TOKENS) {
            if (normalizedKey.contains(token)) {
                return REDACTED;
            }
        }
        return value;
    }

    private void putIfPresent(Map<String, Object> properties, String key, Object value) {
        if (value != null) {
            properties.put(key, value);
        }
    }

    private record RouteInfo(
            List<String> loadBalancerArns,
            String protocol
    ) {
    }

    private record ResolvedTaskDefinition(
            String arn,
            EcsTaskDefinition resource
    ) {
    }
}
