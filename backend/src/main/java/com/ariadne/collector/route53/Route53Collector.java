package com.ariadne.collector.route53;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.Route53Zone;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.route53.model.TagResourceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class Route53Collector extends BaseCollector {

    private final Route53Client route53Client;
    private final ElasticLoadBalancingV2Client elbClient;
    private final Ec2Client ec2Client;
    private final RdsClient rdsClient;

    public Route53Collector(
            Route53Client route53Client,
            ElasticLoadBalancingV2Client elbClient,
            Ec2Client ec2Client,
            RdsClient rdsClient
    ) {
        this.route53Client = route53Client;
        this.elbClient = elbClient;
        this.ec2Client = ec2Client;
        this.rdsClient = rdsClient;
    }

    @Override
    public String resourceType() {
        return "ROUTE53_ZONE";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();

        var loadBalancerByDns = loadBalancerByDns();
        var ec2ByAddress = ec2ByAddress(context);
        var rdsByEndpoint = rdsByEndpoint();

        var paginator = withRetry(route53Client::listHostedZonesPaginator);
        for (var hostedZone : paginator.hostedZones()) {
            var zoneId = normalizeHostedZoneId(hostedZone.id());
            var zoneArn = route53ZoneArn(zoneId);
            var domainName = normalizeDns(hostedZone.name());
            var tags = fetchTags(zoneId);

            resources.add(new Route53Zone(
                    zoneArn,
                    zoneId,
                    inferName(tags, domainName),
                    "global",
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    zoneId,
                    domainName,
                    hostedZone.config() != null && Boolean.TRUE.equals(hostedZone.config().privateZone()),
                    hostedZone.resourceRecordSetCount(),
                    hostedZone.linkedService() == null ? null : hostedZone.linkedService().servicePrincipal()
            ));

            var recordPaginator = withRetry(() -> route53Client.listResourceRecordSetsPaginator(ListResourceRecordSetsRequest.builder()
                    .hostedZoneId(hostedZone.id())
                    .build()));

            for (var recordSet : recordPaginator.resourceRecordSets()) {
                var targetArns = new LinkedHashSet<String>();
                if (recordSet.aliasTarget() != null && hasText(recordSet.aliasTarget().dnsName())) {
                    var loadBalancerArn = loadBalancerByDns.get(normalizeDns(recordSet.aliasTarget().dnsName()));
                    if (loadBalancerArn != null) {
                        targetArns.add(loadBalancerArn);
                    }
                }

                for (var resourceRecord : recordSet.resourceRecords()) {
                    var value = normalizeDns(resourceRecord.value());
                    maybeAdd(targetArns, loadBalancerByDns.get(value));
                    maybeAdd(targetArns, ec2ByAddress.get(value));
                    maybeAdd(targetArns, rdsByEndpoint.get(value));
                }

                var relationshipProperties = Map.<String, Object>of(
                        "recordType", recordSet.typeAsString(),
                        "recordName", normalizeDns(recordSet.name())
                );

                for (var targetArn : targetArns) {
                    relationships.add(new GraphRelationship(
                            zoneArn,
                            targetArn,
                            RelationshipTypes.HAS_RECORD,
                            relationshipProperties
                    ));
                }
            }
        }

        return new CollectResult(resources, relationships);
    }

    private Map<String, String> fetchTags(String zoneId) {
        var response = withRetry(() -> route53Client.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceType(TagResourceType.HOSTEDZONE)
                .resourceId(zoneId)
                .build()));
        return response.resourceTagSet() == null
                ? Map.of()
                : toTagMap(
                response.resourceTagSet().tags(),
                software.amazon.awssdk.services.route53.model.Tag::key,
                software.amazon.awssdk.services.route53.model.Tag::value
        );
    }

    private Map<String, String> loadBalancerByDns() {
        var results = new LinkedHashMap<String, String>();
        var paginator = withRetry(elbClient::describeLoadBalancersPaginator);
        for (var loadBalancer : paginator.loadBalancers()) {
            if (hasText(loadBalancer.dnsName())) {
                results.put(normalizeDns(loadBalancer.dnsName()), loadBalancer.loadBalancerArn());
            }
        }
        return results;
    }

    private Map<String, String> ec2ByAddress(AwsCollectContext context) {
        var results = new LinkedHashMap<String, String>();
        var paginator = withRetry(ec2Client::describeInstancesPaginator);
        for (var reservation : paginator.reservations()) {
            for (var instance : reservation.instances()) {
                var instanceArn = ec2Arn(context, "instance/" + instance.instanceId());
                maybePut(results, instance.publicIpAddress(), instanceArn);
                maybePut(results, instance.privateIpAddress(), instanceArn);
                maybePut(results, instance.publicDnsName(), instanceArn);
                maybePut(results, instance.privateDnsName(), instanceArn);
            }
        }
        return results;
    }

    private Map<String, String> rdsByEndpoint() {
        var results = new LinkedHashMap<String, String>();
        var paginator = withRetry(rdsClient::describeDBInstancesPaginator);
        for (var instance : paginator.dbInstances()) {
            if (instance.endpoint() != null && hasText(instance.endpoint().address())) {
                results.put(normalizeDns(instance.endpoint().address()), instance.dbInstanceArn());
            }
        }
        return results;
    }

    private void maybePut(Map<String, String> target, String key, String value) {
        if (hasText(key) && hasText(value)) {
            target.put(normalizeDns(key), value);
        }
    }

    private void maybeAdd(java.util.Set<String> target, String value) {
        if (hasText(value)) {
            target.add(value);
        }
    }

    private String normalizeHostedZoneId(String rawId) {
        if (!hasText(rawId)) {
            return rawId;
        }
        return rawId.startsWith("/hostedzone/")
                ? rawId.substring("/hostedzone/".length())
                : rawId;
    }

    private String normalizeDns(String value) {
        if (!hasText(value)) {
            return value;
        }
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\.$", "");
    }

    private String route53ZoneArn(String zoneId) {
        return "arn:aws:route53:::hostedzone/%s".formatted(zoneId);
    }
}
