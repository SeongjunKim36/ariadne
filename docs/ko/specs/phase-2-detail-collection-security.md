# Phase 2: 상세 설정 수집 + 보안 하드닝 — 상세 기획서

> 상위 문서: [project-a-infra-mapper.md](../project-a-infra-mapper.md)
> 선행 조건: Phase 1 완료 (핵심 9종 수집 + React Flow MVP)
> 예상 기간: 3-4주

---

## 1. 목표

Phase 1에서 수집한 기본 리소스 정보를 **깊이 있게 확장**하고, 보안 관련 하드닝을 완성한다.

### 종료 조건

- [ ] SG 규칙이 `ALLOWS_FROM` / `ALLOWS_TO` 엣지로 상세 매핑됨
- [ ] ECS 태스크 정의의 이미지/환경변수가 리댁션 적용 후 저장됨
- [ ] IAM 역할/정책이 그래프에 선택적으로 포함 가능
- [ ] nginx 플러그인이 SSM 옵트인 기반으로 동작
- [ ] 민감정보 자동 리댁션 엔진 동작
- [ ] LLM 전송 경계 정책 구현 완료
- [ ] 감사 로그 기록 동작

---

## 2. 상세 수집 (2주)

### 2.1 Security Group 규칙 상세화

Phase 1에서는 SG 자체만 노드로 저장했다. Phase 2에서는 **인바운드/아웃바운드 규칙을 엣지로 변환**한다.

#### 새로운 엣지 타입

```cypher
# SG 간 접근 허용 (SG-to-SG 규칙)
(:SecurityGroup)-[:ALLOWS_FROM {
  port: String,          # "443" 또는 "8080-8090"
  protocol: String,      # "tcp" / "udp" / "-1" (all)
  direction: String      # "inbound" / "outbound"
}]->(:SecurityGroup)

# CIDR 기반 접근 허용 (외부 → SG)
(:CidrSource)-[:ALLOWS_TO {
  port: String,
  protocol: String
}]->(:SecurityGroup)
```

#### CidrSource 가상 노드

외부 CIDR 범위를 노드로 표현하여 "어디서 접근 가능한지" 시각화:

```
(:CidrSource {
  cidr: String,          # "0.0.0.0/0" 또는 "10.0.0.0/8"
  label: String,         # "인터넷 전체" / "내부 네트워크"
  isPublic: Boolean,     # 0.0.0.0/0 또는 ::/0이면 true
  riskLevel: String      # "HIGH" / "MEDIUM" / "LOW"
})
```

#### 수집 로직

```java
@Component
@RequiredArgsConstructor
public class SecurityGroupDetailCollector {

    private final Ec2Client ec2Client;
    private final Neo4jTemplate neo4jTemplate;

    public void collectRules() {
        DescribeSecurityGroupsResponse response = ec2Client.describeSecurityGroups();

        for (SecurityGroup sg : response.securityGroups()) {
            // 인바운드 규칙
            for (IpPermission perm : sg.ipPermissions()) {
                // SG-to-SG 규칙
                for (UserIdGroupPair pair : perm.userIdGroupPairs()) {
                    createSgToSgEdge(pair.groupId(), sg.groupId(), perm, "inbound");
                }
                // CIDR 규칙
                for (IpRange range : perm.ipRanges()) {
                    CidrSource cidrNode = getOrCreateCidrSource(range.cidrIp());
                    createCidrToSgEdge(cidrNode, sg.groupId(), perm);
                }
            }
            // 아웃바운드 규칙 — 동일 패턴
        }
    }
}
```

### 2.2 ECS 태스크 정의 상세

#### 새로운 노드/프로퍼티

```
(:EcsTaskDefinition {
  arn: String,
  family: String,
  revision: Integer,
  cpu: String,
  memory: String,
  networkMode: String,         # awsvpc / bridge / host
  containers: List<ContainerInfo>  # 직렬화된 컨테이너 정보
})

# ContainerInfo (JSON 직렬화)
{
  "name": "web",
  "image": "123456.dkr.ecr.ap-northeast-2.amazonaws.com/myapp:v1.2.3",
  "cpu": 256,
  "memory": 512,
  "portMappings": [{"containerPort": 8080, "protocol": "tcp"}],
  "environment": [
    {"name": "SPRING_PROFILES_ACTIVE", "value": "prod"},
    {"name": "DB_PASSWORD", "value": "***REDACTED***"}   # ← 리댁션 적용
  ]
}
```

