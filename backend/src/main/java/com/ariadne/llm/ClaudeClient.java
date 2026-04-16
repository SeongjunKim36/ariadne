package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
class ClaudeClient implements LlmClient {

    private final AriadneProperties ariadneProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    ClaudeClient(AriadneProperties ariadneProperties, ObjectMapper objectMapper) {
        this.ariadneProperties = ariadneProperties;
        this.objectMapper = objectMapper;

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(ariadneProperties.getLlm().getTimeoutSeconds()).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(ariadneProperties.getLlm().getTimeoutSeconds()).toMillis());

        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com/v1/messages")
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String query(LlmRequest request) {
        if (ariadneProperties.getLlm().getApiKey() == null) {
            throw new IllegalStateException("CLAUDE_API_KEY is not configured");
        }

        var prompt = request.prompt()
                + "\n\nContext JSON:\n"
                + serialize(request.contextData().payload());
        var body = Map.of(
                "model", ariadneProperties.getLlm().getDefaultModel(),
                "max_tokens", ariadneProperties.getLlm().getMaxOutputTokens(),
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        var response = restClient.post()
                .uri("")
                .header("x-api-key", ariadneProperties.getLlm().getApiKey())
                .header("anthropic-version", "2023-06-01")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.has("content") || response.get("content").isEmpty()) {
            throw new IllegalStateException("Claude returned an empty response");
        }

        return response.get("content").get(0).path("text").asText();
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize Claude request", exception);
        }
    }
}
