package com.ariadne.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
@EnableConfigurationProperties({AwsProperties.class, AriadneProperties.class})
public class AwsConfig {

    private final AwsProperties awsProperties;

    public AwsConfig(AwsProperties awsProperties) {
        this.awsProperties = awsProperties;
    }

    @Bean
    AwsCredentialsProvider awsCredentialsProvider() {
        if (awsProperties.hasStaticCredentials()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey())
            );
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean
    Ec2Client ec2Client(AwsCredentialsProvider credentialsProvider) {
        return buildClient(Ec2Client.builder(), credentialsProvider).build();
    }

    @Bean
    RdsClient rdsClient(AwsCredentialsProvider credentialsProvider) {
        return buildClient(RdsClient.builder(), credentialsProvider).build();
    }

    @Bean
    EcsClient ecsClient(AwsCredentialsProvider credentialsProvider) {
        return buildClient(EcsClient.builder(), credentialsProvider).build();
    }

    @Bean
    ElasticLoadBalancingV2Client elbClient(AwsCredentialsProvider credentialsProvider) {
        return buildClient(ElasticLoadBalancingV2Client.builder(), credentialsProvider).build();
    }

    @Bean
    S3Client s3Client(AwsCredentialsProvider credentialsProvider) {
        var builder = S3Client.builder()
                .region(awsProperties.awsRegion())
                .credentialsProvider(credentialsProvider);
        if (awsProperties.hasEndpointOverride()) {
            builder.endpointOverride(awsProperties.endpointUri())
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    LambdaClient lambdaClient(AwsCredentialsProvider credentialsProvider) {
        return buildClient(LambdaClient.builder(), credentialsProvider).build();
    }

    @Bean
    Route53Client route53Client(AwsCredentialsProvider credentialsProvider) {
        return buildClient(Route53Client.builder(), credentialsProvider).build();
    }

    @Bean
    IamClient iamClient(AwsCredentialsProvider credentialsProvider) {
        var builder = IamClient.builder()
                .credentialsProvider(credentialsProvider);
        if (awsProperties.hasEndpointOverride()) {
            builder.region(awsProperties.awsRegion())
                    .endpointOverride(awsProperties.endpointUri());
        } else {
            builder.region(Region.AWS_GLOBAL);
        }
        return builder.build();
    }

    @Bean
    StsClient stsClient(AwsCredentialsProvider credentialsProvider) {
        return buildClient(StsClient.builder(), credentialsProvider).build();
    }

    @Bean
    SsmClient ssmClient(AwsCredentialsProvider credentialsProvider) {
        return buildClient(SsmClient.builder(), credentialsProvider).build();
    }

    private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B buildClient(
            B builder,
            AwsCredentialsProvider credentialsProvider
    ) {
        builder.region(awsProperties.awsRegion())
                .credentialsProvider(credentialsProvider);
        if (awsProperties.hasEndpointOverride()) {
            builder.endpointOverride(awsProperties.endpointUri());
        }
        return builder;
    }
}
