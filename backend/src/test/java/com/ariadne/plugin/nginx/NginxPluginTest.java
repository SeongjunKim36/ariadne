package com.ariadne.plugin.nginx;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.config.AriadneProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Command;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationResponse;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InstanceInformation;
import software.amazon.awssdk.services.ssm.model.PingStatus;
import software.amazon.awssdk.services.ssm.model.SendCommandResponse;
import software.amazon.awssdk.services.ssm.paginators.DescribeInstanceInformationIterable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NginxPluginTest {

    @Mock
    private Ec2Client ec2Client;

    @Mock
    private SsmClient ssmClient;

    @Mock
    private DescribeInstancesIterable describeInstancesIterable;

    @Mock
    private DescribeInstanceInformationIterable describeInstanceInformationIterable;

    private AriadneProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AriadneProperties();
    }

    @Test
    void staysQuietWhileDisabled() {
        var plugin = new NginxPlugin(properties, ec2Client, ssmClient, new NginxConfigParser(), new ObjectMapper());

        assertThat(plugin.enabled()).isFalse();
        assertThat(plugin.collect(context()).warnings()).isEmpty();
    }

    @Test
    void collectsRawNginxConfigFromManagedInstances() {
        properties.getPlugins().getNginx().setEnabled(true);
        properties.getPlugins().getNginx().setConfigPaths(List.of("/etc/nginx/nginx.conf", "/srv/nginx/conf.d"));

        var instance = Instance.builder()
                .instanceId("i-1234")
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .tags(
                        Tag.builder().key("Name").value("prod-web-1").build(),
                        Tag.builder().key("environment").value("prod").build()
                )
                .build();
        when(ec2Client.describeInstancesPaginator()).thenReturn(describeInstancesIterable);
        when(describeInstancesIterable.reservations()).thenReturn(iterableOf(Reservation.builder().instances(instance).build()));

        when(ssmClient.describeInstanceInformationPaginator(any(software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest.class)))
                .thenReturn(describeInstanceInformationIterable);
        when(describeInstanceInformationIterable.instanceInformationList()).thenReturn(iterableOf(
                InstanceInformation.builder()
                        .instanceId("i-1234")
                        .resourceType("EC2Instance")
                        .pingStatus(PingStatus.ONLINE)
                        .build()
        ));
        when(ssmClient.sendCommand(any(software.amazon.awssdk.services.ssm.model.SendCommandRequest.class)))
                .thenReturn(SendCommandResponse.builder()
                        .command(Command.builder().commandId("cmd-1234").build())
                        .build());
        when(ssmClient.getCommandInvocation(any(software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest.class)))
                .thenReturn(GetCommandInvocationResponse.builder()
                        .status(CommandInvocationStatus.SUCCESS)
                        .standardOutputContent("""
                                # ARIADNE FILE: /etc/nginx/nginx.conf
                                upstream backend {
                                  server 10.0.1.10:8080;
                                  server app.internal:8081 max_fails=3;
                                }
                                server {
                                  listen 80;
                                  server_name api.example.com api.internal _;
                                  location / {
                                    proxy_pass http://backend;
                                  }
                                  location /health {
                                    proxy_pass http://127.0.0.1:8081/health;
                                  }
                                }
                                """)
                        .build());

        var plugin = new NginxPlugin(properties, ec2Client, ssmClient, new NginxConfigParser(), new ObjectMapper());
        var result = plugin.collect(context());

        assertThat(result.warnings()).isEmpty();
        assertThat(result.managedResourceTypes()).containsExactly("NGINX_CONFIG");
        assertThat(result.result().resources()).hasSize(1);

        var properties = result.result().resources().get(0).toProperties();
        assertThat(properties)
                .containsEntry("resourceType", "NGINX_CONFIG")
                .containsEntry("instanceId", "i-1234")
                .containsEntry("sourceInstanceName", "prod-web-1")
                .containsEntry("collectionMethod", "ssm-run-command")
                .containsEntry("commandId", "cmd-1234")
                .containsEntry("environment", "prod")
                .containsEntry("truncated", false);
        assertThat(String.valueOf(properties.get("rawConfig")))
                .contains("upstream backend")
                .contains("proxy_pass http://backend;");
        assertThat(properties.get("serverNames")).asInstanceOf(LIST)
                .containsExactly("api.example.com", "api.internal");
        assertThat(properties.get("upstreamNames")).asInstanceOf(LIST)
                .containsExactly("backend");
        assertThat(properties.get("proxyPassTargets")).asInstanceOf(LIST)
                .containsExactly("http://backend", "http://127.0.0.1:8081/health");
        assertThat(String.valueOf(properties.get("upstreams")))
                .contains("\"name\":\"backend\"")
                .contains("\"host\":\"10.0.1.10\"")
                .contains("\"port\":8080");
        assertThat(String.valueOf(properties.get("proxyPasses")))
                .contains("\"rawValue\":\"http://backend\"")
                .contains("\"upstreamName\":\"backend\"")
                .contains("\"host\":\"127.0.0.1\"")
                .contains("\"path\":\"/health\"");
    }

    @Test
    void warnsWhenNoManagedInstancesAreAvailable() {
        properties.getPlugins().getNginx().setEnabled(true);

        var instance = Instance.builder()
                .instanceId("i-1234")
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .build();
        when(ec2Client.describeInstancesPaginator()).thenReturn(describeInstancesIterable);
        when(describeInstancesIterable.reservations()).thenReturn(iterableOf(Reservation.builder().instances(instance).build()));

        when(ssmClient.describeInstanceInformationPaginator(any(software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest.class)))
                .thenReturn(describeInstanceInformationIterable);
        when(describeInstanceInformationIterable.instanceInformationList()).thenReturn(iterableOf());

        var plugin = new NginxPlugin(properties, ec2Client, ssmClient, new NginxConfigParser(), new ObjectMapper());
        var result = plugin.collect(context());

        assertThat(result.result().resources()).isEmpty();
        assertThat(result.warnings()).containsExactly("Plugin nginx skipped: no SSM-managed EC2 instances were found.");
        assertThat(result.managedResourceTypes()).containsExactly("NGINX_CONFIG");
    }

    private AwsCollectContext context() {
        return new AwsCollectContext(
                "123456789012",
                "ap-northeast-2",
                OffsetDateTime.of(2026, 4, 15, 1, 0, 0, 0, ZoneOffset.UTC)
        );
    }

    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... values) {
        return () -> List.of(values).iterator();
    }
}
