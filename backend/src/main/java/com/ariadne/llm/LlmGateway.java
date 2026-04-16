package com.ariadne.llm;

import com.ariadne.config.AriadneProperties;
import org.springframework.stereotype.Service;

@Service
public class LlmGateway {

    private final LlmClient llmClient;
    private final LlmDataSanitizer sanitizer;
    private final LlmFieldAllowlist fieldAllowlist;
    private final LlmAuditLogService auditLogService;
    private final AriadneProperties ariadneProperties;

    public LlmGateway(
            LlmClient llmClient,
            LlmDataSanitizer sanitizer,
            LlmFieldAllowlist fieldAllowlist,
            LlmAuditLogService auditLogService,
            AriadneProperties ariadneProperties
    ) {
        this.llmClient = llmClient;
        this.sanitizer = sanitizer;
        this.fieldAllowlist = fieldAllowlist;
        this.auditLogService = auditLogService;
        this.ariadneProperties = ariadneProperties;
    }

    public String query(String prompt, GraphData contextData) {
        var transmissionLevel = TransmissionLevel.from(ariadneProperties.getLlm().getTransmissionLevel());
        var sanitizedContext = sanitizer.sanitize(contextData, transmissionLevel);
        var allowlistedContext = fieldAllowlist.apply(sanitizedContext);

        try {
            var response = llmClient.query(new LlmRequest(prompt, allowlistedContext));
            auditLogService.recordSuccess(prompt, allowlistedContext);
            return response;
        } catch (RuntimeException exception) {
            auditLogService.recordFailure(prompt, allowlistedContext, exception);
            throw exception;
        }
    }
}
