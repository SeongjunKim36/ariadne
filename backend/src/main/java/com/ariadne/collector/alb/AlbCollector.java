package com.ariadne.collector.alb;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.LoadBalancer;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeListenersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

@Component
public class AlbCollector extends BaseCollector {

    private final ElasticLoadBalancingV2Client elbClient;

    public AlbCollector(ElasticLoadBalancingV2Client elbClient) {
        this.elbClient = elbClient;
    }

    @Override
    public String resourceType() {
        return "LOAD_BALANCER";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var paginator = withRetry(elbClient::describeLoadBalancersPaginator);

        for (var loadBalancer : paginator.loadBalancers()) {
            var loadBalancerArn = loadBalancer.loadBalancerArn();
            var tags = fetchTags(loadBalancerArn);
            var targetGroups = targetGroups(loadBalancerArn);
            var listenerSummary = listenerSummary(loadBalancerArn);

            resources.add(new LoadBalancer(
                    loadBalancerArn,
                    loadBalancer.loadBalancerName(),
                    inferName(tags, loadBalancer.loadBalancerName()),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    loadBalancer.typeAsString(),
                    loadBalancer.schemeAsString(),
                    loadBalancer.dnsName(),
                    loadBalancer.state() == null ? null : loadBalancer.state().codeAsString(),
                    listenerSummary.count(),
                    targetGroups.size(),
                    listenerSummary.ports(),
                    listenerSummary.protocols()
            ));

            if (hasText(loadBalancer.vpcId())) {
                relationships.add(GraphRelationship.belongsTo(
                        loadBalancerArn,
                        ec2Arn(context, "vpc/" + loadBalancer.vpcId())
                ));
            }

            for (var securityGroupId : loadBalancer.securityGroups()) {
                if (hasText(securityGroupId)) {
                    relationships.add(new GraphRelationship(
                            loadBalancerArn,
                            ec2Arn(context, "security-group/" + securityGroupId),
                            RelationshipTypes.HAS_SG,
                            Map.of()
                    ));
                }
            }

            for (var targetGroup : targetGroups) {
                var routeProperties = routeProperties(targetGroup.port(), targetGroup.protocolAsString());
                var targetType = targetGroup.targetTypeAsString() == null
                        ? ""
                        : targetGroup.targetTypeAsString().toLowerCase(Locale.ROOT);
                if ("instance".equals(targetType)) {
                    collectInstanceRoutes(context, loadBalancerArn, targetGroup.targetGroupArn(), routeProperties, relationships);
                } else if ("lambda".equals(targetType)) {
                    collectLambdaRoutes(loadBalancerArn, targetGroup.targetGroupArn(), routeProperties, relationships);
                }
            }
        }

        return new CollectResult(resources, relationships);
    }

    private List<software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup> targetGroups(String loadBalancerArn) {
        var paginator = withRetry(() -> elbClient.describeTargetGroupsPaginator(DescribeTargetGroupsRequest.builder()
                .loadBalancerArn(loadBalancerArn)
                .build()));
        var targetGroups = new ArrayList<software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup>();
        for (var targetGroup : paginator.targetGroups()) {
            targetGroups.add(targetGroup);
        }
        return targetGroups;
    }

    private ListenerSummary listenerSummary(String loadBalancerArn) {
        var paginator = withRetry(() -> elbClient.describeListenersPaginator(DescribeListenersRequest.builder()
                .loadBalancerArn(loadBalancerArn)
                .build()));
        int count = 0;
        var ports = new TreeSet<Integer>();
        var protocols = new java.util.LinkedHashSet<String>();
        for (var listener : paginator.listeners()) {
            count++;
            if (listener.port() != null) {
                ports.add(listener.port());
            }
            if (hasText(listener.protocolAsString())) {
                protocols.add(listener.protocolAsString());
            }
        }
        return new ListenerSummary(count, List.copyOf(ports), List.copyOf(protocols));
    }

    private void collectInstanceRoutes(
            AwsCollectContext context,
            String loadBalancerArn,
            String targetGroupArn,
            Map<String, Object> routeProperties,
            List<GraphRelationship> relationships
    ) {
        var response = withRetry(() -> elbClient.describeTargetHealth(DescribeTargetHealthRequest.builder()
                .targetGroupArn(targetGroupArn)
                .build()));
        for (var description : response.targetHealthDescriptions()) {
            var target = description.target();
            if (target != null && hasText(target.id())) {
                relationships.add(new GraphRelationship(
                        loadBalancerArn,
                        ec2Arn(context, "instance/" + target.id()),
                        RelationshipTypes.ROUTES_TO,
                        routeProperties
                ));
            }
        }
    }

    private void collectLambdaRoutes(
            String loadBalancerArn,
            String targetGroupArn,
            Map<String, Object> routeProperties,
            List<GraphRelationship> relationships
    ) {
        var response = withRetry(() -> elbClient.describeTargetHealth(DescribeTargetHealthRequest.builder()
                .targetGroupArn(targetGroupArn)
                .build()));
        for (var description : response.targetHealthDescriptions()) {
            var target = description.target();
            if (target != null && hasText(target.id()) && target.id().startsWith("arn:aws:lambda:")) {
                relationships.add(new GraphRelationship(
                        loadBalancerArn,
                        target.id(),
                        RelationshipTypes.ROUTES_TO,
                        routeProperties
                ));
            }
        }
    }

    private Map<String, String> fetchTags(String loadBalancerArn) {
        var response = withRetry(() -> elbClient.describeTags(DescribeTagsRequest.builder()
                .resourceArns(loadBalancerArn)
                .build()));
        if (response.tagDescriptions().isEmpty()) {
            return Map.of();
        }
        return toTagMap(
                response.tagDescriptions().get(0).tags(),
                software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag::key,
                software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag::value
        );
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

    private record ListenerSummary(int count, List<Integer> ports, List<String> protocols) {
    }
}