#### 관계

```cypher
(:EcsService)-[:USES_TASK_DEF]->(:EcsTaskDefinition)
(:EcsTaskDefinition)-[:PULLS_IMAGE {containerName: String}]->(:EcrImage)  # 선택적
```

### 2.3 IAM 역할/정책 (선택적)

Phase 2에서는 **서비스 연결 IAM 역할**만 수집한다 (전체 IAM은 스코프 밖).

```
(:IamRole {
  arn: String,
  roleName: String,
  assumeRolePolicy: String,    # 신뢰 정책 (어떤 서비스가 assume 가능한지)
  attachedPolicies: List<String>
})

(:Ec2Instance)-[:HAS_ROLE]->(:IamRole)
(:EcsService)-[:HAS_ROLE]->(:IamRole)       # task execution role
(:LambdaFunction)-[:HAS_ROLE]->(:IamRole)   # execution role
```

---

## 3. nginx 설정 수집 (선택적 플러그인)

### 3.1 설계 원칙

- **기본 설치에 포함하지 않음** — 별도 활성화 필요
- SSM 권한이 있는 경우에만 동작
- graceful degradation: 실패 시 경고 로그만 남기고 계속 진행

### 3.2 활성화 방법

```yaml
# application.yml
ariadne:
  plugins:
    nginx:
      enabled: false           # 기본값 false
      ssm-timeout-seconds: 30
      config-paths:
        - /etc/nginx/nginx.conf
        - /etc/nginx/conf.d/
```

### 3.3 수집 흐름

```
1. EC2 인스턴스 목록에서 SSM Agent 활성 여부 확인 (ssm:DescribeInstanceInformation)
2. 활성 인스턴스에 RunCommand 실행: `cat /etc/nginx/nginx.conf && ls /etc/nginx/conf.d/`
3. 응답 파싱:
   - upstream 블록 → 백엔드 서버 목록 추출
   - server_name → 도메인 매핑
   - proxy_pass → 라우팅 엣지 생성
4. 그래프에 반영:
   (:Ec2Instance)-[:RUNS_NGINX {serverName: "api.dongne.com"}]->(:NginxConfig)
   (:NginxConfig)-[:PROXIES_TO {upstream: "backend", port: 8080}]->(:Ec2Instance)
```

### 3.4 추가 IAM 권한 (nginx 플러그인 전용)

```json
{
  "Sid": "AriadneNginxPlugin",
  "Effect": "Allow",
  "Action": [
    "ssm:DescribeInstanceInformation",
    "ssm:SendCommand",
    "ssm:GetCommandInvocation"
  ],
  "Resource": "*"
}
```

---

## 4. 보안 하드닝 (1-2주)

### 4.1 민감정보 자동 리댁션 엔진

#### 리댁션 대상

| 패턴 | 예시 | 리댁션 결과 |
|---|---|---|
| DB 비밀번호 | `DB_PASSWORD=secret123` | `DB_PASSWORD=***REDACTED***` |
| API 키 | `API_KEY=sk-abc123def` | `API_KEY=***REDACTED***` |
| AWS 시크릿 | `AWS_SECRET_ACCESS_KEY=xxx` | `AWS_SECRET_ACCESS_KEY=***REDACTED***` |
| JWT 토큰 | `JWT_SECRET=long-string` | `JWT_SECRET=***REDACTED***` |
| 연결 문자열 | `jdbc:postgresql://host/db?password=xxx` | `jdbc:postgresql://host/db?password=***REDACTED***` |

#### 구현

