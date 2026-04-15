package com.ariadne.plugin.nginx;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.CollectResult;
import com.ariadne.config.AriadneProperties;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.NginxConfig;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import com.ariadne.plugin.CollectorPlugin;
import com.ariadne.plugin.PluginCollectResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InstanceInformation;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import software.amazon.awssdk.services.ssm.model.PingStatus;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class NginxPlugin implements CollectorPlugin {

    private static final String SSM_DOCUMENT_NAME = "AWS-RunShellScript";
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final AriadneProperties ariadneProperties;
    private final Ec2Client ec2Client;
    private final ElasticLoadBalancingV2Client elbClient;
    private final SsmClient ssmClient;
    private final NginxConfigParser nginxConfigParser;
    private final ObjectMapper objectMapper;

    public NginxPlugin(
            AriadneProperties ariadneProperties,
            Ec2Client ec2Client,
            ElasticLoadBalancingV2Client elbClient,
            SsmClient ssmClient,
            NginxConfigParser nginxConfigParser,
            ObjectMapper objectMapper
    ) {
        this.ariadneProperties = ariadneProperties;
        this.ec2Client = ec2Client;
        this.elbClient = elbClient;
        this.ssmClient = ssmClient;
        this.nginxConfigParser = nginxConfigParser;
        this.objectMapper = objectMapper;
    }

    @Override
    public String pluginId() {
        return "nginx";
    }

    @Override
    public boolean enabled() {
        return ariadneProperties.getPlugins().getNginx().isEnabled();
    }

    @Override
    public PluginCollectResult collect(AwsCollectContext context) {
        if (!enabled()) {
            return PluginCollectResult.empty();
        }

        var nginxProperties = ariadneProperties.getPlugins().getNginx();
        var candidates = listEc2Candidates(context);
        if (candidates.isEmpty()) {
            return new PluginCollectResult(
                    Set.of("NGINX_CONFIG"),
                    CollectResult.empty(),
                    List.of()
            );
        }

        var managedInstances = listManagedEc2Instances();
        var targetInventory = buildTargetInventory(candidates);
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var warnings = new LinkedHashSet<String>();

        for (var candidate : candidates) {
            if (!managedInstances.containsKey(candidate.instanceId())) {
                continue;
            }
            try {
                collectForInstance(context, candidate, nginxProperties, targetInventory).ifPresent(outcome -> {
                    resources.add(outcome.resource());
                    relationships.addAll(outcome.relationships());
                    warnings.addAll(outcome.warnings());
                });
            } catch (RuntimeException exception) {
                warnings.add(NginxPluginGuidance.scanFailureMessage(candidate.instanceId(), exception));
            }
        }

        if (managedInstances.isEmpty()) {
            warnings.add(NginxPluginGuidance.missingManagedInstanceMessage());
        }

        return new PluginCollectResult(
                Set.of("NGINX_CONFIG"),
                new CollectResult(resources, relationships),
                List.copyOf(warnings)
        );
    }

    private List<Ec2Candidate> listEc2Candidates(AwsCollectContext context) {
        var candidates = new ArrayList<Ec2Candidate>();
        DescribeInstancesIterable paginator = withRetry(ec2Client::describeInstancesPaginator);
        for (Reservation reservation : paginator.reservations()) {
            for (var instance : reservation.instances()) {
                if (instance.state() != null && instance.state().name() != null
                        && instance.state().name().toString().equalsIgnoreCase("terminated")) {
                    continue;
                }

                var tags = toTagMap(instance.tags());
                candidates.add(new Ec2Candidate(
                        instance.instanceId(),
                        ec2Arn(context, "instance/" + instance.instanceId()),
                        inferName(tags, instance.instanceId()),
                        inferEnvironment(tags),
                        tags,
                        normalizeHost(instance.privateIpAddress()),
                        normalizeHost(instance.publicIpAddress())
                ));
            }
        }
        return candidates;
    }

    private Map<String, InstanceInformation> listManagedEc2Instances() {
        var managedInstances = new LinkedHashMap<String, InstanceInformation>();
        var paginator = withRetry(() -> ssmClient.describeInstanceInformationPaginator(
                DescribeInstanceInformationRequest.builder().build()
        ));

        for (var instanceInformation : paginator.instanceInformationList()) {
            if (!"EC2INSTANCE".equalsIgnoreCase(instanceInformation.resourceTypeAsString())) {
                continue;
            }
            if (instanceInformation.pingStatus() != PingStatus.ONLINE) {
                continue;
            }
            managedInstances.put(instanceInformation.instanceId(), instanceInformation);
        }
        return managedInstances;
    }

    private Optional<NginxCollectionOutcome> collectForInstanceInternal(
            AwsCollectContext context,
            Ec2Candidate candidate,
            AriadneProperties.Nginx nginxProperties,
            TargetInventory targetInventory
    ) {
        var commandId = sendCollectionCommand(candidate.instanceId(), nginxProperties);
        var invocation = waitForInvocation(commandId, candidate.instanceId(), nginxProperties.getSsmTimeoutSeconds());
        if (invocation.status() != CommandInvocationStatus.SUCCESS) {
            throw new IllegalStateException("SSM command finished with status %s"
                    .formatted(invocation.statusAsString()));
        }

        var rawOutput = invocation.standardOutputContent();
        if (rawOutput == null || rawOutput.isBlank()) {
            return Optional.empty();
        }

        var truncation = truncate(rawOutput);
        var parsedConfig = nginxConfigParser.parse(truncation.content());
        var nginxConfigArn = nginxConfigArn(context, candidate.instanceId());
        var relationships = buildRelationships(candidate, nginxConfigArn, parsedConfig, targetInventory);
        var unresolvedTargets = countUnresolvedTargets(candidate, parsedConfig, targetInventory);
        var warnings = unresolvedTargets == 0
                ? List.<String>of()
                : List.of("Plugin nginx could not map %s proxy targets on instance %s to known AWS resources."
                .formatted(unresolvedTargets, candidate.instanceId()));

        return Optional.of(new NginxCollectionOutcome(
                new NginxConfig(
                        nginxConfigArn,
                        candidate.instanceId() + ":nginx-config",
                        candidate.instanceName() + " nginx config",
                        context.region(),
                        context.accountId(),
                        candidate.environment(),
                        context.collectedAt(),
                        candidate.tags(),
                        candidate.instanceId(),
                        candidate.instanceArn(),
                        candidate.instanceName(),
                        nginxProperties.getConfigPaths(),
                        parsedConfig.serverNames(),
                        parsedConfig.upstreamNames(),
                        parsedConfig.proxyPassTargets(),
                        truncation.content(),
                        toJson(parsedConfig.upstreams()),
                        toJson(parsedConfig.proxyPasses()),
                        rawOutput.getBytes(StandardCharsets.UTF_8).length,
                        truncation.truncated(),
                        "ssm-run-command",
                        commandId
                ),
                relationships,
                warnings
        ));
    }

    private Optional<NginxCollectionOutcome> collectForInstance(
            AwsCollectContext context,
            Ec2Candidate candidate,
            AriadneProperties.Nginx nginxProperties,
            TargetInventory targetInventory
    ) {
        return collectForInstanceInternal(context, candidate, nginxProperties, targetInventory);
    }

    private String sendCollectionCommand(String instanceId, AriadneProperties.Nginx nginxProperties) {
        var request = SendCommandRequest.builder()
                .documentName(SSM_DOCUMENT_NAME)
                .comment("Ariadne nginx config collection")
                .instanceIds(instanceId)
                .timeoutSeconds(nginxProperties.getSsmTimeoutSeconds())
                .parameters(Map.of("commands", List.of(buildCollectionCommand(nginxProperties.getConfigPaths()))))
                .build();
        return withRetry(() -> ssmClient.sendCommand(request))
                .command()
                .commandId();
    }

    private GetCommandInvocationResponse waitForInvocation(String commandId, String instanceId, int timeoutSeconds) {
        var deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds + 5L).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var invocation = withRetry(() -> ssmClient.getCommandInvocation(GetCommandInvocationRequest.builder()
                        .commandId(commandId)
                        .instanceId(instanceId)
                        .build()));
                if (invocation.status() == CommandInvocationStatus.SUCCESS || isTerminalFailure(invocation.status())) {
                    return invocation;
                }
            } catch (InvocationDoesNotExistException ignored) {
                // SSM can lag briefly before the invocation becomes visible.
            }
            sleepQuietly(POLL_INTERVAL);
        }
        throw new IllegalStateException("timed out waiting for SSM command result");
    }

    private boolean isTerminalFailure(CommandInvocationStatus status) {
        if (status == null) {
            return false;
        }
        var statusName = status.toString().toUpperCase(java.util.Locale.ROOT);
        return Set.of(
                "CANCELLED",
                "TIMEDOUT",
                "FAILED",
                "CANCELLING",
                "DELIVERYTIMEDOUT",
                "EXECUTIONTIMEDOUT",
                "UNDELIVERABLE",
                "TERMINATED",
                "INVALIDPLATFORM",
                "ACCESSDENIED"
        ).contains(statusName);
    }

    private TruncatedContent truncate(String rawOutput) {
        var bytes = rawOutput.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return new TruncatedContent(rawOutput, false);
        }

        var endIndex = rawOutput.length();
        while (endIndex > 0
                && rawOutput.substring(0, endIndex).getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            endIndex--;
        }
        return new TruncatedContent(rawOutput.substring(0, endIndex), true);
    }

    private String buildCollectionCommand(List<String> configPaths) {
        var script = new StringBuilder("set +e\n");
        for (var configPath : configPaths) {
            script.append("if [ -d ")
                    .append(shellQuote(configPath))
                    .append(" ]; then\n")
                    .append("  find ")
                    .append(shellQuote(configPath))
                    .append(" -type f -name '*.conf' | sort | while read -r file; do\n")
                    .append("    printf '\\n# ARIADNE FILE: %s\\n' \"$file\"\n")
                    .append("    cat \"$file\"\n")
                    .append("    printf '\\n'\n")
                    .append("  done\n")
                    .append("elif [ -f ")
                    .append(shellQuote(configPath))
                    .append(" ]; then\n")
                    .append("  printf '\\n# ARIADNE FILE: %s\\n' ")
                    .append(shellQuote(configPath))
                    .append("\n")
                    .append("  cat ")
                    .append(shellQuote(configPath))
                    .append("\n")
                    .append("  printf '\\n'\n")
                    .append("fi\n");
        }
        return script.toString();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String nginxConfigArn(AwsCollectContext context, String instanceId) {
        return "arn:aws:ariadne:%s:%s:nginx-config/%s".formatted(context.region(), context.accountId(), instanceId);
    }

    private Map<String, String> toTagMap(List<Tag> tags) {
        var tagMap = new LinkedHashMap<String, String>();
        if (tags == null) {
            return tagMap;
        }
        for (var tag : tags) {
            tagMap.put(tag.key(), tag.value());
        }
        return tagMap;
    }

    private String inferName(Map<String, String> tags, String fallback) {
        var value = tags.get("Name");
        return value == null || value.isBlank() ? fallback : value;
    }

    private String inferEnvironment(Map<String, String> tags) {
        for (var key : List.of("environment", "Environment", "env", "Env")) {
            var value = tags.get(key);
            if (value != null && !value.isBlank()) {
                return value.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "unknown";
    }

    private String ec2Arn(AwsCollectContext context, String resourcePath) {
        return "arn:aws:ec2:%s:%s:%s".formatted(context.region(), context.accountId(), resourcePath);
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("nginx plugin wait was interrupted", interruptedException);
        }
    }

    private <T> T withRetry(Supplier<T> supplier) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return supplier.get();
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isThrottle(exception) || attempt == 3) {
                    throw exception;
                }
                sleepQuietly(Duration.ofMillis(200L * attempt));
            }
        }
        throw lastFailure;
    }

    private boolean isThrottle(RuntimeException exception) {
        if (!(exception instanceof AwsServiceException awsException)) {
            return false;
        }
        var errorCode = awsException.awsErrorDetails() == null
                ? ""
                : awsException.awsErrorDetails().errorCode();
        return errorCode != null && errorCode.toLowerCase(java.util.Locale.ROOT).contains("throttl");
    }

    private TargetInventory buildTargetInventory(List<Ec2Candidate> candidates) {
        var inventory = new TargetInventory();

        for (var candidate : candidates) {
            inventory.registerEc2(candidate);
        }

        var paginator = withRetry(elbClient::describeLoadBalancersPaginator);
        for (var loadBalancer : paginator.loadBalancers()) {
            if (!hasText(loadBalancer.loadBalancerArn())) {
                continue;
            }
            inventory.registerLoadBalancer(new NamedTarget(
                    loadBalancer.loadBalancerArn(),
                    "LOAD_BALANCER",
                    normalizeHost(loadBalancer.loadBalancerName()),
                    normalizeHost(loadBalancer.dnsName())
            ));
        }

        return inventory;
    }

    private List<GraphRelationship> buildRelationships(
            Ec2Candidate source,
            String nginxConfigArn,
            NginxConfigParser.ParsedNginxConfig parsedConfig,
            TargetInventory targetInventory
    ) {
        var relationships = new ArrayList<GraphRelationship>();
        relationships.add(buildRunsNginxRelationship(source.instanceArn(), nginxConfigArn, parsedConfig.serverNames()));

        var proxyRelationships = new LinkedHashMap<String, ProxyRelationshipAccumulator>();
        var upstreamByName = new HashMap<String, NginxConfigParser.ParsedUpstream>();
        for (var upstream : parsedConfig.upstreams()) {
            upstreamByName.put(upstream.name(), upstream);
        }

        for (var proxyPass : parsedConfig.proxyPasses()) {
            if (hasText(proxyPass.upstreamName())) {
                var upstream = upstreamByName.get(proxyPass.upstreamName());
                if (upstream != null) {
                    for (var server : upstream.servers()) {
                        resolveTargetArn(source, server.host(), targetInventory)
                                .ifPresent(targetArn -> proxyRelationships
                                        .computeIfAbsent(targetArn, ProxyRelationshipAccumulator::new)
                                        .record(proxyPass, server.port(), server.host(), proxyPass.upstreamName()));
                    }
                }
            } else {
                resolveTargetArn(source, proxyPass.host(), targetInventory)
                        .ifPresent(targetArn -> proxyRelationships
                                .computeIfAbsent(targetArn, ProxyRelationshipAccumulator::new)
                                .record(proxyPass, proxyPass.port(), proxyPass.host(), null));
            }
        }

        proxyRelationships.values().stream()
                .sorted(Comparator.comparing(ProxyRelationshipAccumulator::targetArn))
                .map(accumulator -> accumulator.toRelationship(nginxConfigArn))
                .forEach(relationships::add);
        return relationships;
    }

    private int countUnresolvedTargets(
            Ec2Candidate source,
            NginxConfigParser.ParsedNginxConfig parsedConfig,
            TargetInventory targetInventory
    ) {
        var unresolved = 0;
        var upstreamByName = new HashMap<String, NginxConfigParser.ParsedUpstream>();
        for (var upstream : parsedConfig.upstreams()) {
            upstreamByName.put(upstream.name(), upstream);
        }

        for (var proxyPass : parsedConfig.proxyPasses()) {
            if (hasText(proxyPass.upstreamName())) {
                var upstream = upstreamByName.get(proxyPass.upstreamName());
                if (upstream == null) {
                    unresolved++;
                    continue;
                }
                var resolvedAny = false;
                for (var server : upstream.servers()) {
                    if (resolveTargetArn(source, server.host(), targetInventory).isPresent()) {
                        resolvedAny = true;
                    }
                }
                if (!resolvedAny) {
                    unresolved++;
                }
            } else if (resolveTargetArn(source, proxyPass.host(), targetInventory).isEmpty()) {
                unresolved++;
            }
        }

        return unresolved;
    }

    private GraphRelationship buildRunsNginxRelationship(String sourceArn, String nginxConfigArn, List<String> serverNames) {
        if (serverNames == null || serverNames.isEmpty()) {
            return new GraphRelationship(sourceArn, nginxConfigArn, RelationshipTypes.RUNS_NGINX, Map.of());
        }

        var properties = new LinkedHashMap<String, Object>();
        properties.put("serverName", serverNames.get(0));
        properties.put("serverNames", serverNames);
        return new GraphRelationship(sourceArn, nginxConfigArn, RelationshipTypes.RUNS_NGINX, properties);
    }

    private Optional<String> resolveTargetArn(Ec2Candidate source, String host, TargetInventory targetInventory) {
        var normalizedHost = normalizeHost(host);
        if (!hasText(normalizedHost)) {
            return Optional.empty();
        }
        if (isLoopback(normalizedHost)) {
            return Optional.of(source.instanceArn());
        }

        var directEc2 = targetInventory.ec2Targets().get(normalizedHost);
        if (directEc2 != null) {
            return Optional.of(directEc2.arn());
        }

        var loadBalancer = targetInventory.loadBalancerTargets().get(normalizedHost);
        if (loadBalancer != null) {
            return Optional.of(loadBalancer.arn());
        }

        return Optional.empty();
    }

    private String normalizeHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isLoopback(String host) {
        return "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "localhost".equals(host);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize nginx parse result", exception);
        }
    }

    private record Ec2Candidate(
            String instanceId,
            String instanceArn,
            String instanceName,
            String environment,
            Map<String, String> tags,
            String privateIp,
            String publicIp
    ) {
    }

    private record TruncatedContent(String content, boolean truncated) {
    }

    private record NamedTarget(
            String arn,
            String resourceType,
            String name,
            String address
    ) {
    }

    private record NginxCollectionOutcome(
            NginxConfig resource,
            List<GraphRelationship> relationships,
            List<String> warnings
    ) {
    }

    private static final class TargetInventory {

        private final Map<String, NamedTarget> ec2Targets = new LinkedHashMap<>();
        private final Map<String, NamedTarget> loadBalancerTargets = new LinkedHashMap<>();

        void registerEc2(Ec2Candidate candidate) {
            register(ec2Targets, candidate.instanceId(), new NamedTarget(candidate.instanceArn(), "EC2", candidate.instanceName(), candidate.privateIp()));
            register(ec2Targets, candidate.instanceName(), new NamedTarget(candidate.instanceArn(), "EC2", candidate.instanceName(), candidate.privateIp()));
            register(ec2Targets, candidate.privateIp(), new NamedTarget(candidate.instanceArn(), "EC2", candidate.instanceName(), candidate.privateIp()));
            register(ec2Targets, candidate.publicIp(), new NamedTarget(candidate.instanceArn(), "EC2", candidate.instanceName(), candidate.publicIp()));
        }

        void registerLoadBalancer(NamedTarget target) {
            register(loadBalancerTargets, target.name(), target);
            register(loadBalancerTargets, target.address(), target);
        }

        Map<String, NamedTarget> ec2Targets() {
            return ec2Targets;
        }

        Map<String, NamedTarget> loadBalancerTargets() {
            return loadBalancerTargets;
        }

        private void register(Map<String, NamedTarget> index, String key, NamedTarget target) {
            if (key == null || key.isBlank() || target == null) {
                return;
            }
            index.putIfAbsent(key, target);
        }
    }

    private static final class ProxyRelationshipAccumulator {

        private final String targetArn;
        private final LinkedHashSet<String> upstreams = new LinkedHashSet<>();
        private final LinkedHashSet<String> proxyPassTargets = new LinkedHashSet<>();
        private final LinkedHashSet<String> hosts = new LinkedHashSet<>();
        private final LinkedHashSet<Integer> ports = new LinkedHashSet<>();

        private ProxyRelationshipAccumulator(String targetArn) {
            this.targetArn = targetArn;
        }

        String targetArn() {
            return targetArn;
        }

        void record(NginxConfigParser.ParsedProxyPass proxyPass, Integer port, String host, String upstream) {
            if (proxyPass != null && proxyPass.rawValue() != null) {
                proxyPassTargets.add(proxyPass.rawValue());
            }
            if (host != null && !host.isBlank()) {
                hosts.add(host);
            }
            if (port != null) {
                ports.add(port);
            }
            if (upstream != null && !upstream.isBlank()) {
                upstreams.add(upstream);
            }
        }

        GraphRelationship toRelationship(String sourceArn) {
            var properties = new LinkedHashMap<String, Object>();
            if (!upstreams.isEmpty()) {
                var upstreamList = List.copyOf(upstreams);
                properties.put("upstream", upstreamList.get(0));
                properties.put("upstreams", upstreamList);
            }
            if (!ports.isEmpty()) {
                var portList = List.copyOf(ports);
                properties.put("port", portList.get(0));
                properties.put("ports", portList);
            }
            if (!proxyPassTargets.isEmpty()) {
                properties.put("proxyPassTargets", List.copyOf(proxyPassTargets));
            }
            if (!hosts.isEmpty()) {
                properties.put("hosts", List.copyOf(hosts));
            }
            return new GraphRelationship(sourceArn, targetArn, RelationshipTypes.PROXIES_TO, properties);
        }
    }
}
