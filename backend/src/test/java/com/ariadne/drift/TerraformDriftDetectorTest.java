package com.ariadne.drift;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.config.AriadneProperties;
import com.ariadne.notify.NotificationService;
import com.ariadne.snapshot.Snapshot;
import com.ariadne.snapshot.SnapshotService;
import com.ariadne.snapshot.SnapshotTrigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TerraformDriftDetectorTest {

    @Mock
    private SnapshotService snapshotService;

    @Mock
    private TerraformDriftRunRepository terraformDriftRunRepository;

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void reportsMissingModifiedAndUnmanagedResources() throws Exception {
        var parser = new TerraformStateParser(objectMapper);
        var detector = new TerraformDriftDetector(
                parser,
                snapshotService,
                terraformDriftRunRepository,
                objectMapper,
                new AriadneProperties(),
                notificationService
        );

        var actualGraph = new GraphResponse(
                List.of(
                        node("arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234", Map.of(
                                "arn", "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234",
                                "resourceType", "EC2",
                                "resourceId", "i-1234",
                                "name", "prod-web-a",
                                "instanceType", "t3.large"
                        )),
                        node("arn:aws:ec2:ap-northeast-2:123456789012:instance/i-extra", Map.of(
                                "arn", "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-extra",
                                "resourceType", "EC2",
                                "resourceId", "i-extra",
                                "name", "prod-worker-a",
                                "instanceType", "t3.micro"
                        ))
                ),
                List.of(),
                new GraphResponse.GraphMetadata(2, 0, OffsetDateTime.parse("2026-04-16T09:30:00Z"), 900)
        );
        var snapshot = new Snapshot(
                OffsetDateTime.parse("2026-04-16T09:30:00Z"),
                "123456789012",
                "ap-northeast-2",
                2,
                0,
                900,
                SnapshotTrigger.SCHEDULED,
                null,
                objectMapper.writeValueAsString(actualGraph),
                "{}"
        );

        when(snapshotService.latestSnapshot()).thenReturn(Optional.of(snapshot));
        when(snapshotService.readGraph(snapshot)).thenReturn(actualGraph);
        when(terraformDriftRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var run = detector.detect(new com.ariadne.api.dto.TerraformDriftDetectionRequest(
                null,
                """
                        {
                          "resources": [
                            {
                              "type": "aws_instance",
                              "name": "web",
                              "instances": [
                                {
                                  "attributes": {
                                    "arn": "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-1234",
                                    "id": "i-1234",
                                    "instance_type": "t3.micro",
                                    "tags": {
                                      "Name": "prod-web-a"
                                    }
                                  }
                                }
                              ]
                            },
                            {
                              "type": "aws_instance",
                              "name": "worker_missing",
                              "instances": [
                                {
                                  "attributes": {
                                    "arn": "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-missing",
                                    "id": "i-missing",
                                    "instance_type": "t3.micro",
                                    "tags": {
                                      "Name": "prod-worker-missing"
                                    }
                                  }
                                }
                              ]
                            }
                          ]
                        }
                        """
        ));

        var items = detector.readItems(run);

        assertThat(run.getTotalItems()).isEqualTo(3);
        assertThat(run.getMissingCount()).isEqualTo(1);
        assertThat(run.getModifiedCount()).isEqualTo(1);
        assertThat(run.getUnmanagedCount()).isEqualTo(1);
        assertThat(items)
                .extracting(DriftItem::status, DriftItem::resourceId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DriftItemStatus.MODIFIED, "i-1234"),
                        org.assertj.core.groups.Tuple.tuple(DriftItemStatus.MISSING, "i-missing"),
                        org.assertj.core.groups.Tuple.tuple(DriftItemStatus.UNMANAGED, "i-extra")
                );
        verify(snapshotService, never()).capturePartialRefresh(any());
        verify(notificationService).notifyDriftReport(run);
    }

    private GraphResponse.GraphNode node(String id, Map<String, Object> data) {
        return new GraphResponse.GraphNode(id, "ec2", data, null);
    }
}
