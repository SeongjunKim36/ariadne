# Phase 3: AI 의미층 — 상세 기획서

> 상위 문서: [project-a-infra-mapper.md](../project-a-infra-mapper.md)
> 선행 조건: Phase 2 완료 (상세 수집 + 보안 하드닝)
> 예상 기간: 3-4주 · **포트폴리오 하이라이트 구간**
> 구현 상태: **완료 (2026-04-16)** — 감사 엔진, NL Query, Tier 레이블링, 감사 설명, 아키텍처 요약, 실계정 검증 반영

---

## 1. 목표

수집된 인프라 그래프 위에 **의미(semantics)**를 얹는다. 두 가지 축으로 구성:

1. **규칙 기반 보안 감사** — 결정론적, 오탐 없음, 기반 엔진
2. **LLM 의미층** — 자연어 질의, 자동 레이블링, 감사 결과 설명

### 종료 조건

- [x] 규칙 기반 감사 엔진이 SG/IAM 위험 항목을 High/Medium/Low로 보고
- [x] "결제 관련 인프라 보여줘" 같은 자연어 질의 → 관련 서브그래프 반환
- [x] LLM이 인프라를 "웹/앱/DB/캐시/배치" 계층으로 자동 레이블링
- [x] 5대 질문 #3("prod vs staging 차이") + #4("위험한 SG") 에 답 가능
- [x] LLM 환각이 사용자에게 도달하지 않음 (Cypher 검증 레이어)

---

## 2. 규칙 기반 보안 감사 엔진 (먼저 구현)

LLM보다 먼저 구현하는 이유: 결정론적이고 오탐이 없으며, LLM 감사 보조의 입력이 됨.

### 2.1 감사 규칙 정의

#### Security Group 규칙

| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| SG-001 | 인터넷 전체 오픈 | inbound 0.0.0.0/0 on any port | HIGH |
| SG-002 | SSH 전체 오픈 | inbound 0.0.0.0/0 on port 22 | HIGH |
| SG-003 | RDP 전체 오픈 | inbound 0.0.0.0/0 on port 3389 | HIGH |
| SG-004 | DB 포트 퍼블릭 | inbound 0.0.0.0/0 on 3306/5432/1521/27017 | HIGH |
| SG-005 | 과도한 CIDR | inbound /8 이하 범위 | MEDIUM |
| SG-006 | 아웃바운드 전체 오픈 | outbound 0.0.0.0/0 on all ports | LOW |
| SG-007 | 미사용 SG | 어떤 리소스에도 연결되지 않은 SG | LOW |
| SG-008 | 과다 규칙 | 인바운드 규칙 50개 초과 | MEDIUM |

#### IAM 규칙 (Phase 2에서 IAM 수집 시)

| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| IAM-001 | 와일드카드 Action | Action에 "*" 포함 | HIGH |
| IAM-002 | 와일드카드 Resource | Resource에 "*" 포함 + Action이 write 계열 | HIGH |
| IAM-003 | 교차 계정 assume | trust policy에 외부 계정 ID | MEDIUM |
| IAM-004 | 미사용 역할 | 90일 이상 미사용 (LastUsedDate 기반) | LOW |

#### 네트워크 규칙

| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| NET-001 | RDS 퍼블릭 접근 | RDS가 public subnet + public SG | HIGH |
| NET-002 | EC2 퍼블릭 IP + 넓은 SG | publicIp 있고 SG-001 해당 | HIGH |
| NET-003 | ALB에 HTTPS 없음 | ALB 리스너에 443 없음 | MEDIUM |

#### S3 규칙

| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| S3-001 | 퍼블릭 접근 차단 안 됨 | publicAccessBlocked = false | HIGH |
| S3-002 | 암호화 미적용 | encryptionType = "none" | MEDIUM |
| S3-003 | 버전관리 미적용 | versioningEnabled = false | LOW |

### 2.2 감사 엔진 아키텍처

```java
public interface AuditRule {
    String ruleId();
    String name();
    String description();
    RiskLevel riskLevel();      // HIGH, MEDIUM, LOW
    String category();          // "security-group", "iam", "network", "s3"

    /**
     * Neo4j에서 위반 리소스를 찾는 Cypher 쿼리.
     * 결과는 위반 리소스 ARN 목록.
     */
    String cypher();

    /** 위반 사항에 대한 설명 템플릿 */
    String remediationHint();
}
```

