package com.ariadne.api.dto;

import com.ariadne.events.EventLog;

import java.time.OffsetDateTime;

public record EventLogResponse(
        long id,
        OffsetDateTime receivedAt,
        OffsetDateTime eventTime,
        String eventId,
        String source,
        String detailType,
        String resourceArn,
        String resourceType,
        String action,
        String status,
        String message
) {

    public static EventLogResponse from(EventLog eventLog) {
        return new EventLogResponse(
                eventLog.getId(),
                eventLog.getReceivedAt(),
                eventLog.getEventTime(),
                eventLog.getEventId(),
                eventLog.getSource(),
                eventLog.getDetailType(),
                eventLog.getResourceArn(),
                eventLog.getResourceType(),
                eventLog.getAction(),
                eventLog.getStatus().name(),
                eventLog.getMessage()
        );
    }
}
