package com.ariadne.semantic;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.llm.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TierLabeler {

    private final SemanticGraphService semanticGraphService;
    private final Neo4jClient neo4jClient;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public TierLabeler(
            SemanticGraphService semanticGraphService,
            Neo4jClient neo4jClient,
            LlmGateway llmGateway,
            ObjectMapper objectMapper
    ) {
        this.semanticGraphService = semanticGraphService;
        this.neo4jClient = neo4jClient;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<LabelResult> generateLabels() {
        var graph = semanticGraphService.fetchFullGraph();
        var results = new LinkedHashMap<String, LabelResult>();
        var nodesById = indexNodes(graph.nodes());
        var outgoing = indexOutgoing(graph.edges());

        for (var node : graph.nodes()) {
            var type = node.type();
            switch (type) {
                case "rds" -> put(results, node.id(), "db-tier", "auto", 1.0, "rule");
                case "s3" -> put(results, node.id(), "storage-tier", "auto", 1.0, "rule");
                case "lambda" -> put(results, node.id(), "batch-tier", "auto", 0.95, "rule");
                case "route53", "vpc-group", "subnet-group", "cidr", "sg" -> put(results, node.id(), "network-tier", "auto", 0.95, "rule");
                case "alb" -> {
                    var scheme = value(node.data(), "scheme");
                    put(results, node.id(), "web-tier", "auto", "internet-facing".equalsIgnoreCase(scheme) ? 0.95 : 0.75, "rule");
                    for (var targetId : outgoing.getOrDefault(node.id(), Set.of())) {
                        put(results, targetId, "web-tier", "auto", 0.9, "rule");
                    }
                }
                default -> {
                    // handled by heuristics below
                }
            }
        }

        for (var edge : graph.edges()) {
            if ("LIKELY_USES".equals(edge.type())) {
                var source = nodesById.get(edge.source());
                if (source != null && ("ec2".equals(source.type()) || "ecs-service".equals(source.type()))) {
                    put(results, source.id(), "app-tier", "auto", 0.85, "rule");
                }
            }
        }

        applyLlmSuggestions(graph, results);

        for (var node : graph.nodes()) {
            if (results.containsKey(node.id())) {
                continue;
            }
            var heuristic = classifyHeuristically(node);
            if (heuristic != null) {
                put(results, node.id(), heuristic.tier(), heuristic.confidence(), heuristic.confidenceScore(), heuristic.source());
            }
        }

        persist(results.values());
        return List.copyOf(results.values());
    }

    @Transactional
    public LabelResult updateLabel(String arn, String tier) {
        var normalizedTier = tier == null ? "" : tier.trim();
        if (normalizedTier.isBlank()) {
            throw new IllegalArgumentException("tier must not be blank");
        }

        neo4jClient.query("""
                        MATCH (n:AwsResource {arn: $arn})
                        WHERE coalesce(n.stale, false) = false
                        SET n.tier = $tier,
                            n.tierConfidence = 'manual',
                            n.tierConfidenceScore = 1.0,
                            n.tierSource = 'manual'
                        RETURN n.arn AS arn
                        """)
                .bind(arn).to("arn")
                .bind(normalizedTier).to("tier")
                .fetch()
                .one()
                .orElseThrow(() -> new IllegalArgumentException("resource not found: " + arn));

        return new LabelResult(arn, normalizedTier, "manual", 1.0, "manual");
    }

    @Transactional(readOnly = true)
    public List<LabelResult> currentLabels() {
        return neo4jClient.query("""
                        MATCH (n:AwsResource)
                        WHERE coalesce(n.stale, false) = false
                          AND n.tier IS NOT NULL
                        RETURN n.arn AS arn,
                               n.tier AS tier,
                               coalesce(n.tierConfidence, 'tentative') AS confidence,
                               coalesce(n.tierConfidenceScore, 0.5) AS confidenceScore,
                               coalesce(n.tierSource, 'rule') AS source
                        ORDER BY n.tier, n.name, n.resourceId
                        """)
                .fetch()
                .all()
                .stream()
                .map(row -> new LabelResult(
                        (String) row.get("arn"),
                        (String) row.get("tier"),
                        (String) row.get("confidence"),
                        row.get("confidenceScore") instanceof Number number ? number.doubleValue() : 0.5,
                        (String) row.get("source")
                ))
                .toList();
    }

    private void applyLlmSuggestions(GraphResponse graph, Map<String, LabelResult> results) {
        var unlabeled = graph.nodes().stream()
                .filter(node -> !results.containsKey(node.id()))
                .filter(node -> !node.type().endsWith("-group"))
                .toList();
        if (unlabeled.isEmpty()) {
            return;
        }

        try {
            var response = llmGateway.queryText(buildLlmPrompt(unlabeled, results.values()));
            mergeLlmLabels(results, parseLlmResponse(response, unlabeled));
        } catch (RuntimeException exception) {
            // Phase 3 keeps LLM labeling optional. Heuristics continue to classify any remaining nodes.
        }
    }

    private void persist(Iterable<LabelResult> labels) {
        for (var label : labels) {
            neo4jClient.query("""
                            MATCH (n:AwsResource {arn: $arn})
                            SET n.tier = $tier,
                                n.tierConfidence = $confidence,
                                n.tierConfidenceScore = $confidenceScore,
                                n.tierSource = $source
                            """)
                    .bind(label.arn()).to("arn")
                    .bind(label.tier()).to("tier")
                    .bind(label.confidence()).to("confidence")
                    .bind(label.confidenceScore()).to("confidenceScore")
                    .bind(label.source()).to("source")
                    .run();
        }
    }

    private void mergeLlmLabels(Map<String, LabelResult> results, List<LabelResult> llmLabels) {
        for (var label : llmLabels) {
            results.putIfAbsent(label.arn(), label);
        }
    }

    private List<LabelResult> parseLlmResponse(String rawResponse, List<GraphResponse.GraphNode> unlabeled) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of();
        }

        var cleaned = rawResponse.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();
        try {
            var root = objectMapper.readTree(cleaned);
            if (!root.isArray()) {
                return List.of();
            }

            var nodeTypesByArn = new LinkedHashMap<String, String>();
            for (var node : unlabeled) {
                nodeTypesByArn.put(node.id(), node.type());
            }

            var labels = new ArrayList<LabelResult>();
            for (JsonNode item : root) {
                var arn = text(item, "arn");
                var tier = text(item, "tier");
                if (arn == null || tier == null || !nodeTypesByArn.containsKey(arn) || !isAllowedTier(tier)) {
                    continue;
                }

                var confidenceScore = item.path("confidence").isNumber()
                        ? item.path("confidence").asDouble()
                        : 0.72;
                var normalizedConfidence = confidenceScore >= 0.8 ? "auto" : "tentative";
                labels.add(new LabelResult(
                        arn,
                        tier.trim().toLowerCase(Locale.ROOT),
                        normalizedConfidence,
                        confidenceScore,
                        "llm"
                ));
            }
            return labels;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private LabelResult classifyHeuristically(GraphResponse.GraphNode node) {
        var name = (value(node.data(), "name") + " " + value(node.data(), "resourceId")).toLowerCase(Locale.ROOT);
        if (name.contains("redis") || name.contains("cache")) {
            return new LabelResult(node.id(), "cache-tier", "tentative", 0.72, "heuristic");
        }
        if (name.contains("batch") || name.contains("worker") || name.contains("cron") || name.contains("schedule")) {
            return new LabelResult(node.id(), "batch-tier", "tentative", 0.67, "heuristic");
        }
        if ("ec2".equals(node.type()) || "ecs-service".equals(node.type())) {
            return new LabelResult(node.id(), "app-tier", "tentative", 0.6, "heuristic");
        }
        return null;
    }

    private Map<String, GraphResponse.GraphNode> indexNodes(List<GraphResponse.GraphNode> nodes) {
        var byId = new LinkedHashMap<String, GraphResponse.GraphNode>();
        for (var node : nodes) {
            byId.put(node.id(), node);
        }
        return byId;
    }

    private Map<String, Set<String>> indexOutgoing(List<GraphResponse.GraphEdge> edges) {
        var outgoing = new LinkedHashMap<String, Set<String>>();
        for (var edge : edges) {
            outgoing.computeIfAbsent(edge.source(), ignored -> new LinkedHashSet<>()).add(edge.target());
        }
        return outgoing;
    }

    private void put(Map<String, LabelResult> results, String arn, String tier, String confidence, double score, String source) {
        results.putIfAbsent(arn, new LabelResult(arn, tier, confidence, score, source));
    }

    private String value(Map<String, Object> data, String key) {
        var raw = data.get(key);
        return raw == null ? "" : String.valueOf(raw);
    }

    private String buildLlmPrompt(List<GraphResponse.GraphNode> unlabeled, Iterable<LabelResult> existingLabels) {
        var prompt = new StringBuilder();
        prompt.append("""
                당신은 AWS 인프라 계층 분류 도우미입니다.
                아래 리소스를 web-tier, app-tier, db-tier, cache-tier, batch-tier, storage-tier, network-tier 중 하나로 분류하세요.
                이미 확정된 규칙 기반 레이블은 참고만 하고, 확신이 낮으면 confidence를 0.5~0.7 사이로 낮게 주십시오.
                JSON 배열만 응답하세요.

                이미 분류된 리소스:
                """);
        for (var label : existingLabels) {
            prompt.append("- ").append(label.arn()).append(" -> ").append(label.tier()).append('\n');
        }
        prompt.append("\n분류 대상 리소스:\n");
        for (var node : unlabeled) {
            prompt.append("- arn: ").append(node.id())
                    .append(", type: ").append(node.type())
                    .append(", name: ").append(value(node.data(), "name"))
                    .append(", resourceId: ").append(value(node.data(), "resourceId"))
                    .append(", detail: ").append(value(node.data(), "resourceType"))
                    .append('\n');
        }
        prompt.append("""

                응답 예시:
                [
                  {"arn": "arn:...", "tier": "app-tier", "confidence": 0.74}
                ]
                """);
        return prompt.toString();
    }

    private boolean isAllowedTier(String tier) {
        return Set.of(
                "web-tier",
                "app-tier",
                "db-tier",
                "cache-tier",
                "batch-tier",
                "storage-tier",
                "network-tier"
        ).contains(tier.trim().toLowerCase(Locale.ROOT));
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asText();
    }
}
