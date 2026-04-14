package com.ariadne.collector;

import com.ariadne.config.AwsProperties;
import com.ariadne.graph.node.Vpc;
import com.ariadne.graph.service.GraphPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectorOrchestratorTest {

    @Mock
    private GraphPersistenceService graphPersistenceService;

    @Mock
    private StsClient stsClient;

    @Captor
    private ArgumentCaptor<CollectResult> resultCaptor;

    @Captor
    private ArgumentCaptor<OffsetDateTime> collectedAtCaptor;

    @Captor
    private ArgumentCaptor<Set<String>> managedTypesCaptor;

    @Test
    void keepsSuccessfulCollectorsWhenOneCollectorFails() {
        var successfulCollector = new ResourceCollector() {
            @Override
            public String resourceType() {
                return "VPC";
            }

            @Override
            public CollectResult collect(AwsCollectContext context) {
                var arn = "arn:aws:ec2:%s:%s:vpc/vpc-1234".formatted(context.region(), context.accountId());
                return new CollectResult(
                        List.of(new Vpc(
                                arn,
                                "vpc-1234",
                                "prod-main-vpc",
                                context.region(),
                                context.accountId(),
                                "prod",
                                context.collectedAt(),
                                java.util.Map.of("environment", "prod"),
                                "10.0.0.0/16",
                                false,
                                "available"
                        )),
                        List.of()
                );
            }
        };

        var failingCollector = new ResourceCollector() {
            @Override
            public String resourceType() {
                return "RDS";
            }

            @Override
            public CollectResult collect(AwsCollectContext context) {
                throw new IllegalStateException("boom");
            }
        };

        var orchestrator = new CollectorOrchestrator(
                List.of(failingCollector, successfulCollector),
                graphPersistenceService,
                stsClient,
                new AwsProperties("ap-northeast-2", null, null, null)
        );

        when(stsClient.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder()
                .account("123456789012")
                .build());

        var summary = orchestrator.collectAll();

        verify(graphPersistenceService).save(resultCaptor.capture(), collectedAtCaptor.capture(), managedTypesCaptor.capture());
        assertThat(resultCaptor.getValue().resources()).hasSize(1);
        assertThat(summary.totalResources()).isEqualTo(1);
        assertThat(summary.totalRelationships()).isEqualTo(0);
        assertThat(summary.warnings()).singleElement().asString().contains("Collector RDS failed");
        assertThat(managedTypesCaptor.getValue()).containsExactly("VPC");
        assertThat(collectedAtCaptor.getValue()).isEqualTo(summary.collectedAt());
    }
}
