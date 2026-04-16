package com.ariadne.audit;

import com.ariadne.api.dto.AuditExplanationResponse;
import com.ariadne.api.dto.AuditFindingResponse;
import com.ariadne.api.dto.AuditReportResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditExplanationServiceTest {

    @Mock
    private LlmAuditAssistant llmAuditAssistant;

    @Test
    void usesLlmAssistantWhenAvailable() {
        var service = new AuditExplanationService(llmAuditAssistant);
        var report = report();
        var llmResponse = new AuditExplanationResponse(
                OffsetDateTime.now(),
                "LLM prioritized internet-facing findings first.",
                List.of("HIGH · SG-001 · prod-api"),
                List.of("Restrict 0.0.0.0/0 ingress")
        );

        when(llmAuditAssistant.explain(report)).thenReturn(llmResponse);

        var response = service.explain(report);

        assertThat(response).isEqualTo(llmResponse);
    }

    @Test
    void fallsBackToDeterministicSummaryWhenLlmFails() {
        var service = new AuditExplanationService(llmAuditAssistant);
        var report = report();

        when(llmAuditAssistant.explain(report)).thenThrow(new IllegalStateException("Claude unavailable"));

        var response = service.explain(report);

        assertThat(response.summary()).contains("최근 감사에서 2건의 finding");
        assertThat(response.priorities()).contains("HIGH · SG-001 · prod-api");
        assertThat(response.actions()).contains("Restrict 0.0.0.0/0 ingress");
    }

    private AuditReportResponse report() {
        return new AuditReportResponse(
                OffsetDateTime.now(),
                2,
                1,
                1,
                0,
                List.of(
                        new AuditFindingResponse(
                                1L,
                                "SG-001",
                                "인터넷 전체 오픈",
                                RiskLevel.HIGH,
                                "security-group",
                                "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-api",
                                "prod-api",
                                "EC2",
                                "arn:aws:ec2:ap-northeast-2:123456789012:security-group/sg-1234",
                                "prod-api-sg",
                                "0.0.0.0/0",
                                "Restrict 0.0.0.0/0 ingress"
                        ),
                        new AuditFindingResponse(
                                2L,
                                "S3-003",
                                "버전관리 미적용",
                                RiskLevel.MEDIUM,
                                "s3",
                                "arn:aws:s3:::prod-assets",
                                "prod-assets",
                                "S3_BUCKET",
                                null,
                                null,
                                null,
                                "Enable bucket versioning"
                        )
                )
        );
    }
}