```java
@Component
public class SgInternetOpenRule implements AuditRule {

    @Override public String ruleId() { return "SG-001"; }
    @Override public String name() { return "인터넷 전체 오픈"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
    @Override public String category() { return "security-group"; }

    @Override
    public String cypher() {
        return """
            MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[:ALLOWS_TO]->(sg:SecurityGroup)
            MATCH (resource)-[:HAS_SG]->(sg)
            RETURN sg.arn AS sgArn, sg.name AS sgName,
                   resource.arn AS resourceArn, resource.name AS resourceName,
                   resource.resourceType AS resourceType
            """;
    }

    @Override
    public String remediationHint() {
        return "이 보안 그룹은 인터넷 전체(0.0.0.0/0)에서 접근을 허용합니다. " +
               "특정 IP/CIDR 범위로 제한하거나, 불필요한 포트를 닫으세요.";
    }
}
```

### 2.3 감사 실행 및 보고서

```java
@Service
@RequiredArgsConstructor
public class AuditEngine {

    private final List<AuditRule> rules;   // Spring이 모든 AuditRule 빈 주입
    private final Neo4jClient neo4jClient;

    public AuditReport runFullAudit() {
        List<AuditFinding> findings = new ArrayList<>();

        for (AuditRule rule : rules) {
            Collection<Map<String, Object>> results =
                neo4jClient.query(rule.cypher()).fetch().all();

            for (Map<String, Object> row : results) {
                findings.add(AuditFinding.builder()
                    .ruleId(rule.ruleId())
                    .ruleName(rule.name())
                    .riskLevel(rule.riskLevel())
                    .category(rule.category())
                    .resourceArn((String) row.get("resourceArn"))
                    .resourceName((String) row.get("resourceName"))
                    .remediationHint(rule.remediationHint())
                    .build());
            }
        }

        return AuditReport.builder()
            .runAt(Instant.now())
            .totalFindings(findings.size())
            .highCount(countByLevel(findings, RiskLevel.HIGH))
            .mediumCount(countByLevel(findings, RiskLevel.MEDIUM))
            .lowCount(countByLevel(findings, RiskLevel.LOW))
            .findings(findings)
            .build();
    }
}
```

### 2.4 감사 API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/audit/run` | 감사 실행 |
| GET | `/api/audit/latest` | 최근 감사 결과 |
| GET | `/api/audit/findings?level=HIGH` | 위험도별 필터 |
| GET | `/api/audit/rules` | 등록된 규칙 목록 |

### 2.5 감사 결과 UI

- 대시보드: HIGH/MEDIUM/LOW 카운트 카드 + 도넛 차트
- 카테고리별 그룹화 (SG / IAM / Network / S3)
- 각 finding 클릭 → 해당 리소스로 토폴로지 뷰 이동 + 위반 엣지 하이라이트
- remediation hint 표시

---

## 3. LLM 의미층

### 3.1 자동 레이블링

수집된 리소스에 **계층(tier)** 레이블을 자동 부여한다.

#### 레이블 종류

| 레이블 | 판단 근거 |
|---|---|
| `web-tier` | ALB에서 트래픽 받는 EC2/ECS, Route53 연결, 포트 80/443 |
| `app-tier` | web-tier에서 트래픽 받지만 외부 직접 노출 없음 |
| `db-tier` | RDS, ElastiCache (향후), DB 포트 노출 EC2 |
| `cache-tier` | ElastiCache, Redis 포트(6379) 사용 리소스 |
| `batch-tier` | Lambda, 스케줄 기반 ECS 태스크 |
| `storage-tier` | S3 버킷 |
| `network-tier` | VPC, Subnet, Route53 Zone |

#### 구현: 규칙 우선 → LLM 보조

