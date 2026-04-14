package com.ariadne.collector.sg;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.CidrSource;
import com.ariadne.graph.node.SecurityGroup;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeManagedPrefixListsRequest;
import software.amazon.awssdk.services.ec2.model.GetManagedPrefixListEntriesRequest;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.ManagedPrefixList;
import software.amazon.awssdk.services.ec2.model.PrefixListEntry;
import software.amazon.awssdk.services.ec2.model.PrefixListId;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SecurityGroupCollector extends BaseCollector {

    private final Ec2Client ec2Client;

    public SecurityGroupCollector(Ec2Client ec2Client) {
        this.ec2Client = ec2Client;
    }

    @Override
    public String resourceType() {
        return "SECURITY_GROUP";
    }

    @Override
    public Set<String> managedResourceTypes() {
        return Set.of("SECURITY_GROUP", "CIDR_SOURCE");
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resourcesByArn = new LinkedHashMap<String, AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var ruleEdges = new LinkedHashMap<RuleEdgeKey, RuleEdgeAccumulator>();
        var prefixListCache = new LinkedHashMap<String, PrefixListMetadata>();
        var paginator = withRetry(() -> ec2Client.describeSecurityGroupsPaginator());

        for (var securityGroup : paginator.securityGroups()) {
            var tags = toTagMap(securityGroup.tags());
            var securityGroupArn = ec2Arn(context, "security-group/" + securityGroup.groupId());
            var fallbackName = hasText(securityGroup.groupName()) ? securityGroup.groupName() : securityGroup.groupId();
            resourcesByArn.put(securityGroupArn, new SecurityGroup(
                    securityGroupArn,
                    securityGroup.groupId(),
                    inferName(tags, fallbackName),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    securityGroup.groupId(),
                    securityGroup.description(),
                    securityGroup.ipPermissions() == null ? 0 : securityGroup.ipPermissions().size(),
                    securityGroup.ipPermissionsEgress() == null ? 0 : securityGroup.ipPermissionsEgress().size()
            ));

            if (hasText(securityGroup.vpcId())) {
                relationships.add(GraphRelationship.belongsTo(
                        securityGroupArn,
                        ec2Arn(context, "vpc/" + securityGroup.vpcId())
                ));
            }

            collectInboundRules(
                    context,
                    securityGroup.groupId(),
                    securityGroupArn,
                    securityGroup.ipPermissions(),
                    resourcesByArn,
                    ruleEdges,
                    prefixListCache
            );
            collectOutboundRules(
                    context,
                    securityGroup.groupId(),
                    securityGroupArn,
                    securityGroup.ipPermissionsEgress(),
                    resourcesByArn,
                    ruleEdges,
                    prefixListCache
            );
        }

        for (var entry : ruleEdges.entrySet()) {
            relationships.add(entry.getValue().toRelationship(entry.getKey()));
        }

        return new CollectResult(new ArrayList<>(resourcesByArn.values()), relationships);
    }

    private void collectInboundRules(
            AwsCollectContext context,
            String securityGroupId,
            String securityGroupArn,
            List<IpPermission> permissions,
            Map<String, AwsResource> resourcesByArn,
            Map<RuleEdgeKey, RuleEdgeAccumulator> ruleEdges,
            Map<String, PrefixListMetadata> prefixListCache
    ) {
        if (permissions == null) {
            return;
        }

        for (var permission : permissions) {
            var port = describePort(permission);
            var protocol = normalizeProtocol(permission.ipProtocol());

            for (var pair : permission.userIdGroupPairs()) {
                if (!hasText(pair.groupId())) {
                    continue;
                }
                if (securityGroupId.equals(pair.groupId())) {
                    addRuleEdge(ruleEdges, securityGroupArn, securityGroupArn, RelationshipTypes.ALLOWS_SELF,
                            "inbound", port, protocol, pair.description(), "security-group", null, null);
                    continue;
                }
                addRuleEdge(
                        ruleEdges,
                        ec2Arn(context, "security-group/" + pair.groupId()),
                        securityGroupArn,
                        RelationshipTypes.ALLOWS_FROM,
                        "inbound",
                        port,
                        protocol,
                        pair.description(),
                        "security-group",
                        null,
                        null
                );
            }

            for (var range : permission.ipRanges()) {
                var cidrArn = addCidrSource(resourcesByArn, context, range.cidrIp());
                addRuleEdge(ruleEdges, cidrArn, securityGroupArn, RelationshipTypes.ALLOWS_TO,
                        "inbound", port, protocol, range.description(), "cidr", null, null);
            }

            for (var range : permission.ipv6Ranges()) {
                var cidrArn = addCidrSource(resourcesByArn, context, range.cidrIpv6());
                addRuleEdge(ruleEdges, cidrArn, securityGroupArn, RelationshipTypes.ALLOWS_TO,
                        "inbound", port, protocol, range.description(), "cidr", null, null);
            }

            for (var prefixList : permission.prefixListIds()) {
                for (var entry : resolvePrefixListEntries(prefixList, prefixListCache)) {
                    var cidrArn = addCidrSource(resourcesByArn, context, entry.cidr());
                    addRuleEdge(ruleEdges, cidrArn, securityGroupArn, RelationshipTypes.ALLOWS_TO,
                            "inbound", port, protocol, firstNonBlank(entry.description(), prefixList.description()),
                            "prefix-list", entry.prefixListId(), entry.prefixListName());
                }
            }
        }
    }

    private void collectOutboundRules(
            AwsCollectContext context,
            String securityGroupId,
            String securityGroupArn,
            List<IpPermission> permissions,
            Map<String, AwsResource> resourcesByArn,
            Map<RuleEdgeKey, RuleEdgeAccumulator> ruleEdges,
            Map<String, PrefixListMetadata> prefixListCache
    ) {
        if (permissions == null) {
            return;
        }

        for (var permission : permissions) {
            var port = describePort(permission);
            var protocol = normalizeProtocol(permission.ipProtocol());

            for (var pair : permission.userIdGroupPairs()) {
                if (!hasText(pair.groupId())) {
                    continue;
                }
                if (securityGroupId.equals(pair.groupId())) {
                    addRuleEdge(ruleEdges, securityGroupArn, securityGroupArn, RelationshipTypes.ALLOWS_SELF,
                            "outbound", port, protocol, pair.description(), "security-group", null, null);
                    continue;
                }
                addRuleEdge(
                        ruleEdges,
                        securityGroupArn,
                        ec2Arn(context, "security-group/" + pair.groupId()),
                        RelationshipTypes.EGRESS_TO,
                        "outbound",
                        port,
                        protocol,
                        pair.description(),
                        "security-group",
                        null,
                        null
                );
            }

            for (var range : permission.ipRanges()) {
                var cidrArn = addCidrSource(resourcesByArn, context, range.cidrIp());
                addRuleEdge(ruleEdges, securityGroupArn, cidrArn, RelationshipTypes.EGRESS_TO,
                        "outbound", port, protocol, range.description(), "cidr", null, null);
            }

            for (var range : permission.ipv6Ranges()) {
                var cidrArn = addCidrSource(resourcesByArn, context, range.cidrIpv6());
                addRuleEdge(ruleEdges, securityGroupArn, cidrArn, RelationshipTypes.EGRESS_TO,
                        "outbound", port, protocol, range.description(), "cidr", null, null);
            }

            for (var prefixList : permission.prefixListIds()) {
                for (var entry : resolvePrefixListEntries(prefixList, prefixListCache)) {
                    var cidrArn = addCidrSource(resourcesByArn, context, entry.cidr());
                    addRuleEdge(ruleEdges, securityGroupArn, cidrArn, RelationshipTypes.EGRESS_TO,
                            "outbound", port, protocol, firstNonBlank(entry.description(), prefixList.description()),
                            "prefix-list", entry.prefixListId(), entry.prefixListName());
                }
            }
        }
    }

    private String addCidrSource(Map<String, AwsResource> resourcesByArn, AwsCollectContext context, String cidr) {
        if (!hasText(cidr)) {
            throw new IllegalArgumentException("CIDR source must not be blank");
        }

        var arn = cidrSourceArn(context, cidr);
        resourcesByArn.computeIfAbsent(arn, ignored -> new CidrSource(
                arn,
                cidr,
                describeCidrLabel(cidr),
                context.region(),
                context.accountId(),
                "shared",
                context.collectedAt(),
                cidr,
                describeCidrLabel(cidr),
                isPublicInternet(cidr),
                describeRiskLevel(cidr),
                cidr.contains(":") ? "ipv6" : "ipv4"
        ));
        return arn;
    }

    private void addRuleEdge(
            Map<RuleEdgeKey, RuleEdgeAccumulator> ruleEdges,
            String sourceArn,
            String targetArn,
            String type,
            String direction,
            String port,
            String protocol,
            String description,
            String sourceKind,
            String prefixListId,
            String prefixListName
    ) {
        var key = new RuleEdgeKey(sourceArn, targetArn, type);
        ruleEdges.computeIfAbsent(key, ignored -> new RuleEdgeAccumulator())
                .add(direction, port, protocol, description, sourceKind, prefixListId, prefixListName);
    }

    private List<ResolvedPrefixListEntry> resolvePrefixListEntries(
            PrefixListId prefixListId,
            Map<String, PrefixListMetadata> prefixListCache
    ) {
        if (!hasText(prefixListId.prefixListId())) {
            return List.of();
        }

        var metadata = prefixListCache.computeIfAbsent(prefixListId.prefixListId(), this::loadPrefixListMetadata);
        return metadata.entries().stream()
                .map(entry -> new ResolvedPrefixListEntry(
                        entry.cidr(),
                        entry.description(),
                        metadata.prefixListId(),
                        metadata.prefixListName()
                ))
                .toList();
    }

    private PrefixListMetadata loadPrefixListMetadata(String prefixListId) {
        var request = DescribeManagedPrefixListsRequest.builder()
                .prefixListIds(prefixListId)
                .build();
        var paginator = withRetry(() -> ec2Client.describeManagedPrefixListsPaginator(request));
        ManagedPrefixList managedPrefixList = null;
        for (var candidate : paginator.prefixLists()) {
            managedPrefixList = candidate;
            break;
        }

        var entriesPaginator = withRetry(() -> ec2Client.getManagedPrefixListEntriesPaginator(
                GetManagedPrefixListEntriesRequest.builder()
                        .prefixListId(prefixListId)
                        .build()
        ));
        var entries = new ArrayList<PrefixListEntry>();
        entriesPaginator.entries().forEach(entries::add);

        return new PrefixListMetadata(
                prefixListId,
                managedPrefixList == null || !hasText(managedPrefixList.prefixListName())
                        ? prefixListId
                        : managedPrefixList.prefixListName(),
                entries
        );
    }

    private String describePort(IpPermission permission) {
        var protocol = normalizeProtocol(permission.ipProtocol());
        if ("all".equals(protocol) || permission.fromPort() == null || permission.toPort() == null) {
            return "all";
        }
        if (protocol.startsWith("icmp")) {
            return "%s:%s".formatted(permission.fromPort(), permission.toPort());
        }
        if (permission.fromPort().equals(permission.toPort())) {
            return String.valueOf(permission.fromPort());
        }
        return permission.fromPort() + "-" + permission.toPort();
    }

    private String normalizeProtocol(String ipProtocol) {
        if (!hasText(ipProtocol) || "-1".equals(ipProtocol)) {
            return "all";
        }
        return ipProtocol.toLowerCase();
    }

    private String describeCidrLabel(String cidr) {
        if (isPublicInternet(cidr)) {
            return "Public Internet";
        }
        if (isPrivateNetwork(cidr)) {
            return "Private Network";
        }
        return "External Network";
    }

    private String describeRiskLevel(String cidr) {
        if (isPublicInternet(cidr)) {
            return "HIGH";
        }
        if (isPrivateNetwork(cidr)) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private boolean isPublicInternet(String cidr) {
        return "0.0.0.0/0".equals(cidr) || "::/0".equals(cidr);
    }

    private boolean isPrivateNetwork(String cidr) {
        return cidr.startsWith("10.")
                || cidr.startsWith("192.168.")
                || cidr.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")
                || cidr.startsWith("fc")
                || cidr.startsWith("fd")
                || cidr.startsWith("fe80:");
    }

    private String cidrSourceArn(AwsCollectContext context, String cidr) {
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(cidr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "arn:aws:ariadne:%s:%s:cidr-source/%s".formatted(context.region(), context.accountId(), encoded);
    }

    private String firstNonBlank(String left, String right) {
        if (hasText(left)) {
            return left;
        }
        if (hasText(right)) {
            return right;
        }
        return null;
    }

    private record RuleEdgeKey(
            String sourceArn,
            String targetArn,
            String type
    ) {
    }

    private static final class RuleEdgeAccumulator {

        private final LinkedHashSet<String> directions = new LinkedHashSet<>();
        private final LinkedHashSet<String> ports = new LinkedHashSet<>();
        private final LinkedHashSet<String> protocols = new LinkedHashSet<>();
        private final LinkedHashSet<String> descriptions = new LinkedHashSet<>();
        private final LinkedHashSet<String> sourceKinds = new LinkedHashSet<>();
        private final LinkedHashSet<String> prefixListIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> prefixListNames = new LinkedHashSet<>();
        private final LinkedHashSet<String> fingerprints = new LinkedHashSet<>();

        void add(
                String direction,
                String port,
                String protocol,
                String description,
                String sourceKind,
                String prefixListId,
                String prefixListName
        ) {
            directions.add(direction);
            ports.add(port);
            protocols.add(protocol);
            if (description != null && !description.isBlank()) {
                descriptions.add(description);
            }
            if (sourceKind != null && !sourceKind.isBlank()) {
                sourceKinds.add(sourceKind);
            }
            if (prefixListId != null && !prefixListId.isBlank()) {
                prefixListIds.add(prefixListId);
            }
            if (prefixListName != null && !prefixListName.isBlank()) {
                prefixListNames.add(prefixListName);
            }
            fingerprints.add(String.join("|",
                    direction,
                    port,
                    protocol,
                    description == null ? "" : description,
                    sourceKind == null ? "" : sourceKind,
                    prefixListId == null ? "" : prefixListId,
                    prefixListName == null ? "" : prefixListName));
        }

        GraphRelationship toRelationship(RuleEdgeKey key) {
            var properties = new LinkedHashMap<String, Object>();
            put(properties, "direction", summarize(directions));
            putList(properties, "directions", directions);
            put(properties, "port", summarize(ports));
            putList(properties, "ports", ports);
            put(properties, "protocol", summarize(protocols));
            putList(properties, "protocols", protocols);
            put(properties, "sourceKind", summarize(sourceKinds));
            putList(properties, "sourceKinds", sourceKinds);
            put(properties, "description", summarize(descriptions));
            putList(properties, "descriptions", descriptions);
            put(properties, "prefixListId", summarize(prefixListIds));
            putList(properties, "prefixListIds", prefixListIds);
            put(properties, "prefixListName", summarize(prefixListNames));
            putList(properties, "prefixListNames", prefixListNames);
            properties.put("ruleCount", fingerprints.size());
            return new GraphRelationship(key.sourceArn(), key.targetArn(), key.type(), properties);
        }

        private void put(Map<String, Object> properties, String key, String value) {
            if (value != null && !value.isBlank()) {
                properties.put(key, value);
            }
        }

        private void putList(Map<String, Object> properties, String key, Set<String> values) {
            if (!values.isEmpty()) {
                properties.put(key, List.copyOf(values));
            }
        }

        private String summarize(Set<String> values) {
            if (values.isEmpty()) {
                return null;
            }
            if (values.size() == 1) {
                return values.iterator().next();
            }
            return String.join(", ", values);
        }
    }

    private record PrefixListMetadata(
            String prefixListId,
            String prefixListName,
            List<PrefixListEntry> entries
    ) {
    }

    private record ResolvedPrefixListEntry(
            String cidr,
            String description,
            String prefixListId,
            String prefixListName
    ) {
    }
}
