package com.ariadne.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

@Entity
@Table(name = "event_log")
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime receivedAt;

    private OffsetDateTime eventTime;

    @Column(length = 255)
    private String eventId;

    @Column(nullable = false, length = 255)
    private String source;

    @Column(nullable = false, length = 255)
    private String detailType;

    @Column(length = 1024)
    private String resourceArn;

    @Column(length = 64)
    private String resourceType;

    @Column(length = 255)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventLogStatus status;

    @Column(length = 2048)
    private String message;

    @Column(columnDefinition = "jsonb", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String rawEventJson;

    protected EventLog() {
    }

    public EventLog(
            OffsetDateTime receivedAt,
            OffsetDateTime eventTime,
            String eventId,
            String source,
            String detailType,
            String resourceArn,
            String resourceType,
            String action,
            EventLogStatus status,
            String message,
            String rawEventJson
    ) {
        this.receivedAt = receivedAt;
        this.eventTime = eventTime;
        this.eventId = eventId;
        this.source = source;
        this.detailType = detailType;
        this.resourceArn = resourceArn;
        this.resourceType = resourceType;
        this.action = action;
        this.status = status;
        this.message = message;
        this.rawEventJson = rawEventJson;
    }

    public Long getId() {
        return id;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public OffsetDateTime getEventTime() {
        return eventTime;
    }

    public String getEventId() {
        return eventId;
    }

    public String getSource() {
        return source;
    }

    public String getDetailType() {
        return detailType;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getAction() {
        return action;
    }

    public EventLogStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getRawEventJson() {
        return rawEventJson;
    }

    public void markProcessed(String message) {
        this.status = EventLogStatus.PROCESSED;
        this.message = message;
    }

    public void markSkipped(String message) {
        this.status = EventLogStatus.SKIPPED;
        this.message = message;
    }

    public void markFailed(String message) {
        this.status = EventLogStatus.FAILED;
        this.message = message;
    }
}
