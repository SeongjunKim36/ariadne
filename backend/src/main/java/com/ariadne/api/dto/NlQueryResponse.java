package com.ariadne.api.dto;

import java.util.List;
import java.util.Map;

public record NlQueryResponse(
        boolean success,
        String generatedCypher,
        List<Map<String, Object>> results,
        String explanation,
        GraphResponse subgraph,
        boolean truncated,
        Integer totalEstimate,
        String error,
        List<String> suggestions,
        boolean clarificationNeeded,
        List<ResourceSummaryResponse> clarificationOptions
) {

    public static NlQueryResponse error(String message, List<String> suggestions) {
        return new NlQueryResponse(false, null, List.of(), null, null, false, null, message, suggestions, false, List.of());
    }
}