```java
@Service
public class TierLabeler {

    /**
     * 1단계: 규칙 기반 레이블링 (확실한 것만)
     */
    public Map<String, String> labelByRules(GraphData graph) {
        Map<String, String> labels = new HashMap<>();

        // RDS → db-tier (확실)
        graph.nodesByType("RdsInstance").forEach(n -> labels.put(n.getArn(), "db-tier"));

        // S3 → storage-tier (확실)
        graph.nodesByType("S3Bucket").forEach(n -> labels.put(n.getArn(), "storage-tier"));

        // ALB → web-tier (확실)
        graph.nodesByType("LoadBalancer")
            .filter(lb -> "internet-facing".equals(lb.getScheme()))
            .forEach(n -> labels.put(n.getArn(), "web-tier"));

        // ALB의 타겟 → web-tier
        graph.edgesByType("ROUTES_TO")
            .filter(e -> labels.getOrDefault(e.getSource(), "").equals("web-tier"))
            .forEach(e -> labels.putIfAbsent(e.getTarget(), "web-tier"));

        // Lambda → batch-tier (기본값, 트리거 기반으로 변경 가능)
        graph.nodesByType("LambdaFunction").forEach(n ->
            labels.putIfAbsent(n.getArn(), "batch-tier"));

        return labels;
    }

    /**
     * 2단계: 규칙으로 분류 안 된 리소스만 LLM에게 질의
     */
    public Map<String, String> labelByLlm(
            GraphData graph, Map<String, String> ruleLabels) {
        List<AwsResource> unlabeled = graph.allNodes().stream()
            .filter(n -> !ruleLabels.containsKey(n.getArn()))
            .toList();

        if (unlabeled.isEmpty()) return ruleLabels;

        // LLM에게 컨텍스트 + 미분류 리소스 전달
        String prompt = buildLabelingPrompt(unlabeled, ruleLabels);
        LlmResponse response = claudeClient.query(prompt);
        Map<String, String> llmLabels = parseLabelingResponse(response);

        // 병합 (규칙 기반이 우선)
        Map<String, String> merged = new HashMap<>(ruleLabels);
        llmLabels.forEach(merged::putIfAbsent);
        return merged;
    }
}
```

#### 레이블링 프롬프트 예시

```
당신은 AWS 인프라 분석 전문가입니다.
아래 AWS 리소스들을 다음 계층 중 하나로 분류해주세요:
web-tier, app-tier, db-tier, cache-tier, batch-tier, storage-tier, network-tier

이미 분류된 리소스 (참고용):
- web-server-1 (EC2, t3.medium) → web-tier
- dongne-prod-db (RDS, PostgreSQL) → db-tier

분류가 필요한 리소스:
- worker-1 (EC2, t3.small, SG: internal-only, 포트 8080)
- data-processor (Lambda, Python 3.12, S3 트리거)

JSON 형식으로 응답하세요:
{"arn1": "tier-label", "arn2": "tier-label"}
```

### 3.2 자연어 질의 (NL Query)

사용자가 자연어로 인프라를 질의하면 Cypher로 변환하여 실행.

#### 아키텍처

```
사용자 질의
    ↓
[NlQueryService]
    ↓
1. 스키마 컨텍스트 생성 (현재 Neo4j 스키마)
    ↓
2. Claude API에 질의 → Cypher 생성
    ↓
3. [CypherValidator] — 스키마 대조 검증
    ├── 통과 → Neo4j 실행 → 결과 반환
    └── 실패 → 1회 재시도 (오류 피드백) → 재실패 시 에러 응답
    ↓
4. 결과를 자연어 + 서브그래프로 포매팅
```

#### 스키마 컨텍스트 생성

```java
@Service
@RequiredArgsConstructor
public class SchemaContextBuilder {

    private final Neo4jClient neo4jClient;

    /**
     * 현재 Neo4j의 실제 스키마를 추출하여 LLM에 제공할 컨텍스트 생성.
     * 환각 방지의 핵심 — LLM이 존재하지 않는 레이블/관계를 사용하지 못하게 함.
     */
    public String buildContext() {
        // 노드 레이블 + 프로퍼티
        var nodeLabels = neo4jClient.query(
            "CALL db.schema.nodeTypeProperties()").fetch().all();

        // 관계 타입 + 프로퍼티
        var relTypes = neo4jClient.query(
            "CALL db.schema.relTypeProperties()").fetch().all();

        // 실제 데이터 통계 (어떤 레이블이 몇 개인지)
        var stats = neo4jClient.query(
            "MATCH (n) RETURN labels(n) AS labels, count(n) AS cnt").fetch().all();

        return formatSchemaContext(nodeLabels, relTypes, stats);
    }
}
```

#### Cypher 생성 프롬프트

