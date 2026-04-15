package com.ariadne.plugin;

import com.ariadne.collector.CollectResult;

import java.util.List;
import java.util.Set;

public record PluginCollectResult(
        Set<String> managedResourceTypes,
        CollectResult result,
        List<String> warnings
) {

    public PluginCollectResult {
        managedResourceTypes = Set.copyOf(managedResourceTypes);
        warnings = List.copyOf(warnings);
    }

    public static PluginCollectResult empty() {
        return new PluginCollectResult(Set.of(), CollectResult.empty(), List.of());
    }

    public static PluginCollectResult warning(String warning) {
        return new PluginCollectResult(Set.of(), CollectResult.empty(), List.of(warning));
    }
}
