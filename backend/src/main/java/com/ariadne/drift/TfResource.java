package com.ariadne.drift;

import java.util.Map;

record TfResource(
        String address,
        String terraformType,
        String resourceType,
        String arn,
        String resourceId,
        String name,
        Map<String, Object> properties
) {
}