```
당신은 Neo4j Cypher 쿼리 전문가입니다.
사용자의 자연어 질문을 Cypher 쿼리로 변환하세요.

## 데이터베이스 스키마 (이 레이블과 관계만 사용하세요)

노드 레이블:
- Ec2Instance (arn, resourceId, name, instanceType, state, privateIp, publicIp, environment)
- RdsInstance (arn, resourceId, name, engine, endpoint, status, environment)
- Vpc (arn, resourceId, name, cidrBlock)
- SecurityGroup (arn, resourceId, name, groupId)
- LoadBalancer (arn, resourceId, name, type, scheme, dnsName)
- EcsService (arn, resourceId, name, runningCount, launchType)
- S3Bucket (arn, resourceId, name, publicAccessBlocked)
- LambdaFunction (arn, resourceId, name, runtime)

관계:
- BELONGS_TO (Ec2Instance→Subnet, Subnet→Vpc, RdsInstance→Subnet)
- HAS_SG (Ec2Instance→SecurityGroup, RdsInstance→SecurityGroup, ...)
- ROUTES_TO (LoadBalancer→Ec2Instance, LoadBalancer→EcsService)
- ALLOWS_FROM (SecurityGroup→SecurityGroup)
- RUNS_IN (EcsService→EcsCluster)

## 규칙
- 위 스키마에 없는 레이블이나 관계를 사용하지 마세요.
- RETURN 절에 의미 있는 alias를 사용하세요.
- 결과가 너무 많을 수 있으면 LIMIT 25를 붙이세요.

## 사용자 질문
{user_query}

Cypher 쿼리만 응답하세요. 설명 없이 쿼리만.
```

#### Cypher 검증기 (환각 방지)

```java
@Component
@RequiredArgsConstructor
public class CypherValidator {

    private final SchemaContextBuilder schemaBuilder;

    public record ValidationResult(boolean valid, String error) {}

    public ValidationResult validate(String cypher) {
        Set<String> allowedLabels = schemaBuilder.getNodeLabels();
        Set<String> allowedRelTypes = schemaBuilder.getRelationshipTypes();

        // 1. 구문 검증: Cypher 파싱 가능한지
        try {
            CypherParser.parse(cypher);
        } catch (Exception e) {
            return new ValidationResult(false, "구문 오류: " + e.getMessage());
        }

        // 2. 레이블 검증: 사용된 노드 레이블이 스키마에 존재하는지
        Set<String> usedLabels = extractLabels(cypher);
        for (String label : usedLabels) {
            if (!allowedLabels.contains(label)) {
                return new ValidationResult(false,
                    "존재하지 않는 노드 레이블: " + label);
            }
        }

        // 3. 관계 타입 검증
        Set<String> usedRels = extractRelationshipTypes(cypher);
        for (String rel : usedRels) {
            if (!allowedRelTypes.contains(rel)) {
                return new ValidationResult(false,
                    "존재하지 않는 관계 타입: " + rel);
            }
        }

        // 4. 위험 쿼리 차단: DETACH DELETE, SET, CREATE, MERGE 등 쓰기 작업
        if (containsWriteOperation(cypher)) {
            return new ValidationResult(false, "쓰기 작업은 허용되지 않습니다.");
        }

        return new ValidationResult(true, null);
    }
}
```

#### NL Query 전체 흐름

```java
@Service
@RequiredArgsConstructor
public class NlQueryService {

    private final ClaudeClient claudeClient;
    private final SchemaContextBuilder schemaBuilder;
    private final CypherValidator validator;
    private final Neo4jClient neo4jClient;
    private final LlmDataSanitizer sanitizer;
    private final LlmAuditLogRepository auditRepo;

    public NlQueryResponse query(String userQuery) {
        // 1. 스키마 컨텍스트 생성
        String schemaContext = schemaBuilder.buildContext();

        // 2. Cypher 생성 요청
        String prompt = buildCypherPrompt(schemaContext, userQuery);
        String generatedCypher = claudeClient.generateCypher(prompt);

        // 3. 검증
        var validation = validator.validate(generatedCypher);
        if (!validation.valid()) {
            // 1회 재시도: 오류 피드백과 함께
            String retryPrompt = buildRetryPrompt(prompt, generatedCypher, validation.error());
            generatedCypher = claudeClient.generateCypher(retryPrompt);
            validation = validator.validate(generatedCypher);

            if (!validation.valid()) {
                return NlQueryResponse.error(
                    "이 질문을 Cypher 쿼리로 변환하지 못했습니다. " +
                    "더 구체적으로 질문해 주세요.");
            }
        }

        // 4. 실행
        var results = neo4jClient.query(generatedCypher).fetch().all();

        // 5. 결과 설명 생성 (선택적)
        String explanation = claudeClient.explainResults(userQuery, results);

        // 6. 감사 로그
        auditRepo.save(LlmAuditLog.builder()
            .queryText(userQuery)
            .transmissionLevel(sanitizer.getCurrentLevel().name())
            .build());

        return NlQueryResponse.success(generatedCypher, results, explanation);
    }
}
```