```java
@Component
public class RedactionEngine {

    // 키 이름 기반 리댁션 (환경변수명에 매칭)
    private static final List<Pattern> KEY_PATTERNS = List.of(
        Pattern.compile("(?i).*(password|passwd|secret|token|key|credential|auth).*"),
        Pattern.compile("(?i).*(api.?key|access.?key|private.?key).*"),
        Pattern.compile("(?i).*(jwt|bearer|oauth).*"),
        Pattern.compile("(?i).*(database.?url|connection.?string|dsn).*")
    );

    // 값 기반 리댁션 (값 자체가 민감 패턴)
    private static final List<Pattern> VALUE_PATTERNS = List.of(
        Pattern.compile("(?i)^(sk-|pk-|ak-|rk-)\\w+"),           // API 키 접두사
        Pattern.compile("(?i)^AKIA[0-9A-Z]{16}$"),               // AWS Access Key
        Pattern.compile("^[A-Za-z0-9+/]{40,}={0,2}$"),           // Base64 긴 문자열
        Pattern.compile("jdbc:[a-z]+://.*password=\\w+")          // JDBC 연결 문자열
    );

    private static final String REDACTED = "***REDACTED***";

    public Map<String, String> redact(Map<String, String> envVars) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (shouldRedactKey(entry.getKey()) || shouldRedactValue(entry.getValue())) {
                result.put(entry.getKey(), REDACTED);
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 화이트리스트 모드: 명시적으로 허용된 키만 값을 보여줌
     */
    public Map<String, String> redactWhitelistMode(
            Map<String, String> envVars, Set<String> allowedKeys) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (allowedKeys.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                result.put(entry.getKey(), REDACTED);
            }
        }
        return result;
    }
}
```

### 4.2 LLM 전송 경계 정책

#### 전송 레벨 정의

```yaml
ariadne:
  llm:
    transmission-level: normal   # strict / normal / verbose

    # strict: 리소스 타입 + 관계 구조만 전송 (이름/ID도 익명화)
    # normal: 리소스 ID/이름 + 관계 구조 전송 (태그/환경변수 제외)
    # verbose: 태그 포함 전송 (환경변수는 항상 리댁션)
```

#### 전송 전 데이터 변환

```java
@Service
@RequiredArgsConstructor
public class LlmDataSanitizer {

    private final AriadneProperties properties;
    private final RedactionEngine redactionEngine;

    public SanitizedGraphData sanitize(GraphData raw) {
        return switch (properties.getLlm().getTransmissionLevel()) {
            case STRICT -> sanitizeStrict(raw);
            case NORMAL -> sanitizeNormal(raw);
            case VERBOSE -> sanitizeVerbose(raw);
        };
    }

    private SanitizedGraphData sanitizeStrict(GraphData raw) {
        // 이름/ID를 "EC2-1", "RDS-1" 등으로 익명화
        // 관계 구조만 유지
    }

    private SanitizedGraphData sanitizeNormal(GraphData raw) {
        // 이름/ID는 유지, 태그/환경변수 제거
    }

    private SanitizedGraphData sanitizeVerbose(GraphData raw) {
        // 태그 포함, 환경변수는 리댁션 적용 후 전송
    }
}
```

### 4.3 감사 로깅

모든 LLM API 호출을 기록하여 "어떤 데이터가 언제 나갔는지" 추적 가능하게 한다.

```java
@Entity
@Table(name = "llm_audit_log")
public class LlmAuditLog {
    @Id @GeneratedValue
    private Long id;

    private Instant timestamp;
    private String transmissionLevel;  // strict/normal/verbose
    private String queryText;          // 사용자의 자연어 질의
    private Integer tokensSent;        // 전송된 토큰 수
    private Integer tokensReceived;
    private String dataScope;          // "전체 그래프" / "서브그래프: vpc-xxx"
    private Integer nodeCount;         // 전송된 노드 수
    private Integer edgeCount;         // 전송된 엣지 수

    // 환경변수/태그 원문은 절대 기록하지 않음
}
```

```java
// PostgreSQL에 저장, REST API로 조회 가능
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    GET /api/audit/llm                    // 전체 로그
    GET /api/audit/llm?from=&to=          // 기간별 조회
    GET /api/audit/llm/stats              // 통계 (일별 호출 수, 평균 토큰)
}
```

---

## 5. 구현 순서

### Week 1: SG 상세 + ECS 태스크 정의

1. SecurityGroup 인바운드/아웃바운드 규칙 파싱 → ALLOWS_FROM/ALLOWS_TO 엣지
2. CidrSource 가상 노드 생성 로직
3. ECS TaskDefinition 수집기
4. 리댁션 엔진 기본 구현 (키 패턴 기반)
5. React Flow에 SG 규칙 시각화 (접근 경로 표시)

### Week 2: IAM + nginx 플러그인

