package com.ariadne.persistence;

import com.ariadne.drift.TerraformDriftRun;
import com.ariadne.drift.TerraformDriftRunRepository;
import com.ariadne.drift.TerraformStateSourceKind;
import com.ariadne.events.EventLog;
import com.ariadne.events.EventLogRepository;
import com.ariadne.events.EventLogStatus;
import com.ariadne.snapshot.Snapshot;
import com.ariadne.snapshot.SnapshotDiff;
import com.ariadne.snapshot.SnapshotDiffRepository;
import com.ariadne.snapshot.SnapshotRepository;
import com.ariadne.snapshot.SnapshotTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JsonbPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private SnapshotDiffRepository snapshotDiffRepository;

    @Autowired
    private EventLogRepository eventLogRepository;

    @Autowired
    private TerraformDriftRunRepository terraformDriftRunRepository;

    @Test
    void persistsJsonbBackedEntitiesWithoutTypeErrors() {
        var snapshot = snapshotRepository.save(new Snapshot(
                OffsetDateTime.parse("2026-04-16T08:47:16Z"),
                "626130419925",
                "ap-northeast-2",
                24,
                45,
                4451,
                SnapshotTrigger.MANUAL_SCAN,
                UUID.fromString("c716eaa0-073e-4a61-93b0-83d694201775"),
                "{\"nodes\":[{\"id\":\"ec2-1\"}],\"edges\":[],\"metadata\":{\"nodeCount\":1,\"edgeCount\":0}}",
                "{\"trigger\":\"MANUAL_SCAN\"}"
        ));

        var diff = snapshotDiffRepository.save(new SnapshotDiff(
                snapshot.getId(),
                snapshot.getId(),
                OffsetDateTime.parse("2026-04-16T08:47:18Z"),
                "[{\"id\":\"ec2-2\"}]",
                "[]",
                "[{\"id\":\"ec2-1\",\"changes\":{\"name\":\"after\"}}]",
                "[]",
                "[]",
                "[{\"id\":\"edge-1\"}]",
                2,
                1,
                0,
                1
        ));

        var eventLog = eventLogRepository.save(new EventLog(
                OffsetDateTime.parse("2026-04-16T08:47:19Z"),
                OffsetDateTime.parse("2026-04-16T08:47:19Z"),
                "event-1",
                "aws.ec2",
                "EC2 Instance State-change Notification",
                "arn:aws:ec2:ap-northeast-2:626130419925:instance/i-1234567890abcdef0",
                "EC2",
                "MODIFIED",
                EventLogStatus.PROCESSED,
                "Processed partial refresh",
                "{\"detail\":{\"instance-id\":\"i-1234567890abcdef0\"}}"
        ));

        var driftRun = terraformDriftRunRepository.save(new TerraformDriftRun(
                OffsetDateTime.parse("2026-04-16T08:47:20Z"),
                TerraformStateSourceKind.INLINE_JSON,
                "phase5-regression",
                3,
                1,
                1,
                1,
                "{\"missing\":[\"sg-1\"],\"modified\":[\"rds-1\"],\"unmanaged\":[\"ec2-1\"]}"
        ));

        assertThat(snapshotRepository.findById(snapshot.getId()))
                .get()
                .extracting(Snapshot::getGraphJson, Snapshot::getMetadataJson)
                .containsExactly(
                        "{\"nodes\":[{\"id\":\"ec2-1\"}],\"edges\":[],\"metadata\":{\"nodeCount\":1,\"edgeCount\":0}}",
                        "{\"trigger\":\"MANUAL_SCAN\"}"
                );

        assertThat(snapshotDiffRepository.findById(diff.getId()))
                .get()
                .extracting(SnapshotDiff::getModifiedNodesJson, SnapshotDiff::getModifiedEdgesJson)
                .containsExactly(
                        "[{\"id\":\"ec2-1\",\"changes\":{\"name\":\"after\"}}]",
                        "[{\"id\":\"edge-1\"}]"
                );

        assertThat(eventLogRepository.findById(eventLog.getId()))
                .get()
                .extracting(EventLog::getRawEventJson)
                .isEqualTo("{\"detail\":{\"instance-id\":\"i-1234567890abcdef0\"}}");

        assertThat(terraformDriftRunRepository.findById(driftRun.getId()))
                .get()
                .extracting(TerraformDriftRun::getReportJson)
                .isEqualTo("{\"missing\":[\"sg-1\"],\"modified\":[\"rds-1\"],\"unmanaged\":[\"ec2-1\"]}");
    }
}
