package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.springframework.stereotype.Service;

@Service
public class LlmGateway {

    private final LlmClient llmClient;
    private final LlmDataSanitizer sanitizer;
    private final AriadneProperties ariadneProperties;

    public LlmGateway(
            LlmClient llmClient,
            LlmDataSanitizer sanitizer,
            AriadneProperties ariadneProperties
    ) {
        this.llmClient = llmClient;
        this.sanitizer = sanitizer;
        this.ariadneProperties = ariadneProperties;
    }

    public String query(String prompt, GraphData contextData) {
        var transmissionLevel = TransmissionLevel.from(ariadneProperties.getLlm().getTransmissionLevel());
        var sanitizedContext = sanitizer.sanitize(contextData, transmissionLevel);

        // Phase 2 focuses on forcing the gateway boundary first.
        // Field allowlists and audit logging are layered on top in the next steps.
        return llmClient.query(new LlmRequest(prompt, sanitizedContext));
    }
}
