package com.ariadne.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

class AwsConfigLocalStackTest {

    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8.1")
    ).withServices(
            LocalStackContainer.Service.EC2,
            LocalStackContainer.Service.STS
    );

    @BeforeAll
    static void startLocalStack() {
        LOCALSTACK.start();
    }

    @AfterAll
    static void stopLocalStack() {
        LOCALSTACK.stop();
    }

    @Test
    void createsClientsWithExplicitLocalStackCredentials() {
        var properties = new AwsProperties(
                LOCALSTACK.getRegion(),
                LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.EC2).toString(),
                LOCALSTACK.getAccessKey(),
                LOCALSTACK.getSecretKey()
        );
        var awsConfig = new AwsConfig(properties);
        var credentialsProvider = awsConfig.awsCredentialsProvider();
        var ec2Client = awsConfig.ec2Client(credentialsProvider);
        var stsClient = awsConfig.stsClient(credentialsProvider);

        ec2Client.createVpc(request -> request.cidrBlock("10.20.0.0/16"));

        assertThat(ec2Client.describeVpcs().vpcs())
                .extracting(vpc -> vpc.cidrBlock())
                .contains("10.20.0.0/16");
        assertThat(stsClient.getCallerIdentity().account()).isNotBlank();
    }
}
