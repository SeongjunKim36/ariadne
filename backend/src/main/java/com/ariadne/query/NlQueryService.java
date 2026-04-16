package com.ariadne.query;

import com.ariadne.api.dto.GraphResponse;
import com.ariadne.api.dto.NlQueryResponse;
import com.ariadne.api.dto.ResourceSummaryResponse;
import com.ariadne.llm.LlmGateway;
import com.ariadne.semantic.SemanticGraphService;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class NlQueryService {

    private static final int QUERY_LIMIT = 100;

    private final LlmGateway llmGateway;
    private final SchemaContextBuilder schemaContextBuilder;
    private final CypherValidator cypherValidator;
    private final Neo4jClient neo4jClient;
    private final QueryTemplateEngine queryTemplateEngine;
    private final SemanticGraphService semanticGraphService;

    public NlQueryService(
            LlmGateway llmGateway,
            SchemaContextBuilder schemaContextBuilder,
            CypherValidator cypherValidator,
            Neo4jClient neo4jClient,
            QueryTemplateEngine queryTemplateEngine,
            SemanticGraphService semanticGraphService
    ) {
        this.llmGateway = llmGateway;
        this.schemaContextBuilder = schemaContextBuilder;
        this.cypherValidator = cypherValidator;
        this.neo4jClient = neo4jClient;
        this.queryTemplateEngine = queryTemplateEngine;
        this.semanticGraphService = semanticGraphService;
    }

    public NlQueryResponse query(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return NlQueryResponse.error("질문이 비어 있습니다. 예시 질의 중 하나를 사용해 주세요.", queryTemplateEngine.examples());
        }

        var templateCypher = queryTemplateEngine.generateCypher(userQuery);
        var generatedCypher = templateCypher.orElseGet(() -> generateFromLlm(userQuery).orElse(null));
        if (generatedCypher == null) {
            return NlQueryResponse.error("이 질문을 Cypher로 변환하지 못했습니다. 더 구체적으로 질문해 주세요.", queryTemplateEngine.examples());
        }

        var validation = cypherValidator.validate(generatedCypher);
        if (!validation.valid()) {
            var repaired = retryWithValidationFeedback(userQuery, generatedCypher, validation.error())
                    .or(() -> templateCypher)
                    .orElse(null);
            if (repaired == null) {
                return NlQueryResponse.error(validation.error(), queryTemplateEngine.examples());
            }
            generatedCypher = repaired;
            validation = cypherValidator.validate(generatedCypher);
            if (!validation.valid()) {
                return NlQueryResponse.error(validation.error(), queryTemplateEngine.examples());
            }
        }

        var results = executeSafely(generatedCypher);
        if (results.isEmpty()) {
            var emptyExplanation = explainEmpty(userQuery);
            return new NlQueryResponse(
                    true,
                    generatedCypher,
                    List.of(),
                    emptyExplanation,
                    new GraphResponse(List.of(), List.of(), semanticGraphService.fetchFullGraph().metadata()),
                    false,
                    0,
                    null,
                    queryTemplateEngine.examples(),
                    false,
                    List.of()
            );
        }

        var clarificationOptions = clarificationOptions(results);
        var clarificationNeeded = clarificationOptions.size() > 1 && needsClarification(userQuery, results);
        var subgraph = semanticGraphService.buildSubgraph(extractResultArns(results), 1);
        var truncated = results.size() >= QUERY_LIMIT;

        return new NlQueryResponse(
                true,
                generatedCypher,
                results,
                explain(userQuery, results),
                subgraph,
                truncated,
                truncated ? QUERY_LIMIT : results.size(),
                null,
                List.of(),
                clarificationNeeded,
                clarificationOptions
        );
    }

    public List<String> examples() {
        return queryTemplateEngine.examples();
    }

    private Optional<String> generateFromLlm(String userQuery) {
        try {
            return Optional.of(llmGateway.queryText(buildPrompt(userQuery)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> retryWithValidationFeedback(String userQuery, String cypher, String error) {
        try {
            return Optional.of(llmGateway.queryText("""
                    이전 Cypher가 검증에 실패했습니다.

                    원래 질문:
                    %s

                    실패한 Cypher:
                    %s

                    실패 이유:
                    %s

                    아래 실제 스키마만 사용해서 읽기 전용 Cypher를 다시 작성하세요.
                    %s
                    """.formatted(userQuery, cypher, error, schemaContextBuilder.buildContext())));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String buildPrompt(String userQuery) {
        return """
                당신은 Neo4j Cypher 쿼리 전문가입니다.
                아래 실제 데이터베이스 스키마만 사용해서 읽기 전용 Cypher를 생성하세요.
                쓰기 구문(CREATE, MERGE, DELETE, SET, CALL)은 금지입니다.
                LIMIT 25를 기본으로 사용하세요.

                %s

                사용자 질문:
                %s

                설명 없이 Cypher만 응답하세요.
                """.formatted(schemaContextBuilder.buildContext(), userQuery);
    }

    private List<Map<String, Object>> executeSafely(String generatedCypher) {
        var safeCypher = generatedCypher.toUpperCase(Locale.ROOT).contains("LIMIT")
                ? generatedCypher
                : generatedCypher + " LIMIT " + QUERY_LIMIT;
        return List.copyOf(neo4jClient.query(safeCypher).fetch().all());
    }

    private String explain(String userQuery, List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return null;
        }
        var resourceNames = results.stream()
                .map(row -> stringValue(row, "resourceName"))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(5)
                .toList();
        if (!resourceNames.isEmpty()) {
            return "'%s' 질문에 대해 %d건을 찾았습니다. 대표 결과는 %s 입니다."
                    .formatted(userQuery, results.size(), String.join(", ", resourceNames));
        }
        return "'%s' 질문에 대해 %d건의 결과를 반환했습니다.".formatted(userQuery, results.size());
    }

    private String explainEmpty(String userQuery) {
        var normalized = userQuery.toLowerCase(Locale.ROOT);
        if (normalized.contains("prod") && normalized.contains("staging")) {
            return "현재 그래프에서는 prod/staging 환경 태그가 없거나 비교 가능한 리소스가 없습니다. 먼저 환경 태그 수집 상태를 확인하거나 이름 패턴으로 다시 질의해 보세요.";
        }
        if (normalized.contains("0.0.0.0/0") || normalized.contains("위험한")) {
            return "조건에 맞는 공개 SG를 찾지 못했습니다. 현재 계정에는 해당 유형의 노출이 없거나, 검색 조건을 더 좁혀야 할 수 있습니다.";
        }
        return "조건에 맞는 리소스를 찾지 못했습니다. 리소스 이름, 환경, 타입을 조금 더 구체적으로 적어보세요.";
    }

    private Set<String> extractResultArns(List<Map<String, Object>> results) {
        var arns = new LinkedHashSet<String>();
        for (var row : results) {
            for (var value : row.values()) {
                if (value instanceof String stringValue && stringValue.startsWith("arn:aws:")) {
                    arns.add(stringValue);
                }
            }
        }
        return Set.copyOf(arns);
    }

    private List<ResourceSummaryResponse> clarificationOptions(List<Map<String, Object>> results) {
        var options = new ArrayList<ResourceSummaryResponse>();
        for (var row : results) {
            var arn = stringValue(row, "resourceArn");
            var name = stringValue(row, "resourceName");
            var resourceType = stringValue(row, "resourceType");
            var environment = stringValue(row, "environment");
            if (arn == null || name == null || resourceType == null) {
                continue;
            }
            options.add(new ResourceSummaryResponse(arn, name, resourceType, environment));
        }
        return options.stream().distinct().toList();
    }

    private boolean needsClarification(String userQuery, List<Map<String, Object>> results) {
        var normalized = userQuery.toLowerCase(Locale.ROOT);
        if (normalized.contains("prod") || normalized.contains("staging") || normalized.contains("dev")) {
            return false;
        }
        return results.stream()
                .map(row -> stringValue(row, "resourceName"))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count() == 1;
    }

    private String stringValue(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