### 3.3 LLM 보안 감사 보조

규칙 기반 감사 결과를 LLM이 **설명하고 우선순위화**한다. LLM 단독 판단이 아님.

```java
@Service
public class LlmAuditAssistant {

    /**
     * 규칙 기반 감사 결과를 LLM에게 전달하여:
     * 1. 각 finding의 비즈니스 맥락 설명
     * 2. 조치 우선순위 제안
     * 3. 구체적 조치 방법 안내
     */
    public AuditExplanation explain(AuditReport report) {
        String prompt = """
            당신은 AWS 보안 전문가입니다.
            아래 보안 감사 결과를 분석하고:
            1. 각 항목이 왜 위험한지 비즈니스 관점에서 설명
            2. 어떤 것부터 조치해야 하는지 우선순위 제안
            3. 각 항목의 구체적 조치 방법

            감사 결과:
            %s

            JSON 형식으로 응답하세요.
            """.formatted(report.toSummaryJson());

        return claudeClient.query(prompt, AuditExplanation.class);
    }
}
```

### 3.4 자동 아키텍처 요약

전체 인프라를 한국어/영어로 요약하는 문서를 자동 생성.

```java
public String generateArchitectureSummary(GraphData graph, String language) {
    String sanitizedData = sanitizer.sanitize(graph).toJson();

    String prompt = """
        아래 AWS 인프라 그래프 데이터를 분석하여 아키텍처 요약을 작성하세요.

        포함할 내용:
        - 전체 구조 개요 (몇 개의 VPC, 주요 서비스 구성)
        - 트래픽 흐름 (외부 → ALB → 앱 → DB)
        - 환경 분리 현황 (prod/staging/dev)
        - 주목할 점 (특이한 구성, 잠재적 개선점)

        언어: %s
        분량: 300-500자

        그래프 데이터:
        %s
        """.formatted(language, sanitizedData);

    return claudeClient.query(prompt);
}
```

### 3.5 NL Query API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/query` | 자연어 질의 |
| GET | `/api/query/examples` | 예시 질의 목록 |
| POST | `/api/labels/generate` | 자동 레이블링 실행 |
| GET | `/api/labels` | 현재 레이블 목록 |
| POST | `/api/audit/explain` | 감사 결과 LLM 설명 |
| POST | `/api/summary/generate` | 아키텍처 요약 생성 |

#### 자연어 질의 요청/응답

```json
// POST /api/query
// Request
{
  "query": "dongne-prod-db를 사용하는 서비스들을 보여줘"
}

// Response
{
  "success": true,
  "generatedCypher": "MATCH (r:RdsInstance {name: 'dongne-prod-db'})<-[:HAS_SG]-(sg)<-[:HAS_SG]-(resource) RETURN resource.name AS name, resource.resourceType AS type",
  "results": [
    {"name": "api-server-1", "type": "EC2"},
    {"name": "payment-service", "type": "EcsService"}
  ],
  "explanation": "dongne-prod-db RDS 인스턴스와 같은 보안 그룹을 사용하는 리소스는 2개입니다...",
  "subgraph": {
    "nodes": [...],
    "edges": [...]
  }
}
```

---

## 4. 프론트엔드 확장

### 4.1 자연어 질의 UI

- 화면 상단에 검색 바 형태의 질의 입력창
- 예시 질의 버튼 ("prod에 뭐가 돌아가고 있어?", "RDS 쓰는 서비스는?")
- 결과: 토폴로지 뷰에서 관련 노드만 하이라이트 + 우측 패널에 설명

### 4.2 감사 대시보드

- 별도 탭 또는 사이드바 섹션
- HIGH (빨강) / MEDIUM (주황) / LOW (노랑) 카드
- 각 finding 클릭 → 토폴로지에서 위반 리소스 하이라이트
- LLM 설명 + 조치 방법 표시

### 4.3 계층 레이블 시각화

