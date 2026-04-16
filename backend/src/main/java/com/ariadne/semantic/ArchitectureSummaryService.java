package com.ariadne.semantic;

import com.ariadne.api.dto.ArchitectureSummaryResponse;
import com.ariadne.llm.LlmGateway;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ArchitectureSummaryService {

    private final SemanticGraphService semanticGraphService;
    private final LlmGateway llmGateway;

    public ArchitectureSummaryService(SemanticGraphService semanticGraphService, LlmGateway llmGateway) {
        this.semanticGraphService = semanticGraphService;
        this.llmGateway = llmGateway;
    }

    public ArchitectureSummaryResponse generate(String language) {
        var graph = semanticGraphService.fetchFullGraph();
        var normalizedLanguage = language == null || language.isBlank() ? "ko" : language.toLowerCase(Locale.ROOT);
        var typeCounts = graph.nodes().stream()
                .collect(Collectors.groupingBy(node -> String.valueOf(node.data().getOrDefault("resourceType", node.type())), Collectors.counting()));
        var envCounts = graph.nodes().stream()
                .collect(Collectors.groupingBy(node -> String.valueOf(node.data().getOrDefault("environment", "unknown")), Collectors.counting()));

        var fallback = buildFallbackSummary(normalizedLanguage, graph.metadata().totalNodes(), graph.metadata().totalEdges(), typeCounts, envCounts);
        try {
            var prompt = """
                    아래 AWS 인프라 현황을 %s로 300자 내외로 요약하세요.
                    노드 수: %d
                    엣지 수: %d
                    타입별 개수: %s
                    환경별 개수: %s
                    """.formatted(normalizedLanguage, graph.metadata().totalNodes(), graph.metadata().totalEdges(), typeCounts, envCounts);
            var summary = llmGateway.queryText(prompt);
            return new ArchitectureSummaryResponse(summary, normalizedLanguage, OffsetDateTime.now());
        } catch (RuntimeException exception) {
            return new ArchitectureSummaryResponse(fallback, normalizedLanguage, OffsetDateTime.now());
        }
    }

    private String buildFallbackSummary(String language, int totalNodes, int totalEdges, Map<String, Long> typeCounts, Map<String, Long> envCounts) {
        if ("en".equals(language)) {
            return "The current graph contains %d resources and %d relationships. Main resource groups are %s, while environments are split as %s."
                    .formatted(totalNodes, totalEdges, summarize(typeCounts), summarize(envCounts));
        }
        return "현재 그래프에는 리소스 %d개와 관계 %d개가 있습니다. 주요 리소스 구성은 %s 이고, 환경 분포는 %s 입니다."
                .formatted(totalNodes, totalEdges, summarize(typeCounts), summarize(envCounts));
    }

    private String summarize(Map<String, Long> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(entry -> entry.getKey() + " " + entry.getValue() + "개")
                .collect(Collectors.joining(", "));
    }
}
