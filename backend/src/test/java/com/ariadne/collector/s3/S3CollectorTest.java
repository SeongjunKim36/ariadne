package com.ariadne.collector.s3;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.LambdaFunctionConfiguration;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3CollectorTest {

    private static final AwsCollectContext CONTEXT = new AwsCollectContext(
            "123456789012",
            "ap-northeast-2",
            OffsetDateTime.parse("2026-04-14T00:00:00Z")
    );

    @Mock
    private S3Client s3Client;

    @Test
    void collectsS3BucketAndLambdaTriggerRelationship() {
        when(s3Client.listBuckets()).thenReturn(ListBucketsResponse.builder()
                .buckets(Bucket.builder()
                        .name("prod-artifacts")
                        .creationDate(Instant.parse("2026-04-10T00:00:00Z"))
                        .build())
                .build());
        when(s3Client.getBucketLocation(any(GetBucketLocationRequest.class))).thenReturn(GetBucketLocationResponse.builder()
                .locationConstraint("ap-northeast-2")
                .build());
        when(s3Client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenReturn(GetBucketVersioningResponse.builder()
                .status(BucketVersioningStatus.ENABLED)
                .build());
        when(s3Client.getBucketEncryption(any(GetBucketEncryptionRequest.class))).thenReturn(GetBucketEncryptionResponse.builder()
                .serverSideEncryptionConfiguration(ServerSideEncryptionConfiguration.builder()
                        .rules(ServerSideEncryptionRule.builder()
                                .applyServerSideEncryptionByDefault(ServerSideEncryptionByDefault.builder()
                                        .sseAlgorithm("AES256")
                                        .build())
                                .build())
                        .build())
                .build());
        when(s3Client.getPublicAccessBlock(any(GetPublicAccessBlockRequest.class))).thenReturn(GetPublicAccessBlockResponse.builder()
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(true)
                        .ignorePublicAcls(true)
                        .blockPublicPolicy(true)
                        .restrictPublicBuckets(true)
                        .build())
                .build());
        when(s3Client.getBucketNotificationConfiguration(any(GetBucketNotificationConfigurationRequest.class))).thenReturn(GetBucketNotificationConfigurationResponse.builder()
                .lambdaFunctionConfigurations(LambdaFunctionConfiguration.builder()
                        .lambdaFunctionArn("arn:aws:lambda:ap-northeast-2:123456789012:function:artifact-processor")
                        .build())
                .build());

        var result = new S3Collector(s3Client).collect(CONTEXT);

        assertThat(result.resources()).hasSize(1);
        assertThat(result.relationships()).hasSize(1);

        var properties = result.resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "S3_BUCKET")
                .containsEntry("resourceId", "prod-artifacts")
                .containsEntry("region", "ap-northeast-2")
                .containsEntry("versioningEnabled", true)
                .containsEntry("encryptionType", "AES256")
                .containsEntry("publicAccessBlocked", true);

        var relationship = result.relationships().get(0);
        assertThat(relationship.type()).isEqualTo(RelationshipTypes.TRIGGERS);
        assertThat(relationship.targetArn()).isEqualTo("arn:aws:lambda:ap-northeast-2:123456789012:function:artifact-processor");
    }
}