6. IAM Role 수집기 (서비스 연결 역할만)
7. HAS_ROLE 관계 매핑
8. nginx 플러그인 구조 (enabled: false 기본)
9. SSM RunCommand → nginx config 파싱 → 라우팅 엣지
10. 플러그인 활성화 시 추가 IAM 정책 안내 UI

### Week 3: 보안 하드닝

11. 리댁션 엔진 고도화 (값 패턴 + 화이트리스트 모드)
12. LLM 전송 경계 정책 (strict/normal/verbose)
13. LlmDataSanitizer 구현
14. 감사 로그 테이블 + API
15. 통합 테스트: 리댁션 정확성, 전송 레벨별 데이터 검증

### Week 4 (버퍼): 안정화

16. dongne-v2 실계정에서 상세 수집 테스트
17. SG 시각화 UX 개선
18. 리댁션 엔진 엣지 케이스 처리
19. Phase 2 종료 조건 체크리스트 확인

---

## 6. Codex 검토 반영 사항

### 6.1 SG 규칙 누락 케이스 보완

**지적**: IPv6, prefix list, 아웃바운드 CIDR, self-reference 미처리.

**반영**:
```java
// SecurityGroupDetailCollector 보완 사항

// 1. IPv6 CIDR 처리 추가
for (Ipv6Range range : perm.ipv6Ranges()) {
    CidrSource cidrNode = getOrCreateCidrSource(range.cidrIpv6());
    createCidrToSgEdge(cidrNode, sg.groupId(), perm);
}

// 2. Managed Prefix List 처리
for (PrefixListId prefixList : perm.prefixListIds()) {
    // prefix list → 실제 CIDR 조회 후 CidrSource 생성
    DescribeManagedPrefixListsResponse resp =
        ec2Client.describeManagedPrefixLists(r -> r.prefixListIds(prefixList.prefixListId()));
    // entries에서 CIDR 추출 → 각각 CidrSource 노드 생성
}

// 3. 아웃바운드 규칙: SG → CidrSource 방향 엣지
for (IpPermission outPerm : sg.ipPermissionsEgress()) {
    for (IpRange range : outPerm.ipRanges()) {
        CidrSource cidrNode = getOrCreateCidrSource(range.cidrIp());
        createSgToCidrEdge(sg.groupId(), cidrNode, outPerm); // 방향 반대
    }
}

// 4. Self-reference 처리 (SG가 자기 자신을 참조)
for (UserIdGroupPair pair : perm.userIdGroupPairs()) {
    if (pair.groupId().equals(sg.groupId())) {
        createSelfReferenceEdge(sg.groupId(), perm);  // ALLOWS_SELF 엣지
    }
}
```

새로운 엣지 타입 추가:
```cypher
(:SecurityGroup)-[:ALLOWS_SELF {port, protocol}]->(:SecurityGroup)  # 자기 참조
(:SecurityGroup)-[:EGRESS_TO {port, protocol}]->(:CidrSource)       # 아웃바운드
```

### 6.2 리댁션 엔진 커버리지 확장

**지적**: env vars만 다루고 secrets, repositoryCredentials, logConfiguration 누락. 짧은 Base64 미매칭.

**반영**: 리댁션 대상을 환경변수 외로 확장.
```java
@Component
public class RedactionEngine {

    // 기존: 환경변수 리댁션
    public Map<String, String> redactEnvVars(Map<String, String> envVars) { ... }

    // 추가: ECS TaskDefinition 전체 리댁션
    public ContainerDefinition redactContainerDef(ContainerDefinition def) {
        return def.toBuilder()
            .environment(redactEnvVars(def.environment()))
            .secrets(List.of())            // secrets 필드 완전 제거 (ARN만 남길지 결정 필요)
            .repositoryCredentials(null)    // 완전 제거
            .logConfiguration(redactLogConfig(def.logConfiguration()))
            .build();
    }

    // 추가: logConfiguration에서 민감 옵션 제거
    private LogConfiguration redactLogConfig(LogConfiguration config) {
        if (config == null) return null;
        Map<String, String> safeOptions = config.options().entrySet().stream()
            .collect(toMap(
                Map.Entry::getKey,
                e -> shouldRedactKey(e.getKey()) ? REDACTED : e.getValue()
            ));
        return config.toBuilder().options(safeOptions).build();
    }

    // 수정: Base64 패턴 완화 (20자 이상으로 하향)
    private static final List<Pattern> VALUE_PATTERNS = List.of(
        Pattern.compile("(?i)^(sk-|pk-|ak-|rk-)\\w+"),
        Pattern.compile("(?i)^AKIA[0-9A-Z]{16}$"),
        Pattern.compile("^[A-Za-z0-9+/]{20,}={0,2}$"),  // 40→20자로 하향
        Pattern.compile("jdbc:[a-z]+://.*password=\\w+")
    );

    // 추가: ECR 이미지 URL에서 account ID는 유지 (리소스 식별 필요)
    // 단, 이미지 태그의 커밋 해시 등은 그대로 유지 (민감하지 않음)
}
```

