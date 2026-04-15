package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Node("NginxConfig")
public final class NginxConfig extends AwsResource {

    private String instanceId;
    private String sourceInstanceArn;
    private String sourceInstanceName;
    private List<String> configPaths;
    private List<String> serverNames;
    private List<String> upstreamNames;
    private List<String> proxyPassTargets;
    private String rawConfig;
    private String upstreams;
    private String proxyPasses;
    private Integer rawConfigBytes;
    private Boolean truncated;
    private String collectionMethod;
    private String commandId;

    public NginxConfig() {
    }

    public NginxConfig(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String instanceId,
            String sourceInstanceArn,
            String sourceInstanceName,
            List<String> configPaths,
            List<String> serverNames,
            List<String> upstreamNames,
            List<String> proxyPassTargets,
            String rawConfig,
            String upstreams,
            String proxyPasses,
            Integer rawConfigBytes,
            Boolean truncated,
            String collectionMethod,
            String commandId
    ) {
        super(arn, resourceId, "NGINX_CONFIG", name, region, accountId, environment, collectedAt, tags);
        this.instanceId = instanceId;
        this.sourceInstanceArn = sourceInstanceArn;
        this.sourceInstanceName = sourceInstanceName;
        this.configPaths = configPaths;
        this.serverNames = serverNames;
        this.upstreamNames = upstreamNames;
        this.proxyPassTargets = proxyPassTargets;
        this.rawConfig = rawConfig;
        this.upstreams = upstreams;
        this.proxyPasses = proxyPasses;
        this.rawConfigBytes = rawConfigBytes;
        this.truncated = truncated;
        this.collectionMethod = collectionMethod;
        this.commandId = commandId;
    }

    @Override
    public String graphLabel() {
        return "NginxConfig";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "instanceId", instanceId);
        put(properties, "sourceInstanceArn", sourceInstanceArn);
        put(properties, "sourceInstanceName", sourceInstanceName);
        put(properties, "configPaths", configPaths);
        put(properties, "serverNames", serverNames);
        put(properties, "upstreamNames", upstreamNames);
        put(properties, "proxyPassTargets", proxyPassTargets);
        put(properties, "rawConfig", rawConfig);
        put(properties, "upstreams", upstreams);
        put(properties, "proxyPasses", proxyPasses);
        put(properties, "rawConfigBytes", rawConfigBytes);
        put(properties, "truncated", truncated);
        put(properties, "collectionMethod", collectionMethod);
        put(properties, "commandId", commandId);
    }
}