- 토폴로지 뷰에서 레이블별 색상 배경 또는 태그 뱃지
- 필터: "web-tier만 보기" / "db-tier만 보기"
- 레이블별 그룹화 레이아웃 옵션

---

## 5. Claude API 통합 설계

### 5.1 Spring 서비스

```java
@Service
@RequiredArgsConstructor
public class ClaudeClient {

    private final AriadneProperties props;
    private final RestClient restClient;
    private final LlmDataSanitizer sanitizer;

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    public String query(String prompt) {
        return query(prompt, props.getLlm().getDefaultModel());
    }

    public String query(String prompt, String model) {
        var request = Map.of(
            "model", model,
            "max_tokens", 4096,
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        var response = restClient.post()
            .uri(API_URL)
            .header("x-api-key", props.getLlm().getApiKey())
            .header("anthropic-version", "2023-06-01")
            .body(request)
            .retrieve()
            .body(ClaudeResponse.class);

        return response.content().get(0).text();
    }
}
```

### 5.2 설정

```yaml
ariadne:
  llm:
    api-key: ${CLAUDE_API_KEY}
    default-model: claude-sonnet-4-6-20250514
    complex-model: claude-opus-4-6-20250610  # 복잡한 추론용
    transmission-level: normal
    max-tokens: 4096
    timeout-seconds: 30
```

---

## 6. 구현 순서

### Week 1: 규칙 기반 감사 엔진

1. AuditRule 인터페이스 + SG 규칙 8개 구현
2. AuditEngine — 전체 감사 실행
3. AuditReport 모델 + REST API
4. 감사 대시보드 UI (HIGH/MEDIUM/LOW 카드)
5. finding → 토폴로지 하이라이트 연동

### Week 2: 자동 레이블링 + NL Query 기반

6. TierLabeler 규칙 기반 레이블링
7. ClaudeClient Spring 서비스
8. SchemaContextBuilder — Neo4j 스키마 추출
9. NlQueryService — Cypher 생성 + 검증 파이프라인
10. CypherValidator — 환각 방지 레이어

### Week 3: NL Query 완성 + LLM 보조

11. NL Query UI (검색 바 + 예시 + 결과 표시)
12. LLM 레이블링 (미분류 리소스 보조)
13. LlmAuditAssistant — 감사 결과 설명
14. 아키텍처 요약 생성기
15. 감사 로그 연동

### Week 4 (버퍼): 통합 + 안정화

16. dongne-v2 실데이터로 NL Query 정확도 테스트
17. Cypher 검증 엣지 케이스 처리
18. 프롬프트 튜닝 (실 질의 패턴 기반)
19. 5대 질문 #3, #4 답변 가능 확인

---

## 7. Codex 검토 반영 사항

### 7.1 CypherValidator 강화

**지적**: 프로퍼티명/함수/절 화이트리스트 없음, EXPLAIN 미적용, row/시간 상한 없음.

**반영**:
```java
@Component
public class CypherValidator {

    // 기존: 레이블 + 관계 타입 + 쓰기 차단
    // 추가:

    // 1. 프로퍼티 화이트리스트
    private static final Set<String> ALLOWED_PROPERTIES = Set.of(
        "arn", "resourceId", "name", "resourceType", "environment",
        "state", "status", "instanceType", "engine", "endpoint",
        "cidrBlock", "scheme", "type", "runtime", "dnsName",
        "privateIp", "publicIp", "port", "protocol"
    );

    // 2. 허용 절 화이트리스트 (읽기 전용)
    private static final Set<String> ALLOWED_CLAUSES = Set.of(
        "MATCH", "WHERE", "RETURN", "ORDER BY", "LIMIT", "SKIP",
        "WITH", "OPTIONAL MATCH", "UNWIND", "CASE", "WHEN"
    );

    // 3. 차단: CALL, 프로시저, APOC 등
    private boolean containsForbiddenCalls(String cypher) {
        return cypher.toUpperCase().contains("CALL ") ||
               cypher.toUpperCase().contains("APOC.") ||
               cypher.toUpperCase().contains("dbms.");
    }

    public ValidationResult validate(String cypher) {
        // ... 기존 검증 ...

        // 4. 프로퍼티 검증
        Set<String> usedProps = extractPropertyNames(cypher);
        for (String prop : usedProps) {
            if (!ALLOWED_PROPERTIES.contains(prop)) {
                return invalid("허용되지 않은 프로퍼티: " + prop);
            }
        }

        // 5. CALL/프로시저 차단
        if (containsForbiddenCalls(cypher)) {
            return invalid("프로시저 호출은 허용되지 않습니다.");
        }

        return valid();
    }
}
```

