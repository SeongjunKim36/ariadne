package com.ariadne.collector.s3;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.collector.BaseCollector;
import com.ariadne.collector.CollectResult;
import com.ariadne.graph.node.AwsResource;
import com.ariadne.graph.node.S3Bucket;
import com.ariadne.graph.relationship.GraphRelationship;
import com.ariadne.graph.relationship.RelationshipTypes;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class S3Collector extends BaseCollector {

    private final S3Client s3Client;

    public S3Collector(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public String resourceType() {
        return "S3_BUCKET";
    }

    @Override
    public CollectResult collect(AwsCollectContext context) {
        var resources = new ArrayList<AwsResource>();
        var relationships = new ArrayList<GraphRelationship>();
        var buckets = withRetry(s3Client::listBuckets).buckets();

        for (var bucket : buckets) {
            var bucketName = bucket.name();
            var bucketArn = s3Arn(bucketName);
            var region = resolveBucketRegion(bucketName);

            resources.add(new S3Bucket(
                    bucketArn,
                    bucketName,
                    bucketName,
                    region,
                    context.accountId(),
                    "unknown",
                    context.collectedAt(),
                    Map.of(),
                    bucket.creationDate() == null ? null : bucket.creationDate().atOffset(ZoneOffset.UTC),
                    isVersioningEnabled(bucketName),
                    encryptionType(bucketName),
                    isPublicAccessBlocked(bucketName)
            ));

            var notification = readBucketNotification(bucketName);
            for (var lambdaConfiguration : notification.lambdaFunctionConfigurations()) {
                if (hasText(lambdaConfiguration.lambdaFunctionArn())) {
                    relationships.add(new GraphRelationship(
                            bucketArn,
                            lambdaConfiguration.lambdaFunctionArn(),
                            RelationshipTypes.TRIGGERS,
                            Map.of()
                    ));
                }
            }
        }

        return new CollectResult(resources, relationships);
    }

    private String resolveBucketRegion(String bucketName) {
        var response = readSafely(() -> withRetry(() -> s3Client.getBucketLocation(GetBucketLocationRequest.builder()
                .bucket(bucketName)
                .build())));
        if (response == null || !hasText(response.locationConstraintAsString())) {
            return "us-east-1";
        }
        var location = response.locationConstraintAsString().toLowerCase(Locale.ROOT);
        return "eu".equals(location) ? "eu-west-1" : location;
    }

    private boolean isVersioningEnabled(String bucketName) {
        var response = readSafely(() -> withRetry(() -> s3Client.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(bucketName)
                .build())));
        return response != null && "enabled".equalsIgnoreCase(response.statusAsString());
    }

    private String encryptionType(String bucketName) {
        var response = readSafely(() -> withRetry(() -> s3Client.getBucketEncryption(GetBucketEncryptionRequest.builder()
                .bucket(bucketName)
                .build())));
        if (response == null
                || response.serverSideEncryptionConfiguration() == null
                || response.serverSideEncryptionConfiguration().rules().isEmpty()
                || response.serverSideEncryptionConfiguration().rules().get(0).applyServerSideEncryptionByDefault() == null) {
            return "none";
        }
        return response.serverSideEncryptionConfiguration()
                .rules()
                .get(0)
                .applyServerSideEncryptionByDefault()
                .sseAlgorithmAsString();
    }

    private boolean isPublicAccessBlocked(String bucketName) {
        var response = readSafely(() -> withRetry(() -> s3Client.getPublicAccessBlock(GetPublicAccessBlockRequest.builder()
                .bucket(bucketName)
                .build())));
        if (response == null || response.publicAccessBlockConfiguration() == null) {
            return false;
        }
        var config = response.publicAccessBlockConfiguration();
        return Boolean.TRUE.equals(config.blockPublicAcls())
                && Boolean.TRUE.equals(config.ignorePublicAcls())
                && Boolean.TRUE.equals(config.blockPublicPolicy())
                && Boolean.TRUE.equals(config.restrictPublicBuckets());
    }

    private software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse readBucketNotification(String bucketName) {
        var response = readSafely(() -> withRetry(() -> s3Client.getBucketNotificationConfiguration(GetBucketNotificationConfigurationRequest.builder()
                .bucket(bucketName)
                .build())));
        return response == null
                ? software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse.builder().build()
                : response;
    }

    private <T> T readSafely(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
