package com.ariadne.events;

import com.ariadne.config.AriadneProperties;
import com.ariadne.notify.NotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class EventBridgeListener {

    private final AriadneProperties ariadneProperties;
    private final SqsClient sqsClient;
    private final EventResourceMapper eventResourceMapper;
    private final EventLogRepository eventLogRepository;
    private final PartialRefreshService partialRefreshService;
    private final NotificationService notificationService;

    public EventBridgeListener(
            AriadneProperties ariadneProperties,
            SqsClient sqsClient,
            EventResourceMapper eventResourceMapper,
            EventLogRepository eventLogRepository,
            PartialRefreshService partialRefreshService,
            NotificationService notificationService
    ) {
        this.ariadneProperties = ariadneProperties;
        this.sqsClient = sqsClient;
        this.eventResourceMapper = eventResourceMapper;
        this.eventLogRepository = eventLogRepository;
        this.partialRefreshService = partialRefreshService;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "${ariadne.eventbridge.schedule:0 * * * * *}")
    public void poll() {
        var eventbridge = ariadneProperties.getEventbridge();
        if (!eventbridge.isEnabled() || eventbridge.getQueueUrl() == null || eventbridge.getQueueUrl().isBlank()) {
            return;
        }

        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(eventbridge.getQueueUrl())
                .maxNumberOfMessages(Math.min(Math.max(eventbridge.getMaxMessages(), 1), 10))
                .waitTimeSeconds(1)
                .build());

        for (var message : response.messages()) {
            processMessage(eventbridge.getQueueUrl(), message.receiptHandle(), message.body());
        }
    }

    private void processMessage(String queueUrl, String receiptHandle, String rawBody) {
        EventLog eventLog = null;
        try {
            var mapping = eventResourceMapper.map(rawBody);
            eventLog = new EventLog(
                    OffsetDateTime.now(ZoneOffset.UTC),
                    mapping.eventTime(),
                    mapping.eventId(),
                    mapping.source() == null ? "unknown" : mapping.source(),
                    mapping.detailType() == null ? "unknown" : mapping.detailType(),
                    mapping.resourceArn(),
                    mapping.resourceType(),
                    mapping.action(),
                    EventLogStatus.RECEIVED,
                    mapping.summary(),
                    rawBody
            );
            eventLogRepository.save(eventLog);

            if (mapping.collectorTypes().isEmpty()) {
                eventLog.markSkipped("No supported collector mapping for event.");
            } else {
                var result = partialRefreshService.refresh(mapping);
                eventLog.markProcessed(result);
            }
            eventLogRepository.save(eventLog);
            notificationService.notifyEvent(eventLog);
        } catch (RuntimeException exception) {
            if (eventLog == null) {
                eventLog = new EventLog(
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        null,
                        "unknown",
                        "unknown",
                        null,
                        null,
                        null,
                        EventLogStatus.FAILED,
                        exception.getMessage(),
                        rawBody
                );
            } else {
                eventLog.markFailed(exception.getMessage());
            }
            eventLogRepository.save(eventLog);
            notificationService.notifyEvent(eventLog);
        } finally {
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build());
        }
    }
}
