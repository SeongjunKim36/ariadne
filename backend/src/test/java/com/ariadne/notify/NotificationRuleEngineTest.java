package com.ariadne.notify;

import com.ariadne.drift.TerraformDriftRun;
import com.ariadne.drift.TerraformStateSourceKind;
import com.ariadne.events.EventLog;
import com.ariadne.events.EventLogStatus;
import com.ariadne.snapshot.Snapshot;
import com.ariadne.snapshot.SnapshotDiff;
import com.ariadne.snapshot.SnapshotTrigger;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRuleEngineTest {

    private final NotificationRuleEngine engine = new NotificationRuleEngine();

    @Test
    void elevatesSnapshotNotificationsWhenResourcesAreRemoved() {
        var message = engine.snapshotDiff(
                new Snapshot(
                        OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                        "123456789012",
                        "ap-northeast-2",
                        12,
                        14,
                        1200,
                        SnapshotTrigger.MANUAL_SCAN,
                        null,
                        "{}",
                        "{}"
                ),
                new SnapshotDiff(1L, 2L, OffsetDateTime.parse("2026-04-16T09:01:00Z"), "[]", "[]", "[]", "[]", "[]", "[]", 3, 1, 1, 1)
        );

        assertThat(message).isPresent();
        assertThat(message.get().severity()).isEqualTo(NotificationSeverity.HIGH);
        assertThat(message.get().render()).contains("Ariadne snapshot diff detected");
    }

    @Test
    void skipsEmptyDriftRunsAndIgnoredEvents() {
        assertThat(engine.drift(new TerraformDriftRun(
                OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                TerraformStateSourceKind.INLINE_JSON,
                "inline-json",
                0,
                0,
                0,
                0,
                "[]"
        ))).isEmpty();

        assertThat(engine.event(new EventLog(
                OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                "evt-1",
                "aws.ec2",
                "AWS API Call via CloudTrail",
                null,
                "EC2",
                "RunInstances",
                EventLogStatus.SKIPPED,
                "ignored",
                "{}"
        ))).isEmpty();
    }

    @Test
    void marksFailedEventsAsHighSeverity() {
        var message = engine.event(new EventLog(
                OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                OffsetDateTime.parse("2026-04-16T09:00:00Z"),
                "evt-2",
                "aws.ec2",
                "AWS API Call via CloudTrail",
                "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234",
                "EC2",
                "TerminateInstances",
                EventLogStatus.FAILED,
                "boom",
                "{}"
        ));

        assertThat(message).isPresent();
        assertThat(message.get().severity()).isEqualTo(NotificationSeverity.HIGH);
        assertThat(message.get().render()).contains("FAILED");
    }
}
