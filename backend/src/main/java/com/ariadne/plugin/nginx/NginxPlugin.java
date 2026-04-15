package com.ariadne.plugin.nginx;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.CollectResult;
import com.ariadne.config.AriadneProperties;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.NginxConfig;
import com.ariadne.plugin.CollectorPlugin;
import com.ariadne.plugin.PluginCollectResult;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class NginxPlugin implements CollectorPlugin {

    private static final String SSM_DOCUMENT_NAME = "AWS-RunShellScript";
    private static final int MAX_OUTPUT_BYTES = 1_048_576;
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final AriadneProperties ariadneProperties;
    private final Ec2Client ec2Client;
    private final SsmClient ssmClient;

    public NginxPlugin(AriadneProperties ariadneProperties, Ec2Client ec2Client, SsmClient ssmClient) {
        this.ariadneProperties = ariadneProperties;
        this.ec2Client = ec2Client;
        this.ssmClient = ssmClient;
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
        var resources = new ArrayList<AwsResource>();
        var warnings = new ArrayList<String>();

        for (var candidate : candidates) {
            if (!managedInstances.containsKey(candidate.instanceId())) {
                continue;
            }
            try {
                collectForInstance(context, candidate, nginxProperties).ifPresent(resources::add);
            } catch (RuntimeException exception) {
                warnings.add("Plugin nginx failed for instance %s: %s"
                        .formatted(candidate.instanceId(), exception.getMessage()));
            }
        }

        if (managedInstances.isEmpty()) {
            warnings.add("Plugin nginx skipped: no SSM-managed EC2 instances were found.");
        }

        return new PluginCollectResult(
                Set.of("NGINX_CONFIG"),
                new CollectResult(resources, List.of()),
                warnings
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
                        tags
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

    private java.util.Optional<NginxConfig> collectForInstance(
            AwsCollectContext context,
            Ec2Candidate candidate,
            AriadneProperties.Nginx nginxProperties
    ) {
        var commandId = sendCollectionCommand(candidate.instanceId(), nginxProperties);
        var invocation = waitForInvocation(commandId, candidate.instanceId(), nginxProperties.getSsmTimeoutSeconds());
        if (invocation.status() != CommandInvocationStatus.SUCCESS) {
            throw new IllegalStateException("SSM command finished with status %s"
                    .formatted(invocation.statusAsString()));
        }

        var rawOutput = invocation.standardOutputContent();
        if (rawOutput == null || rawOutput.isBlank()) {
            return java.util.Optional.empty();
        }

        var truncation = truncate(rawOutput);
        return java.util.Optional.of(new NginxConfig(
                nginxConfigArn(context, candidate.instanceId()),
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
                truncation.content(),
                rawOutput.getBytes(StandardCharsets.UTF_8).length,
                truncation.truncated(),
                "ssm-run-command",
                commandId
        ));
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

    private record Ec2Candidate(
            String instanceId,
            String instanceArn,
            String instanceName,
            String environment,
            Map<String, String> tags
    ) {
    }

    private record TruncatedContent(String content, boolean truncated) {
    }
}
