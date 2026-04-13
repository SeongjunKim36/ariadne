package com.ariadne.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;

@Configuration
public class AwsConfig {

    @Value("${aws.region:ap-northeast-2}")
    private String region;

    @Value("${aws.endpoint-url:#{null}}")
    private String endpointUrl;

    @Bean
    Ec2Client ec2Client() {
        return buildClient(Ec2Client.builder()).build();
    }

    @Bean
    RdsClient rdsClient() {
        return buildClient(RdsClient.builder()).build();
    }

    @Bean
    EcsClient ecsClient() {
        return buildClient(EcsClient.builder()).build();
    }

    @Bean
    ElasticLoadBalancingV2Client elbClient() {
        return buildClient(ElasticLoadBalancingV2Client.builder()).build();
    }

    @Bean
    S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointUrl != null) {
            builder.endpointOverride(URI.create(endpointUrl))
                    .forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    LambdaClient lambdaClient() {
        return buildClient(LambdaClient.builder()).build();
    }

    @Bean
    Route53Client route53Client() {
        return buildClient(Route53Client.builder()).build();
    }

    @Bean
    StsClient stsClient() {
        return buildClient(StsClient.builder()).build();
    }

    private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B buildClient(B builder) {
        builder.region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointUrl != null) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder;
    }
}
