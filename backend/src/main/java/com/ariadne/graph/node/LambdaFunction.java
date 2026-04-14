package com.ariadne.graph.node;

import org.springframework.data.neo4j.core.schema.Node;

import java.time.OffsetDateTime;
import java.util.Map;

@Node("LambdaFunction")
public final class LambdaFunction extends AwsResource {

    private String runtime;
    private String handler;
    private Integer memoryMb;
    private Integer timeoutSeconds;
    private String lastModified;
    private Long codeSize;
    private String state;
    private String packageType;

    public LambdaFunction() {
    }

    public LambdaFunction(
            String arn,
            String resourceId,
            String name,
            String region,
            String accountId,
            String environment,
            OffsetDateTime collectedAt,
            Map<String, String> tags,
            String runtime,
            String handler,
            Integer memoryMb,
            Integer timeoutSeconds,
            String lastModified,
            Long codeSize,
            String state,
            String packageType
    ) {
        super(arn, resourceId, "LAMBDA_FUNCTION", name, region, accountId, environment, collectedAt, tags);
        this.runtime = runtime;
        this.handler = handler;
        this.memoryMb = memoryMb;
        this.timeoutSeconds = timeoutSeconds;
        this.lastModified = lastModified;
        this.codeSize = codeSize;
        this.state = state;
        this.packageType = packageType;
    }

    @Override
    public String graphLabel() {
        return "LambdaFunction";
    }

    @Override
    protected void addTypeSpecificProperties(Map<String, Object> properties) {
        put(properties, "runtime", runtime);
        put(properties, "handler", handler);
        put(properties, "memoryMb", memoryMb);
        put(properties, "timeoutSeconds", timeoutSeconds);
        put(properties, "lastModified", lastModified);
        put(properties, "codeSize", codeSize);
        put(properties, "state", state);
        put(properties, "packageType", packageType);
    }
}
