package com.ariadne.notify;

import com.ariadne.drift.TerraformDriftRun;
import com.ariadne.events.EventLog;
import com.ariadne.snapshot.Snapshot;
import com.ariadne.snapshot.SnapshotDiff;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final SlackNotifier slackNotifier;
    private final NotificationRuleEngine notificationRuleEngine;

    public NotificationService(SlackNotifier slackNotifier, NotificationRuleEngine notificationRuleEngine) {
        this.slackNotifier = slackNotifier;
        this.notificationRuleEngine = notificationRuleEngine;
    }

    public void notifySnapshotDiff(Snapshot snapshot, SnapshotDiff diff) {
        notificationRuleEngine.snapshotDiff(snapshot, diff)
                .ifPresent(message -> slackNotifier.send(message.render()));
    }

    public void notifyDriftReport(TerraformDriftRun run) {
        notificationRuleEngine.drift(run)
                .ifPresent(message -> slackNotifier.send(message.render()));
    }

    public void notifyEvent(EventLog eventLog) {
        notificationRuleEngine.event(eventLog)
                .ifPresent(message -> slackNotifier.send(message.render()));
    }
}
