package com.ariadne.collector.lambda;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.ListTagsRequest;
import software.amazon.awssdk.services.lambda.model.ListTagsResponse;
import software.amazon.awssdk.services.lambda.model.VpcConfigResponse;
import software.amazon.awssdk.services.lambda.paginators.ListFunctionsIterable;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LambdaCollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-14T00:00:00Z")
    );

    @Mock
    private LambdaClient lambdaClient;

    @Mock
    private ListFunctionsIterable paginator;

    @Test
    void collectsLambdaFunctionAndVpcRelationships() {
        when(lambdaClient.listFunctionsPaginator()).thenReturn(paginator);
        when(paginator.functions()).thenReturn(iterableOf(FunctionConfiguration.builder()
                .functionArn("arn:aws:lambda:ap-northeast-2:123456789012:function:prod-worker")
                .functionName("prod-worker")
                .runtime("java21")
                .handler("com.example.Handler::handleRequest")
                .memorySize(1024)
                .timeout(30)
                .lastModified("2026-04-14T00:00:00.000+0000")
                .codeSize(4096L)
                .state("Active")
                .packageType("Zip")
                .vpcConfig(VpcConfigResponse.builder()
                        .subnetIds("subnet-1234", "subnet-5678")
                        .securityGroupIds("sg-1234")
                        .build())
                .build()));
        when(lambdaClient.listTags(any(ListTagsRequest.class))).thenReturn(ListTagsResponse.builder()
                .tags(java.util.Map.of("Name", "prod-worker", "environment", "prod"))
                .build());

        var result = new LambdaCollector(lambdaClient).collect(CONTEXT);

        assertThat(result.resources()).hasSize(1);
        assertThat(result.relationships()).hasSize(3);

        var properties = result.resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "LAMBDA_FUNCTION")
                .containsEntry("name", "prod-worker")
                .containsEntry("runtime", "java21")
                .containsEntry("handler", "com.example.Handler::handleRequest")
                .containsEntry("memoryMb", 1024)
                .containsEntry("timeoutSeconds", 30)
                .containsEntry("packageType", "Zip");

        assertThat(result.relationships())
                .extracting(relationship -> relationship.type(), relationship -> relationship.targetArn())
                .containsExactlyInAnyOrder(
                        tuple(RelationshipTypes.BELONGS_TO, "arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-1234"),
                        tuple(RelationshipTypes.BELONGS_TO, "arn:aws:ec2:ap-northeast-2:123456789012:subnet/subnet-5678"),
                        tuple(RelationshipTypes.HAS_SG, "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234")
                );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