### 6.3 LLM 전송 경계 강제 인터셉터

**지적**: sanitize() 우회 가능, fail-closed 미정의, field allowlist 없음.

**반영**: 모든 LLM 호출이 반드시 Sanitizer를 거치도록 강제.
```java
/**
 * ClaudeClient를 직접 사용하지 않고, 반드시 이 게이트웨이를 통해야 함.
 * 직접 호출 방지: ClaudeClient를 package-private으로 제한.
 */
@Service
@RequiredArgsConstructor
public class LlmGateway {

    private final ClaudeClient claudeClient;         // package-private
    private final LlmDataSanitizer sanitizer;
    private final LlmAuditLogRepository auditRepo;
    private final AriadneProperties props;

    public String query(String prompt, GraphData contextData) {
        // 1. transmission-level 누락 시 fail-closed: STRICT 강제
        TransmissionLevel level = Optional.ofNullable(props.getLlm().getTransmissionLevel())
            .orElse(TransmissionLevel.STRICT);

        // 2. 강제 sanitize
        SanitizedGraphData sanitized = sanitizer.sanitize(contextData, level);

        // 3. field allowlist 적용 — 허용된 필드만 전송
        String sanitizedPrompt = applyFieldAllowlist(prompt, sanitized);

        // 4. 감사 로그
        auditRepo.save(buildLog(prompt, sanitized));

        // 5. 호출
        return claudeClient.query(sanitizedPrompt);
    }

    private static final Set<String> ALLOWED_FIELDS = Set.of(
        "arn", "resourceId", "resourceType", "name", "region",
        "environment", "state", "status", "instanceType", "engine",
        "cidrBlock", "scheme", "type", "runtime"
    );
}
```

### 6.4 nginx 플러그인 수정

**지적**: `ls`로는 conf.d 파일 내용을 못 읽음. 병렬수집/재시도/출력크기 제한 누락.

**반영**:
```java
// 수정: ls 대신 find + cat으로 전체 내용 수집
private static final String NGINX_COLLECT_COMMAND =
    "cat /etc/nginx/nginx.conf 2>/dev/null; " +
    "find /etc/nginx/conf.d/ -name '*.conf' -exec cat {} \\; 2>/dev/null";

// 추가: 출력 크기 제한 (1MB 초과 시 truncate)
private static final int MAX_OUTPUT_BYTES = 1_048_576;

// 추가: 병렬 수집 (최대 10개 인스턴스 동시)
@Value("${ariadne.plugins.nginx.parallelism:10}")
private int parallelism;

// 추가: 재시도 (SSM 타임아웃 시 1회 재시도)
@Retryable(maxAttempts = 2, backoff = @Backoff(delay = 5000))
public String collectNginxConfig(String instanceId) { ... }
```

### 6.5 AWS SDK 공통 처리

**지적**: pagination, throttling, partial failure 처리 없음.

**반영**: 모든 수집기의 공통 베이스에 적용.
```java
public abstract class BaseCollector<T extends AwsResource>
        implements ResourceCollector<T> {

    // 공통 재시도 + 쓰로틀링 처리
    protected <R> R withRetry(Supplier<R> awsCall) {
        return Retry.of("aws-api", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryOnException(e -> e instanceof ThrottlingException)
                .exponentialBackoff(2.0)
                .build())
            .executeSupplier(awsCall);
    }

    // 페이지네이션 헬퍼 — 모든 describe* 호출에 적용
    // (구체적 구현은 각 수집기에서 Paginator 사용)
}
```
