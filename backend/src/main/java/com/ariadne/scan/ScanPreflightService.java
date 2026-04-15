package com.ariadne.scan;

import com.ariadne.api.dto.ScanPreflightResponse;
import com.ariadne.config.AriadneProperties;
import com.ariadne.config.AwsProperties;
import com.ariadne.plugin.nginx.NginxPluginGuidance;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest;
import software.amazon.awssdk.services.sts.StsClient;

@Service
public class ScanPreflightService {

    private final AwsCredentialsProvider credentialsProvider;
    private final StsClient stsClient;
    private final SsmClient ssmClient;
    private final AwsProperties awsProperties;
    private final AriadneProperties ariadneProperties;

    public ScanPreflightService(
            AwsCredentialsProvider credentialsProvider,
            StsClient stsClient,
            SsmClient ssmClient,
            AwsProperties awsProperties,
            AriadneProperties ariadneProperties
    ) {
        this.credentialsProvider = credentialsProvider;
        this.stsClient = stsClient;
        this.ssmClient = ssmClient;
        this.awsProperties = awsProperties;
        this.ariadneProperties = ariadneProperties;
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
                    "AWS credentials are ready for scanning.",
                    inspectPluginWarning()
            );
        } catch (RuntimeException exception) {
            return new ScanPreflightResponse(
                    false,
                    awsProperties.region(),
                    null,
                    null,
                    authenticationMode(),
                    AwsFailureMessageResolver.toUserMessage(exception),
                    null
            );
        }
    }

    private String inspectPluginWarning() {
        if (!ariadneProperties.getPlugins().getNginx().isEnabled()) {
            return null;
        }

        try {
            var response = ssmClient.describeInstanceInformation(DescribeInstanceInformationRequest.builder()
                    .maxResults(1)
                    .build());
            if (response.instanceInformationList() == null || response.instanceInformationList().isEmpty()) {
                return NginxPluginGuidance.missingManagedInstanceMessage();
            }
            return null;
        } catch (RuntimeException exception) {
            if (NginxPluginGuidance.isAccessDenied(exception)) {
                return NginxPluginGuidance.missingPermissionMessage();
            }
            return "nginx plugin is enabled, but SSM preflight could not be verified: " + exception.getMessage();
        }
    }

    private String authenticationMode() {
        return awsProperties.hasStaticCredentials() ? "static" : "default-chain";
    }
}
