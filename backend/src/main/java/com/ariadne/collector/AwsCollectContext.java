package com.ariadne.collector;

import java.time.OffsetDateTime;

public record AwsCollectContext(
        String accountId,
        String region,
        OffsetDateTime collectedAt
) {
}
