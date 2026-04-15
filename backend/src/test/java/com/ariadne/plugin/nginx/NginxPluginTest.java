package com.ariadne.plugin.nginx;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.config.AriadneProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class NginxPluginTest {

    @Test
    void staysQuietWhileDisabled() {
        var plugin = new NginxPlugin(new AriadneProperties());

        assertThat(plugin.enabled()).isFalse();
        assertThat(plugin.collect(context()).warnings()).isEmpty();
    }

    @Test
    void emitsGuidanceWarningWhenEnabled() {
        var properties = new AriadneProperties();
        properties.getPlugins().getNginx().setEnabled(true);
        properties.getPlugins().getNginx().setSsmTimeoutSeconds(45);
        properties.getPlugins().getNginx().setConfigPaths(java.util.List.of("/etc/nginx/nginx.conf", "/srv/nginx/conf.d"));

        var plugin = new NginxPlugin(properties);
        var result = plugin.collect(context());

        assertThat(plugin.enabled()).isTrue();
        assertThat(result.result().resources()).isEmpty();
        assertThat(result.warnings()).singleElement().asString()
                .contains("Plugin nginx is enabled")
                .contains("/srv/nginx/conf.d")
                .contains("timeout=45s");
    }

    private AwsCollectContext context() {
        return new AwsCollectContext(
                "123456789012",
                "ap-northeast-2",
                OffsetDateTime.of(2026, 4, 15, 1, 0, 0, 0, ZoneOffset.UTC)
        );
    }
}
