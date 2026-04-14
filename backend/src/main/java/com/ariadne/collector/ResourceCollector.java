package com.ariadne.collector;

import java.util.Set;

public interface ResourceCollector {

    String resourceType();

    CollectResult collect(AwsCollectContext context);

    default Set<String> managedResourceTypes() {
        return Set.of(resourceType());
    }
}
