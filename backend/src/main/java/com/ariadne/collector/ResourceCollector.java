package com.ariadne.collector;

public interface ResourceCollector {

    String resourceType();

    CollectResult collect(AwsCollectContext context);
}
