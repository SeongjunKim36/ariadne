package com.ariadne.llm;

import com.ariadne.security.RedactionEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmDataSanitizerTest {

    private final LlmDataSanitizer sanitizer = new LlmDataSanitizer(new RedactionEngine());

    @Test
    void redactsNestedSensitiveValuesWhilePreservingThePayloadShape() {
        var raw = new GraphData("subgraph:prod", Map.of(
                "resourceId", "i-1234",
                "env", Map.of(
                        "SPRING_PROFILES_ACTIVE", "prod",
                        "DB_PASSWORD", "super-secret"
                ),
                "connections", List.of(
                        Map.of("target", "db.internal", "token", "sk-abc123def456")
                )
        ));

        var sanitized = sanitizer.sanitize(raw, TransmissionLevel.NORMAL);
        @SuppressWarnings("unchecked")
        var env = (Map<String, Object>) sanitized.payload().get("env");
        @SuppressWarnings("unchecked")
        var connections = (List<Map<String, Object>>) sanitized.payload().get("connections");

        assertThat(sanitized.scope()).isEqualTo("subgraph:prod");
        assertThat(sanitized.transmissionLevel()).isEqualTo(TransmissionLevel.NORMAL);
        assertThat(env)
                .containsEntry("SPRING_PROFILES_ACTIVE", "prod")
                .containsEntry("DB_PASSWORD", RedactionEngine.REDACTED);
        assertThat(connections).singleElement();
        assertThat(connections.get(0))
                .containsEntry("target", "db.internal")
                .containsEntry("token", RedactionEngine.REDACTED);
    }

    @Test
    void returnsEmptyPayloadWhenContextDataIsMissing() {
        var sanitized = sanitizer.sanitize(null, TransmissionLevel.STRICT);

        assertThat(sanitized.scope()).isEqualTo("global");
        assertThat(sanitized.transmissionLevel()).isEqualTo(TransmissionLevel.STRICT);
        assertThat(sanitized.payload()).isEmpty();
    }
}
