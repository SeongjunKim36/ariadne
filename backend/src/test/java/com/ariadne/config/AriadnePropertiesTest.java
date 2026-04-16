package com.ariadne.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AriadnePropertiesTest {

    @Test
    void exposesDefaultNginxPluginValues() {
        var properties = new AriadneProperties();

        assertThat(properties.getScan().getSchedule()).isEqualTo("0 0 * * * *");
        assertThat(properties.getLlm().getTransmissionLevel()).isNull();
        assertThat(properties.getLlm().getAllowedFields())
                .contains("resourceId", "name", "cidrBlock", "port");
        assertThat(properties.getLlm().getVerboseAdditionalFields())
                .containsExactly("tags", "env", "envVars", "environmentVariables");
        assertThat(properties.getPlugins().getNginx().isEnabled()).isFalse();
        assertThat(properties.getPlugins().getNginx().getSsmTimeoutSeconds()).isEqualTo(30);
        assertThat(properties.getPlugins().getNginx().getConfigPaths())
                .containsExactly("/etc/nginx/nginx.conf", "/etc/nginx/conf.d/");
    }

    @Test
    void bindsNestedNginxPluginOverrides() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "ariadne.scan.schedule", "0 */30 * * * *",
                "ariadne.llm.transmission-level", "verbose",
                "ariadne.llm.allowed-fields[0]", "resourceId",
                "ariadne.llm.allowed-fields[1]", "runtime",
                "ariadne.llm.verbose-additional-fields[0]", "tags",
                "ariadne.plugins.nginx.enabled", "true",
                "ariadne.plugins.nginx.ssm-timeout-seconds", "45",
                "ariadne.plugins.nginx.config-paths[0]", "/etc/nginx/nginx.conf",
                "ariadne.plugins.nginx.config-paths[1]", "/opt/nginx/conf.d"
        )));

        var properties = binder.bind("ariadne", Bindable.of(AriadneProperties.class))
                .orElseGet(AriadneProperties::new);

        assertThat(properties.getScan().getSchedule()).isEqualTo("0 */30 * * * *");
        assertThat(properties.getLlm().getTransmissionLevel()).isEqualTo("verbose");
        assertThat(properties.getLlm().getAllowedFields()).containsExactly("resourceId", "runtime");
        assertThat(properties.getLlm().getVerboseAdditionalFields()).containsExactly("tags");
        assertThat(properties.getPlugins().getNginx().isEnabled()).isTrue();
        assertThat(properties.getPlugins().getNginx().getSsmTimeoutSeconds()).isEqualTo(45);
        assertThat(properties.getPlugins().getNginx().getConfigPaths())
                .containsExactly("/etc/nginx/nginx.conf", "/opt/nginx/conf.d");
    }
}
