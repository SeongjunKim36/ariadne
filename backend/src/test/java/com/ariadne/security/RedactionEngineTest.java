package com.ariadne.security;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.RepositoryCredentials;
import software.amazon.awssdk.services.ecs.model.Secret;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                "CONFIG_SNIPPET", "sk-abc123def456ghi789",
                "BASE64ISH", "YWJjZGVmZ2hpamtsbW5vcA=="
        ));

        assertThat(redacted)
                .containsEntry("PUBLIC_ID", RedactionEngine.REDACTED)
                .containsEntry("CONFIG_SNIPPET", RedactionEngine.REDACTED)
                .containsEntry("BASE64ISH", RedactionEngine.REDACTED);
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
    void preservesUriShapeWhileMaskingUserInfoAndSensitiveQueryParameters() {
        var redacted = redactionEngine.redact(Map.of(
                "EXTERNAL_URL", "postgresql://app:topsecret@db.internal:5432/app?token=abcd1234&sslmode=require"
        ));

        assertThat(redacted.get("EXTERNAL_URL"))
                .isEqualTo("postgresql://app:***REDACTED***@db.internal:5432/app?token=***REDACTED***&sslmode=require");
    }

    @Test
    void redactsPrivateKeysSessionCredentialsAndBearerTokens() {
        var redacted = redactionEngine.redact(Map.of(
                "TEMP_ACCESS_KEY", "ASIAIOSFODNN7EXAMPLE",
                "AUTHORIZATION", "Bearer abcdefghijklmnopqrstuvwxyz123456",
                "TLS_PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----\nabc123\n-----END PRIVATE KEY-----"
        ));

        assertThat(redacted)
                .containsEntry("TEMP_ACCESS_KEY", RedactionEngine.REDACTED)
                .containsEntry("AUTHORIZATION", RedactionEngine.REDACTED)
                .containsEntry("TLS_PRIVATE_KEY", RedactionEngine.REDACTED);
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

    @Test
    void redactsEcsContainerSecretsRepositoryCredentialsAndLogConfiguration() {
        var containerDefinition = ContainerDefinition.builder()
                .name("web")
                .environment(
                        KeyValuePair.builder().name("SPRING_PROFILES_ACTIVE").value("prod").build(),
                        KeyValuePair.builder().name("DB_PASSWORD").value("super-secret").build()
                )
                .secrets(Secret.builder().name("DB_PASSWORD").valueFrom("arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:db").build())
                .repositoryCredentials(RepositoryCredentials.builder()
                        .credentialsParameter("arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:repo")
                        .build())
                .logConfiguration(LogConfiguration.builder()
                        .logDriver("awslogs")
                        .options(Map.of(
                                "awslogs-group", "/ecs/prod-api",
                                "splunk-token", "topsecret-token"
                        ))
                        .secretOptions(Secret.builder().name("splunk-token").valueFrom("arn:aws:secretsmanager:...").build())
                        .build())
                .build();

        var redacted = redactionEngine.redactContainerDefinition(containerDefinition);

        assertThat(redacted.environment())
                .extracting(KeyValuePair::name, KeyValuePair::value)
                .containsExactlyInAnyOrder(
                        tuple("SPRING_PROFILES_ACTIVE", "prod"),
                        tuple("DB_PASSWORD", RedactionEngine.REDACTED)
                );
        assertThat(redacted.secrets()).isEmpty();
        assertThat(redacted.repositoryCredentials()).isNull();
        assertThat(redacted.logConfiguration().options())
                .containsEntry("awslogs-group", "/ecs/prod-api")
                .containsEntry("splunk-token", RedactionEngine.REDACTED);
        assertThat(redacted.logConfiguration().secretOptions()).isEmpty();
    }
}
