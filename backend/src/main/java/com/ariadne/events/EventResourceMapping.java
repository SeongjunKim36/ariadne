package com.ariadne.events;

import java.time.OffsetDateTime;
import java.util.Set;

public record EventResourceMapping(
        String eventId,
        OffsetDateTime eventTime,
        String source,
        String detailType,
        String action,
        String resourceArn,
        String resourceType,
        Set<String> collectorTypes,
        String summary
) {
}