**EXPLAIN dry-run + 실행 시 안전장치**:
```java
// NlQueryService 내 실행 전 EXPLAIN 검증
private void validateWithExplain(String cypher) {
    try {
        neo4jClient.query("EXPLAIN " + cypher).fetch().all();
    } catch (Exception e) {
        throw new CypherValidationException("EXPLAIN 실패: " + e.getMessage());
    }
}

// 실행 시 서버측 LIMIT + timeout 강제
private Collection<Map<String, Object>> executeSafely(String cypher) {
    // 사용자 쿼리에 LIMIT 없으면 강제 추가
    if (!cypher.toUpperCase().contains("LIMIT")) {
        cypher = cypher + " LIMIT 100";
    }

    // 트랜잭션 타임아웃 10초
    return neo4jClient.query(cypher)
        .in(neo4jTemplate.getDatabaseName())
        .fetch().all();
    // Spring Data Neo4j 트랜잭션 타임아웃 설정:
    // spring.neo4j.transaction.timeout=10s
}
```

### 7.2 NL Query 엣지 케이스 처리

**지적**: 빈 결과, 모호한 질의, 대량 결과 미처리.

**반영**:
```java
public NlQueryResponse query(String userQuery) {
    // ... Cypher 생성 + 검증 ...

    var results = executeSafely(generatedCypher);

    // 1. 빈 결과 처리
    if (results.isEmpty()) {
        return NlQueryResponse.empty(
            generatedCypher,
            "조건에 맞는 리소스를 찾지 못했습니다. " +
            "리소스 이름이나 조건을 확인해 주세요.",
            suggestSimilarQueries(userQuery)  // 유사 질의 제안
        );
    }

    // 2. 대량 결과 처리 (100건 이상)
    boolean truncated = results.size() >= 100;

    // 3. 결과 응답
    return NlQueryResponse.builder()
        .success(true)
        .generatedCypher(generatedCypher)
        .results(results)
        .truncated(truncated)
        .totalEstimate(truncated ? estimateTotal(generatedCypher) : results.size())
        .explanation(claudeClient.explainResults(userQuery, results))
        .build();
}
```

**모호한 질의 대응** — 동일 이름 리소스 감지:
```java
// 동일 이름 리소스가 여러 개면 clarification 요청
if (isAmbiguous(results, userQuery)) {
    return NlQueryResponse.clarificationNeeded(
        "'" + extractResourceName(userQuery) + "' 이름의 리소스가 " +
        results.size() + "개 있습니다. 환경(prod/staging)이나 " +
        "리소스 타입을 지정해 주세요.",
        results.stream().map(this::toSummary).toList()
    );
}
```

### 7.3 감사 규칙 확장

**지적**: IAM 규칙 부족 (AdministratorAccess, iam:PassRole, MFA 없음), 네트워크/암호화 공백.

**반영 — 추가 규칙**:

#### IAM 추가 규칙
| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| IAM-005 | AdministratorAccess 연결 | AdministratorAccess 정책이 attached | HIGH |
| IAM-006 | PassRole 권한 | iam:PassRole이 와일드카드 리소스 | HIGH |
| IAM-007 | 루트 계정 Access Key | 루트 계정에 활성 Access Key 존재 | HIGH |

#### 네트워크 추가 규칙
| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| NET-004 | 퍼블릭 서브넷 직접 배치 | RDS/내부 서비스가 public subnet에 있음 | HIGH |

#### 암호화 추가 규칙
| 규칙 ID | 이름 | 조건 | 위험도 |
|---|---|---|---|
| ENC-001 | RDS 암호화 미적용 | encrypted = false | HIGH |
| ENC-002 | S3 버킷 정책 퍼블릭 | bucket policy에 Principal: "*" | HIGH |
| ENC-003 | S3 SSL 미강제 | bucket policy에 SecureTransport 조건 없음 | MEDIUM |

### 7.4 Claude API 비용/안정성 관리

**지적**: 입력 토큰 예산 없음, 최대 3회 동기 호출, 캐시/비용 상한 없음, circuit breaker 없음.

