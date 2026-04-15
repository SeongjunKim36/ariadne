package com.ariadne.plugin;

import com.ariadne.collector.AwsCollectContext;

public interface CollectorPlugin {

    String pluginId();

    boolean enabled();

    PluginCollectResult collect(AwsCollectContext context);
}
