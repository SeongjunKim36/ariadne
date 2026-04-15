package com.ariadne.llm;

import java.util.Map;

record LlmRequest(
        String prompt,
        SanitizedGraphData contextData
) {

    LlmRequest {
        prompt = prompt == null ? "" : prompt;
        contextData = contextData == null
                ? new SanitizedGraphData("global", TransmissionLevel.STRICT, Map.of())
                : contextData;
    }
}