**반영**:
```yaml
ariadne:
  llm:
    # 토큰 예산
    max-input-tokens: 8000          # 프롬프트 입력 상한
    max-output-tokens: 4096
    # 비용 상한
    daily-budget-usd: 5.0           # 일일 비용 상한 ($5)
    # 타임아웃
    timeout-seconds: 30
    retry-max-attempts: 2
    retry-backoff-seconds: 2
```

```java
@Service
public class LlmGateway {

    // 1. Circuit Breaker (5회 연속 실패 시 30초간 차단)
    @CircuitBreaker(name = "claude", fallbackMethod = "fallback")
    public String query(String prompt, GraphData contextData) { ... }

    // 2. Fallback: LLM 실패 시 규칙/템플릿 기반 응답
    public String fallback(String prompt, GraphData contextData, Exception e) {
        log.warn("LLM 호출 실패, 폴백 응답 사용: {}", e.getMessage());
        return "AI 응답을 생성할 수 없습니다. 규칙 기반 결과를 참고하세요.";
    }

    // 3. 일일 비용 추적
    private final AtomicReference<DailyUsage> dailyUsage = new AtomicReference<>();

    private void checkBudget(int estimatedTokens) {
        if (dailyUsage.get().totalCostUsd() >= props.getDailyBudgetUsd()) {
            throw new BudgetExceededException("일일 LLM 예산 초과");
        }
    }

    // 4. 그래프 청크 전략 (대규모 그래프)
    private String chunkGraphForPrompt(SanitizedGraphData data) {
        if (data.estimateTokens() <= props.getMaxInputTokens()) {
            return data.toJson();
        }
        // 토큰 초과 시: 통계 요약 + 관련 서브그래프만 전송
        return data.toSummaryWithRelevantSubgraph(props.getMaxInputTokens());
    }
}
```

**NL Query 비동기 설명 생성**:
```java
// 기존: 동기 3회 (생성 + 재시도 + 설명)
// 수정: 설명은 비동기로 분리
public NlQueryResponse query(String userQuery) {
    // 1. Cypher 생성 (동기, 필수)
    String cypher = generateCypher(userQuery);
    // 2. 검증 + 실행 (동기, 필수)
    var results = executeSafely(cypher);
    // 3. 설명 생성 (비동기, 선택 — 프론트에서 별도 요청)
    return NlQueryResponse.success(cypher, results, null);
}

// 설명은 별도 API
// GET /api/query/{queryId}/explanation → SSE 또는 비동기 응답
```

### 7.5 자동 레이블링 신뢰도 관리

**지적**: few-shot 부족, confidence threshold 없음, 사람 검토 큐 없음.

**반영**:
```java
public record LabelResult(
    String arn,
    String tier,
    double confidence,       // 0.0 ~ 1.0
    String source            // "rule" | "llm"
) {}

// LLM 응답에 confidence 포함 요청
String prompt = """
    ...
    JSON 형식으로 응답하세요:
    {"arn1": {"tier": "app-tier", "confidence": 0.9, "reason": "..."}, ...}
    confidence는 0.0~1.0 범위로, 확신 정도를 나타냅니다.
    """;

// confidence 기반 처리
public Map<String, LabelResult> labelByLlm(...) {
    Map<String, LabelResult> results = parseLabelingResponse(response);

    // 0.8 이상: 자동 적용
    // 0.5~0.8: "추정" 뱃지로 표시 (사용자가 확인 가능)
    // 0.5 미만: 미분류로 유지

    results.forEach((arn, label) -> {
        if (label.confidence() >= 0.8) {
            applyLabel(arn, label.tier(), "auto");
        } else if (label.confidence() >= 0.5) {
            applyLabel(arn, label.tier(), "tentative");  // UI에서 "?" 표시
        }
        // 0.5 미만은 무시
    });

    return results;
}
```

Few-shot 확장 (최소 5개):
```
이미 분류된 리소스 (참고용):
- web-server-1 (EC2, t3.medium, ALB 타겟, 포트 80) → web-tier
- api-gateway (ECS, Fargate, internal ALB 타겟) → app-tier
- dongne-prod-db (RDS, PostgreSQL, private subnet) → db-tier
- session-cache (EC2, r5.large, 포트 6379, private) → cache-tier
- daily-report-gen (Lambda, S3 트리거, 매일 실행) → batch-tier
```
