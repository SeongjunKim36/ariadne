package com.ariadne.llm;

import org.springframework.stereotype.Component;

@Component
class ClaudeClient implements LlmClient {

    @Override
    public String query(LlmRequest request) {
        throw new UnsupportedOperationException(
                "Claude client is not configured yet. Use LlmGateway only after the Phase 3 AI integration is enabled."
        );
    }
}
