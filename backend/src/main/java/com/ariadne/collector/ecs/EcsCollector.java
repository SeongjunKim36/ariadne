package com.ariadne.collector.ecs;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.EcsCluster;
import com.ariadne.graph.node.EcsService;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class EcsCollector extends BaseCollector {

    private static final int ECS_DESCRIBE_BATCH_SIZE = 10;

    private final EcsClient ecsClient;
    private final ElasticLoadBalancingV2Client elbClient;

    public EcsCollector(EcsClient ecsClient, ElasticLoadBalancingV2Client elbClient) {
        this.ecsClient = ecsClient;
        this.elbClient = elbClient;
    }

    @Override
    public String resourceType() {
        return "ECS_CLUSTER";
    }

    @Override
    public Set<String> managedResourceTypes() {
        return Set.of("ECS_CLUSTER", "ECS_SERVICE");
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var targetGroupRoutes = loadBalancerRoutesByTargetGroup();

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

                collectServices(context, clusterArn, targetGroupRoutes, resources, relationships);
            }
        }

        return new CollectResult(resources, relationships);
    }

    private void collectServices(
            AwsCollectContext context,
            String clusterArn,
            Map<String, RouteInfo> targetGroupRoutes,
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
                        service.taskDefinition()
                ));

                relationships.add(new GraphRelationship(
                        serviceArn,
                        clusterArn,
                        RelationshipTypes.RUNS_IN,
                        Map.of()
                ));

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

    private record RouteInfo(
            List<String> loadBalancerArns,
            String protocol
    ) {
    }
}
