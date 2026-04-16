package com.ariadne.notify;

import com.ariadne.drift.TerraformDriftRun;
import com.ariadne.events.EventLog;
import com.ariadne.events.EventLogStatus;
import com.ariadne.snapshot.Snapshot;
import com.ariadne.snapshot.SnapshotDiff;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class NotificationRuleEngine {

    public Optional<NotificationMessage> snapshotDiff(Snapshot snapshot, SnapshotDiff diff) {
        if (diff.getTotalChanges() <= 0) {
            return Optional.empty();
        }

        var severity = diff.getRemovedCount() > 0
                ? NotificationSeverity.HIGH
                : diff.getModifiedCount() > 0
                ? NotificationSeverity.MEDIUM
                : NotificationSeverity.INFO;

        return Optional.of(new NotificationMessage(
                severity,
                "Ariadne snapshot diff detected",
                """
                        - trigger: %s
                        - capturedAt: %s
                        - totalChanges: %d
                        - added: %d / removed: %d / modified: %d
                        """.formatted(
                        snapshot.getTriggerSource().name(),
                        snapshot.getCapturedAt(),
                        diff.getTotalChanges(),
                        diff.getAddedCount(),
                        diff.getRemovedCount(),
                        diff.getModifiedCount()
                )
        ));
    }

    public Optional<NotificationMessage> drift(TerraformDriftRun run) {
        if (run.getTotalItems() <= 0) {
            return Optional.empty();
        }

        var severity = run.getMissingCount() > 0 || run.getUnmanagedCount() > 0
                ? NotificationSeverity.HIGH
                : NotificationSeverity.MEDIUM;

        return Optional.of(new NotificationMessage(
                severity,
                "Ariadne Terraform drift detected",
                """
                        - source: %s
                        - generatedAt: %s
                        - total: %d
                        - missing: %d / modified: %d / unmanaged: %d
                        """.formatted(
                        run.getSourceName(),
                        run.getGeneratedAt(),
                        run.getTotalItems(),
                        run.getMissingCount(),
                        run.getModifiedCount(),
                        run.getUnmanagedCount()
                )
        ));
    }

    public Optional<NotificationMessage> event(EventLog eventLog) {
        if (eventLog.getStatus() == EventLogStatus.SKIPPED || eventLog.getStatus() == EventLogStatus.RECEIVED) {
            return Optional.empty();
        }

        var severity = eventLog.getStatus() == EventLogStatus.FAILED
                ? NotificationSeverity.HIGH
                : NotificationSeverity.INFO;

        return Optional.of(new NotificationMessage(
                severity,
                "Ariadne EventBridge event processed",
                """
                        - source: %s
                        - detailType: %s
                        - action: %s
                        - resourceType: %s
                        - status: %s
                        - message: %s
                        """.formatted(
                        eventLog.getSource(),
                        eventLog.getDetailType(),
                        eventLog.getAction(),
                        eventLog.getResourceType(),
                        eventLog.getStatus().name(),
                        eventLog.getMessage()
                )
        ));
    }
}
