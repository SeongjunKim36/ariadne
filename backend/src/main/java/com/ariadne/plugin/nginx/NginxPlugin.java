package com.ariadne.plugin.nginx;

import com.ariadne.collector.AwsCollectContext;
import com.ariadne.config.AriadneProperties;
import com.ariadne.plugin.CollectorPlugin;
import com.ariadne.plugin.PluginCollectResult;
import org.springframework.stereotype.Service;

@Service
public class NginxPlugin implements CollectorPlugin {

    private final AriadneProperties ariadneProperties;

    public NginxPlugin(AriadneProperties ariadneProperties) {
        this.ariadneProperties = ariadneProperties;
    }

    @Override
    public String pluginId() {
        return "nginx";
    }

    @Override
    public boolean enabled() {
        return ariadneProperties.getPlugins().getNginx().isEnabled();
    }

    @Override
    public PluginCollectResult collect(AwsCollectContext context) {
        if (!enabled()) {
            return PluginCollectResult.empty();
        }

        var nginxProperties = ariadneProperties.getPlugins().getNginx();
        var warning = "Plugin nginx is enabled, but SSM-based nginx config collection is not implemented yet. "
                + "Requested paths: %s (timeout=%ss)"
                .formatted(
                        String.join(", ", nginxProperties.getConfigPaths()),
                        nginxProperties.getSsmTimeoutSeconds()
                );
        return PluginCollectResult.warning(warning);
    }
}
