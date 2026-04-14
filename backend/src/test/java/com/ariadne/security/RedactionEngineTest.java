package com.ariadne.security;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionEngineTest {

    private final RedactionEngine redactionEngine = new RedactionEngine();

    @Test
    void redactsSensitiveKeysAndValues() {
        var redacted = redactionEngine.redact(Map.of(
                "DB_PASSWORD", "secret123",
                "SPRING_PROFILES_ACTIVE", "prod",
                "JWT_SECRET", "jwt-super-secret",
                "NORMAL_NAME", "safe"
        ));

        assertThat(redacted)
                .containsEntry("DB_PASSWORD", RedactionEngine.REDACTED)
                .containsEntry("SPRING_PROFILES_ACTIVE", "prod")
                .containsEntry("JWT_SECRET", RedactionEngine.REDACTED)
                .containsEntry("NORMAL_NAME", "safe");
    }

    @Test
    void redactsAwsAndApiKeyLikeValuesEvenWhenKeyLooksSafe() {
        var redacted = redactionEngine.redact(Map.of(
                "PUBLIC_ID", "AKIAIOSFODNN7EXAMPLE",
                "CONFIG_SNIPPET", "sk-abc123def456ghi789"
        ));

        assertThat(redacted)
                .containsEntry("PUBLIC_ID", RedactionEngine.REDACTED)
                .containsEntry("CONFIG_SNIPPET", RedactionEngine.REDACTED);
    }

    @Test
    void preservesJdbcShapeWhileMaskingPasswordParameter() {
        var redacted = redactionEngine.redact(Map.of(
                "DATABASE_URL", "jdbc:postgresql://db.internal:5432/app?user=app&password=topsecret&ssl=true"
        ));

        assertThat(redacted.get("DATABASE_URL"))
                .isEqualTo("jdbc:postgresql://db.internal:5432/app?user=app&password=***REDACTED***&ssl=true");
    }

    @Test
    void whitelistModeKeepsAllowedKeysAndMasksOthers() {
        var redacted = redactionEngine.redactWhitelistMode(
                Map.of(
                        "SPRING_PROFILES_ACTIVE", "prod",
                        "DB_PASSWORD", "secret123",
                        "SERVICE_NAME", "ariadne"
                ),
                Set.of("SPRING_PROFILES_ACTIVE")
        );

        assertThat(redacted)
                .containsEntry("SPRING_PROFILES_ACTIVE", "prod")
                .containsEntry("DB_PASSWORD", RedactionEngine.REDACTED)
                .containsEntry("SERVICE_NAME", RedactionEngine.REDACTED);
    }
}
