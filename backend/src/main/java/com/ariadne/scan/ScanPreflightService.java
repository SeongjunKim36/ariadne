package com.ariadne.scan;

import com.ariadne.api.dto.ScanPreflightResponse;
import com.ariadne.config.AwsProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;

@Service
public class ScanPreflightService {

    private final AwsCredentialsProvider credentialsProvider;
    private final StsClient stsClient;
    private final AwsProperties awsProperties;

    public ScanPreflightService(
            AwsCredentialsProvider credentialsProvider,
            StsClient stsClient,
            AwsProperties awsProperties
    ) {
        this.credentialsProvider = credentialsProvider;
        this.stsClient = stsClient;
        this.awsProperties = awsProperties;
    }

    public ScanPreflightResponse inspect() {
        try {
            credentialsProvider.resolveCredentials();
            var identity = stsClient.getCallerIdentity();
            return new ScanPreflightResponse(
                    true,
                    awsProperties.region(),
                    identity.account(),
                    identity.arn(),
                    authenticationMode(),
                    "AWS credentials are ready for scanning."
            );
        } catch (RuntimeException exception) {
            return new ScanPreflightResponse(
                    false,
                    awsProperties.region(),
                    null,
                    null,
                    authenticationMode(),
                    AwsFailureMessageResolver.toUserMessage(exception)
            );
        }
    }

    private String authenticationMode() {
        return awsProperties.hasStaticCredentials() ? "static" : "default-chain";
    }
}
