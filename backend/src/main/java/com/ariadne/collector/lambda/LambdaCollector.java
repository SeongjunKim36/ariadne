package com.ariadne.collector.lambda;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.LambdaFunction;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ListTagsRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LambdaCollector extends BaseCollector {

    private final LambdaClient lambdaClient;

    public LambdaCollector(LambdaClient lambdaClient) {
        this.lambdaClient = lambdaClient;
    }

    @Override
    public String resourceType() {
        return "LAMBDA_FUNCTION";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var paginator = withRetry(lambdaClient::listFunctionsPaginator);

        for (var function : paginator.functions()) {
            var functionArn = function.functionArn();
            var tags = fetchTags(functionArn);
            resources.add(new LambdaFunction(
                    functionArn,
                    function.functionName(),
                    inferName(tags, function.functionName()),
                    context.region(),
                    context.accountId(),
                    inferEnvironment(tags),
                    context.collectedAt(),
                    tags,
                    function.runtimeAsString(),
                    function.handler(),
                    function.memorySize(),
                    function.timeout(),
                    function.lastModified(),
                    function.codeSize(),
                    function.stateAsString(),
                    function.packageTypeAsString()
            ));

            var vpcConfig = function.vpcConfig();
            if (vpcConfig != null) {
                for (var subnetId : vpcConfig.subnetIds()) {
                    if (hasText(subnetId)) {
                        relationships.add(GraphRelationship.belongsTo(
                                functionArn,
                                ec2Arn(context, "subnet/" + subnetId)
                        ));
                    }
                }

                for (var securityGroupId : vpcConfig.securityGroupIds()) {
                    if (hasText(securityGroupId)) {
                        relationships.add(new GraphRelationship(
                                functionArn,
                                ec2Arn(context, "security-group/" + securityGroupId),
                                RelationshipTypes.HAS_SG,
                                Map.of()
                        ));
                    }
                }
            }
        }

        return new CollectResult(resources, relationships);
    }

    private Map<String, String> fetchTags(String functionArn) {
        var response = withRetry(() -> lambdaClient.listTags(ListTagsRequest.builder()
                .resource(functionArn)
                .build()));
        return response.hasTags() ? Map.copyOf(new LinkedHashMap<>(response.tags())) : Map.of();
    }
}
