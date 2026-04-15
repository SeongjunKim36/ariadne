package com.ariadne.scan;

import com.ariadne.config.AwsProperties;
import com.ariadne.config.AriadneProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanPreflightServiceTest {

    @Mock
    private AwsCredentialsProvider credentialsProvider;

    @Mock
    private StsClient stsClient;

    @Mock
    private SsmClient ssmClient;

    @Test
    void returnsReadyResponseWhenCredentialsAndIdentityAreAvailable() {
        var service = new ScanPreflightService(
                credentialsProvider,
                stsClient,
                ssmClient,
                new AwsProperties("ap-northeast-2", null, null, null),
                new AriadneProperties()
        );
        when(credentialsProvider.resolveCredentials())
                .thenReturn(AwsBasicCredentials.create("test", "test"));
        when(stsClient.getCallerIdentity())
                .thenReturn(GetCallerIdentityResponse.builder()
                        .account("123456789012")
                        .arn("arn:aws:sts::123456789012:assumed-role/Ariadne/cli")
                        .build());

        var response = service.inspect();

        assertThat(response.ready()).isTrue();
        assertThat(response.accountId()).isEqualTo("123456789012");
        assertThat(response.callerArn()).contains("Ariadne");
        assertThat(response.message()).contains("ready");
        assertThat(response.warningMessage()).isNull();
    }

    @Test
    void returnsHelpfulMessageWhenCredentialsAreMissing() {
        var service = new ScanPreflightService(
                credentialsProvider,
                stsClient,
                ssmClient,
                new AwsProperties("ap-northeast-2", null, null, null),
                new AriadneProperties()
        );
        when(credentialsProvider.resolveCredentials())
                .thenThrow(new IllegalStateException("Unable to locate credentials"));

        var response = service.inspect();

        assertThat(response.ready()).isFalse();
        assertThat(response.accountId()).isNull();
        assertThat(response.message()).contains("aws sso login");
        assertThat(response.warningMessage()).isNull();
    }

    @Test
    void exposesOptionalNginxPermissionGuidanceWhenPluginIsEnabled() {
        var properties = new AriadneProperties();
        properties.getPlugins().getNginx().setEnabled(true);

        var service = new ScanPreflightService(
                credentialsProvider,
                stsClient,
                ssmClient,
                new AwsProperties("ap-northeast-2", null, null, null),
                properties
        );
        when(credentialsProvider.resolveCredentials())
                .thenReturn(AwsBasicCredentials.create("test", "test"));
        when(stsClient.getCallerIdentity())
                .thenReturn(GetCallerIdentityResponse.builder()
                        .account("123456789012")
                        .arn("arn:aws:sts::123456789012:assumed-role/Ariadne/cli")
                        .build());
        when(ssmClient.describeInstanceInformation(org.mockito.ArgumentMatchers.any(DescribeInstanceInformationRequest.class)))
                .thenThrow(SsmException.builder()
                        .message("User is not authorized to perform: ssm:DescribeInstanceInformation")
                        .build());

        var response = service.inspect();

        assertThat(response.ready()).isTrue();
        assertThat(response.warningMessage())
                .contains("nginx plugin is enabled")
                .contains("ssm:DescribeInstanceInformation")
                .contains("ariadne-nginx-plugin-policy.json");
    }

    @Test
    void warnsWhenNginxPluginHasNoManagedInstances() {
        var properties = new AriadneProperties();
        properties.getPlugins().getNginx().setEnabled(true);

        var service = new ScanPreflightService(
                credentialsProvider,
                stsClient,
                ssmClient,
                new AwsProperties("ap-northeast-2", null, null, null),
                properties
        );
        when(credentialsProvider.resolveCredentials())
                .thenReturn(AwsBasicCredentials.create("test", "test"));
        when(stsClient.getCallerIdentity())
                .thenReturn(GetCallerIdentityResponse.builder()
                        .account("123456789012")
                        .arn("arn:aws:sts::123456789012:assumed-role/Ariadne/cli")
                        .build());
        when(ssmClient.describeInstanceInformation(org.mockito.ArgumentMatchers.any(DescribeInstanceInformationRequest.class)))
                .thenReturn(DescribeInstanceInformationResponse.builder().build());

        var response = service.inspect();

        assertThat(response.ready()).isTrue();
        assertThat(response.warningMessage()).contains("no online SSM-managed EC2 instances");
    }
}
