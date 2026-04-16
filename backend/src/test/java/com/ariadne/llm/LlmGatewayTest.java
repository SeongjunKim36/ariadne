package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private LlmDataSanitizer sanitizer;

    @Mock
    private LlmFieldAllowlist fieldAllowlist;

    @Mock
    private LlmAuditLogService auditLogService;

    @Captor
    private ArgumentCaptor<LlmRequest> requestCaptor;

    @Captor
    private ArgumentCaptor<IllegalStateException> exceptionCaptor;

    @Test
    void forcesStrictTransmissionLevelWhenConfigurationIsMissing() {
        var properties = new AriadneProperties();
        var gateway = new LlmGateway(llmClient, sanitizer, fieldAllowlist, auditLogService, properties);
        var graphData = new GraphData("subgraph:vpc-123", Map.of("resourceId", "i-1234"));
        var sanitized = new SanitizedGraphData(
                "subgraph:vpc-123",
                TransmissionLevel.STRICT,
                Map.of("resourceId", "i-1234")
        );

        when(sanitizer.sanitize(graphData, TransmissionLevel.STRICT)).thenReturn(sanitized);
        when(fieldAllowlist.apply(sanitized)).thenReturn(sanitized);
        when(llmClient.query(requestCaptor.capture())).thenReturn("ok");

        var result = gateway.query("prod에 뭐가 돌아가고 있어?", graphData);

        assertThat(result).isEqualTo("ok");
        verify(sanitizer).sanitize(graphData, TransmissionLevel.STRICT);
        verify(fieldAllowlist).apply(sanitized);
        verify(auditLogService).recordSuccess("prod에 뭐가 돌아가고 있어?", sanitized);
        assertThat(requestCaptor.getValue().prompt()).isEqualTo("prod에 뭐가 돌아가고 있어?");
        assertThat(requestCaptor.getValue().contextData()).isEqualTo(sanitized);
    }

    @Test
    void usesConfiguredTransmissionLevelAndNeverPassesRawContextToClient() {
        var properties = new AriadneProperties();
        properties.getLlm().setTransmissionLevel("verbose");

        var gateway = new LlmGateway(llmClient, sanitizer, fieldAllowlist, auditLogService, properties);
        var graphData = new GraphData("subgraph:prod", Map.of(
                "resourceId", "i-1234",
                "DB_PASSWORD", "topsecret"
        ));
        var sanitized = new SanitizedGraphData(
                "subgraph:prod",
                TransmissionLevel.VERBOSE,
                Map.of(
                        "resourceId", "i-1234",
                        "DB_PASSWORD", "***REDACTED***"
                )
        );

        when(sanitizer.sanitize(graphData, TransmissionLevel.VERBOSE)).thenReturn(sanitized);
        when(fieldAllowlist.apply(sanitized)).thenReturn(sanitized);
        when(llmClient.query(requestCaptor.capture())).thenReturn("safe");

        var result = gateway.query("RDS 연결을 설명해줘", graphData);

        assertThat(result).isEqualTo("safe");
        verify(sanitizer).sanitize(graphData, TransmissionLevel.VERBOSE);
        verify(fieldAllowlist).apply(sanitized);
        verify(auditLogService).recordSuccess("RDS 연결을 설명해줘", sanitized);
        assertThat(requestCaptor.getValue().contextData()).isNotNull();
        assertThat(requestCaptor.getValue().contextData().payload())
                .containsEntry("DB_PASSWORD", "***REDACTED***")
                .doesNotContainValue("topsecret");
    }

    @Test
    void fallsBackToStrictWhenTransmissionLevelIsInvalid() {
        var properties = new AriadneProperties();
        properties.getLlm().setTransmissionLevel("surprise");

        var gateway = new LlmGateway(llmClient, sanitizer, fieldAllowlist, auditLogService, properties);
        var graphData = new GraphData("global", Map.of("name", "prod-api"));
        var sanitized = new SanitizedGraphData("global", TransmissionLevel.STRICT, Map.of("name", "prod-api"));

        when(sanitizer.sanitize(graphData, TransmissionLevel.STRICT)).thenReturn(sanitized);
        when(fieldAllowlist.apply(sanitized)).thenReturn(sanitized);
        when(llmClient.query(requestCaptor.capture())).thenReturn("strict");

        var result = gateway.query(null, graphData);

        assertThat(result).isEqualTo("strict");
        verify(sanitizer).sanitize(graphData, TransmissionLevel.STRICT);
        verify(fieldAllowlist).apply(sanitized);
        verify(auditLogService).recordSuccess(null, sanitized);
        assertThat(requestCaptor.getValue().prompt()).isEmpty();
        assertThat(requestCaptor.getValue().contextData().transmissionLevel()).isEqualTo(TransmissionLevel.STRICT);
    }

    @Test
    void recordsFailureWhenClientThrows() {
        var properties = new AriadneProperties();
        var gateway = new LlmGateway(llmClient, sanitizer, fieldAllowlist, auditLogService, properties);
        var graphData = new GraphData("subgraph:prod", Map.of("resourceId", "i-1234"));
        var sanitized = new SanitizedGraphData("subgraph:prod", TransmissionLevel.STRICT, Map.of("resourceId", "EC2-1"));

        when(sanitizer.sanitize(graphData, TransmissionLevel.STRICT)).thenReturn(sanitized);
        when(fieldAllowlist.apply(sanitized)).thenReturn(sanitized);
        when(llmClient.query(requestCaptor.capture())).thenThrow(new IllegalStateException("Claude unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.query("help", graphData))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Claude unavailable");

        verify(auditLogService).recordFailure(org.mockito.ArgumentMatchers.eq("help"), org.mockito.ArgumentMatchers.eq(sanitized), exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue()).hasMessageContaining("Claude unavailable");
    }
}
