# Phase 4: 드리프트 & 타임라인 — 상세 기획서

> 상위 문서: [project-a-infra-mapper.md](../project-a-infra-mapper.md)
> 선행 조건: Phase 3 완료 (AI 의미층)
> 예상 기간: 3-4주

---

## 1. 목표

인프라의 **시간 축**을 추가한다. "지금 상태"뿐 아니라 "어제와 뭐가 달라졌는지", "IaC와 실제가 다른지"를 보여준다.

### 종료 조건

- [ ] 매시간 스냅샷이 PostgreSQL에 저장됨
- [ ] 두 스냅샷 간 diff를 API + UI로 조회 가능
- [ ] Terraform state vs 실제 AWS 비교 → 드리프트 목록 출력
- [ ] EventBridge 기반 준실시간 변경 보강 동작 (선택적)
- [ ] 중요 변경 Slack 알림 동작
- [ ] 5대 질문 #5("어제 대비 오늘 뭐가 바뀌었어?") 에 답 가능

---

## 2. 스냅샷 시스템

### 2.1 스냅샷 모델

매 스캔마다 전체 그래프 상태를 PostgreSQL에 직렬화하여 저장한다. Neo4j는 항상 **최신 상태**만 유지하고, 히스토리는 PostgreSQL이 담당한다.

```java
@Entity
@Table(name = "snapshots")
public class Snapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant capturedAt;
    private String accountId;
    private String region;
    private Integer nodeCount;
    private Integer edgeCount;
    private Long scanDurationMs;

    @Column(columnDefinition = "jsonb")
    private String graphJson;        // 전체 그래프 JSON (노드 + 엣지)

    @Column(columnDefinition = "jsonb")
    private String metadata;         // 수집 통계, 오류 등
}
```

```java
@Entity
@Table(name = "snapshot_diffs")
public class SnapshotDiff {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long baseSnapshotId;     // 이전 스냅샷
    private Long targetSnapshotId;   // 현재 스냅샷
    private Instant diffedAt;

    @Column(columnDefinition = "jsonb")
    private String addedNodes;       // 새로 생긴 리소스
    @Column(columnDefinition = "jsonb")
    private String removedNodes;     // 삭제된 리소스
    @Column(columnDefinition = "jsonb")
    private String modifiedNodes;    // 속성 변경된 리소스
    @Column(columnDefinition = "jsonb")
    private String addedEdges;       // 새로운 관계
    @Column(columnDefinition = "jsonb")
    private String removedEdges;     // 제거된 관계

    private Integer totalChanges;
}
```

### 2.2 스냅샷 캡처

```java
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private final Neo4jClient neo4jClient;
    private final SnapshotRepository snapshotRepo;
    private final SnapshotDiffRepository diffRepo;

    /**
     * 현재 Neo4j 그래프 상태를 스냅샷으로 저장
     */
    public Snapshot capture(AwsCollectContext ctx) {
        // 1. Neo4j에서 전체 그래프 추출
        String graphJson = exportGraphAsJson();

        // 2. 스냅샷 저장
        Snapshot snapshot = Snapshot.builder()
            .capturedAt(ctx.getCollectedAt())
            .accountId(ctx.getAccountId())
            .region(ctx.getRegion())
            .graphJson(graphJson)
            .build();
        snapshotRepo.save(snapshot);

        // 3. 이전 스냅샷과 diff 계산
        snapshotRepo.findLatestBefore(snapshot.getCapturedAt())
            .ifPresent(prev -> {
                SnapshotDiff diff = computeDiff(prev, snapshot);
                diffRepo.save(diff);
            });

        return snapshot;
    }

    private String exportGraphAsJson() {
        var nodes = neo4jClient.query(
            "MATCH (n:AwsResource) RETURN n").fetch().all();
        var edges = neo4jClient.query(
            "MATCH (a:AwsResource)-[r]->(b:AwsResource) " +
            "RETURN a.arn AS source, type(r) AS type, b.arn AS target, properties(r) AS props"
        ).fetch().all();

        return objectMapper.writeValueAsString(Map.of("nodes", nodes, "edges", edges));
    }
}
```

### 2.3 Diff 계산 로직

```java
@Component
public class DiffCalculator {

    public SnapshotDiff computeDiff(Snapshot base, Snapshot target) {
        GraphData baseGraph = parse(base.getGraphJson());
        GraphData targetGraph = parse(target.getGraphJson());

        // 노드 diff (ARN 기준)
        Set<String> baseArns = baseGraph.nodeArns();
        Set<String> targetArns = targetGraph.nodeArns();

        List<NodeChange> added = targetArns.stream()
            .filter(a -> !baseArns.contains(a))
            .map(a -> NodeChange.added(targetGraph.getNode(a)))
            .toList();

        List<NodeChange> removed = baseArns.stream()
            .filter(a -> !targetArns.contains(a))
            .map(a -> NodeChange.removed(baseGraph.getNode(a)))
            .toList();

        List<NodeChange> modified = baseArns.stream()
            .filter(targetArns::contains)
            .map(arn -> diffNode(baseGraph.getNode(arn), targetGraph.getNode(arn)))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();

        // 엣지 diff (source+type+target 기준)
        // ... 동일 패턴

        return SnapshotDiff.builder()
            .baseSnapshotId(base.getId())
            .targetSnapshotId(target.getId())
            .addedNodes(serialize(added))
            .removedNodes(serialize(removed))
            .modifiedNodes(serialize(modified))
            .totalChanges(added.size() + removed.size() + modified.size())
            .build();
    }

    private Optional<NodeChange> diffNode(GraphNode base, GraphNode target) {
        Map<String, PropertyChange> changes = new LinkedHashMap<>();

        // 모든 프로퍼티를 비교
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(base.getProperties().keySet());
        allKeys.addAll(target.getProperties().keySet());

        for (String key : allKeys) {
            Object oldVal = base.getProperties().get(key);
            Object newVal = target.getProperties().get(key);
            if (!Objects.equals(oldVal, newVal)) {
                changes.put(key, new PropertyChange(oldVal, newVal));
            }
        }

        if (changes.isEmpty()) return Optional.empty();
        return Optional.of(NodeChange.modified(target, changes));
    }
}
```

### 2.4 스냅샷 보관 정책

```yaml
ariadne:
  snapshot:
    schedule: "0 0 * * * *"     # 매시간 정각
    retention:
      hourly: 48                 # 최근 48시간은 매시간
      daily: 30                  # 30일간 일별 (자정 스냅샷만 보관)
      weekly: 12                 # 12주간 주별 (월요일만 보관)
    max-storage-gb: 5            # 초과 시 가장 오래된 것부터 삭제
```

```java
@Scheduled(cron = "0 0 2 * * *")  // 매일 새벽 2시
public void cleanupOldSnapshots() {
    // hourly: 48시간 이전의 매시간 스냅샷 삭제 (일별 대표만 남김)
    // daily: 30일 이전의 일별 스냅샷 삭제 (주별 대표만 남김)
    // weekly: 12주 이전 전부 삭제
}
```

---

## 3. Terraform 드리프트 감지

### 3.1 Terraform State 파싱

```java
@Service
public class TerraformDriftDetector {

    /**
     * Terraform state 파일(또는 S3 remote state)을 읽어
     * 실제 AWS 상태와 비교한다.
     */
    public DriftReport detectDrift(TerraformStateSource source) {
        // 1. Terraform state 파싱
        TerraformState tfState = parseTfState(source);

        // 2. state에 선언된 리소스 목록 추출
        List<TfResource> tfResources = tfState.getResources();

        // 3. 각 리소스를 현재 Neo4j 그래프와 대조
        List<DriftItem> drifts = new ArrayList<>();

        for (TfResource tf : tfResources) {
            String arn = tf.getArn();
            Optional<GraphNode> actual = graphService.findByArn(arn);

            if (actual.isEmpty()) {
                // Terraform에는 있는데 실제로 없음
                drifts.add(DriftItem.missing(tf));
                continue;
            }

            // 속성 비교
            Map<String, PropertyChange> changes = compareProperties(tf, actual.get());
            if (!changes.isEmpty()) {
                drifts.add(DriftItem.modified(tf, actual.get(), changes));
            }
        }

        // 4. 실제에는 있는데 Terraform에 없는 리소스 (unmanaged)
        Set<String> tfArns = tfResources.stream().map(TfResource::getArn).collect(toSet());
        graphService.findAll().stream()
            .filter(n -> !tfArns.contains(n.getArn()))
            .forEach(n -> drifts.add(DriftItem.unmanaged(n)));

        return new DriftReport(drifts);
    }
}
```

### 3.2 Terraform State 소스

```yaml
ariadne:
  terraform:
    enabled: false
    sources:
      - type: local              # 로컬 파일
        path: /path/to/terraform.tfstate
      - type: s3                 # S3 remote state
        bucket: my-tf-state-bucket
        key: prod/terraform.tfstate
        region: ap-northeast-2
```

### 3.3 드리프트 보고서

```java
public record DriftItem(
    DriftType type,              // MISSING, MODIFIED, UNMANAGED
    String arn,
    String resourceName,
    String resourceType,
    Map<String, PropertyChange> changes,  // MODIFIED일 때만
    String terraformModule               // tf 모듈 경로
) {}

public enum DriftType {
    MISSING,     // TF에 있는데 실제로 없음 (삭제됨?)
    MODIFIED,    // 실제 속성이 TF 선언과 다름
    UNMANAGED    // 실제에 있는데 TF에 없음 (수동 생성?)
}
```

---

## 4. EventBridge 준실시간 보강 (선택적)

### 4.1 설계 원칙

- **기본 비활성** — 활성화하려면 AWS에 EventBridge 규칙 생성 필요
- 스냅샷 사이의 **중요 변경만 보강** (모든 이벤트를 처리하지 않음)
- "실시간"이 아닌 "준실시간(near-realtime)" — 분 단위 지연 허용

### 4.2 감시 대상 이벤트

| 이벤트 소스 | 이벤트 | 중요도 | 설명 |
|---|---|---|---|
| ec2.amazonaws.com | RunInstances | MEDIUM | 새 인스턴스 생성 |
| ec2.amazonaws.com | TerminateInstances | HIGH | 인스턴스 삭제 |
| ec2.amazonaws.com | AuthorizeSecurityGroupIngress | HIGH | SG 인바운드 규칙 추가 |
| ec2.amazonaws.com | RevokeSecurityGroupIngress | MEDIUM | SG 인바운드 규칙 제거 |
| rds.amazonaws.com | CreateDBInstance | MEDIUM | RDS 생성 |
| rds.amazonaws.com | DeleteDBInstance | HIGH | RDS 삭제 |
| rds.amazonaws.com | ModifyDBInstance | MEDIUM | RDS 수정 |

### 4.3 이벤트 수신 아키텍처

```
AWS EventBridge Rule
    ↓ (CloudTrail 이벤트 매칭)
SQS Queue (ariadne-events)
    ↓
Spring @SqsListener
    ↓
EventProcessor
    ↓
1. 해당 리소스만 재수집 (부분 스캔)
2. Neo4j 업데이트
3. 변경 로그 기록
4. 알림 조건 체크
```

```java
@Component
@RequiredArgsConstructor
@ConditionalOnProperty("ariadne.eventbridge.enabled")
public class EventBridgeListener {

    private final ResourceCollectorRegistry registry;
    private final NotificationService notificationService;

    @SqsListener("ariadne-events")
    public void handleEvent(CloudTrailEvent event) {
        String eventName = event.getEventName();
        String resourceId = extractResourceId(event);

        // 해당 리소스만 재수집
        ResourceCollector<?> collector = registry.getCollectorFor(eventName);
        if (collector != null) {
            collector.collectSingle(resourceId);
        }

        // 알림 조건 체크
        notificationService.checkAndNotify(event);
    }
}
```

### 4.4 EventBridge 설정 (Terraform/CloudFormation 예시)

```json
{
  "Source": ["aws.ec2", "aws.rds", "aws.ecs"],
  "DetailType": ["AWS API Call via CloudTrail"],
  "Detail": {
    "eventName": [
      "RunInstances", "TerminateInstances",
      "AuthorizeSecurityGroupIngress", "RevokeSecurityGroupIngress",
      "CreateDBInstance", "DeleteDBInstance", "ModifyDBInstance"
    ]
  }
}
```

---

## 5. 알림 시스템

### 5.1 알림 규칙

```yaml
ariadne:
  notifications:
    slack:
      enabled: false
      webhook-url: ${SLACK_WEBHOOK_URL}
      channel: "#infra-alerts"
    rules:
      - name: "SG 퍼블릭 오픈"
        condition: "sg-rule-added AND cidr == '0.0.0.0/0'"
        severity: CRITICAL
      - name: "DB 인스턴스 삭제"
        condition: "rds-deleted"
        severity: HIGH
      - name: "새 리소스 생성"
        condition: "resource-created"
        severity: INFO
```

### 5.2 Slack 메시지 포맷

```java
@Service
@ConditionalOnProperty("ariadne.notifications.slack.enabled")
public class SlackNotifier {

    public void send(InfraAlert alert) {
        var payload = Map.of(
            "channel", props.getChannel(),
            "blocks", List.of(
                headerBlock(alert.getSeverity(), alert.getTitle()),
                sectionBlock(
                    "*리소스*: " + alert.getResourceName() + "\n" +
                    "*변경*: " + alert.getDescription() + "\n" +
                    "*시각*: " + alert.getTimestamp() + "\n" +
                    "*계정*: " + alert.getAccountId()
                ),
                actionBlock(alert.getAriadneUrl())  // Ariadne UI 링크
            )
        );

        restClient.post()
            .uri(props.getWebhookUrl())
            .body(payload)
            .retrieve();
    }
}
```

---

## 6. API 설계

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/snapshots` | 스냅샷 목록 (페이징) |
| GET | `/api/snapshots/{id}` | 스냅샷 상세 |
| GET | `/api/snapshots/diff?from={id1}&to={id2}` | 두 스냅샷 diff |
| GET | `/api/snapshots/diff/latest` | 최근 vs 이전 diff |
| GET | `/api/timeline?from=&to=` | 기간별 변경 타임라인 |
| POST | `/api/drift/terraform` | Terraform 드리프트 감지 실행 |
| GET | `/api/drift/latest` | 최근 드리프트 보고서 |
| GET | `/api/events` | EventBridge 이벤트 로그 |

### Diff 응답 예시

```json
{
  "baseSnapshot": {"id": 142, "capturedAt": "2026-05-20T09:00:00Z"},
  "targetSnapshot": {"id": 143, "capturedAt": "2026-05-20T10:00:00Z"},
  "summary": {
    "added": 1,
    "removed": 0,
    "modified": 2,
    "totalChanges": 3
  },
  "changes": [
    {
      "type": "ADDED",
      "arn": "arn:aws:ec2:...:instance/i-new123",
      "resourceName": "temp-debug-server",
      "resourceType": "EC2"
    },
    {
      "type": "MODIFIED",
      "arn": "arn:aws:ec2:...:security-group/sg-abc",
      "resourceName": "prod-api-sg",
      "resourceType": "SecurityGroup",
      "changes": {
        "inboundRuleCount": {"old": 5, "new": 6}
      }
    }
  ]
}
```

---

## 7. 프론트엔드: 타임라인 뷰

### 7.1 타임라인 UI 구성

- **타임라인 바**: 수평 스크롤 가능한 시간축, 각 스냅샷을 점으로 표시
- **변경 마커**: 변경이 있는 스냅샷은 빨강(HIGH)/주황(MEDIUM)/회색(LOW) 점
- **기간 선택**: "최근 24시간" / "최근 7일" / "커스텀 범위"
- **diff 뷰**: 두 스냅샷 선택 → 좌우 분할 비교 또는 변경 목록

### 7.2 diff 시각화

- 토폴로지 뷰에서 변경 하이라이트:
  - **추가**: 노드/엣지 초록 테두리 + "NEW" 뱃지
  - **삭제**: 노드/엣지 빨강 점선 + "REMOVED" 뱃지
  - **수정**: 노드 주황 테두리 + 변경된 프로퍼티 툴팁

### 7.3 드리프트 뷰

- Terraform managed vs unmanaged 색상 구분
- 드리프트 항목 클릭 → TF 선언값 vs 실제값 나란히 비교
- "이 리소스는 Terraform에 없습니다" 경고 배너

---

## 8. 구현 순서

### Week 1: 스냅샷 시스템

1. Snapshot / SnapshotDiff JPA 엔티티 + 리포지토리
2. SnapshotService — 그래프 캡처 + 저장
3. DiffCalculator — 노드/엣지 diff 로직
4. @Scheduled 매시간 스냅샷
5. 스냅샷 API (목록, 상세, diff)

### Week 2: 타임라인 UI + Terraform 드리프트

6. 타임라인 바 컴포넌트 (스냅샷 점 + 변경 마커)
7. diff 뷰 — 변경 목록 + 토폴로지 하이라이트
8. TerraformDriftDetector — state 파싱 + 비교
9. 드리프트 보고서 API + UI
10. 스냅샷 보관 정책 (retention + cleanup)

### Week 3: EventBridge + 알림

11. EventBridge 리스너 (SQS 기반)
12. 부분 재수집 로직 (단일 리소스)
13. 알림 규칙 엔진
14. Slack 알림 발송기
15. 이벤트 로그 API + UI

### Week 4 (버퍼): 통합 + 안정화

16. dongne-v2에서 24시간 스냅샷 누적 테스트
17. diff 정확성 검증 (의도적 변경 → diff 확인)
18. Terraform state 연동 E2E 테스트
19. 5대 질문 #5 답변 가능 확인

---

## 9. Codex 검토 반영 사항

### 9.1 SnapshotDiff에 modifiedEdges 추가

**지적**: 엣지 속성 변경 추적 불가 — modifiedEdges 필드 누락.

**반영**:
```java
@Entity
@Table(name = "snapshot_diffs")
public class SnapshotDiff {
    // 기존 필드...

    @Column(columnDefinition = "jsonb")
    private String modifiedEdges;    // ← 추가: 속성 변경된 엣지

    // DiffCalculator에 엣지 수정 감지 추가
}
```

```java
// DiffCalculator 내 엣지 diff 로직 구현
private List<EdgeChange> diffEdges(GraphData base, GraphData target) {
    // 엣지 키: source_arn + relationship_type + target_arn
    Map<String, GraphEdge> baseEdges = base.edgesByKey();
    Map<String, GraphEdge> targetEdges = target.edgesByKey();

    List<EdgeChange> changes = new ArrayList<>();

    // 추가/삭제
    Sets.difference(targetEdges.keySet(), baseEdges.keySet())
        .forEach(k -> changes.add(EdgeChange.added(targetEdges.get(k))));
    Sets.difference(baseEdges.keySet(), targetEdges.keySet())
        .forEach(k -> changes.add(EdgeChange.removed(baseEdges.get(k))));

    // 수정 (속성 변경 — port, protocol 등)
    Sets.intersection(baseEdges.keySet(), targetEdges.keySet())
        .forEach(k -> {
            Map<String, PropertyChange> propDiff =
                diffProperties(baseEdges.get(k).getProps(), targetEdges.get(k).getProps());
            if (!propDiff.isEmpty()) {
                changes.add(EdgeChange.modified(targetEdges.get(k), propDiff));
            }
        });

    return changes;
}
```

### 9.2 Terraform State ARN 추출 규칙 정의

**지적**: state 구조별 ARN 추출, 리소스별 속성 매핑 규칙 미정의.

**반영**:
```java
/**
 * Terraform state JSON에서 리소스별 ARN을 추출하는 매퍼.
 * state 형식: terraform show -json 출력 기준.
 */
@Component
public class TfStateParser {

    // Terraform 리소스 타입 → ARN 속성명 매핑
    private static final Map<String, String> ARN_FIELD_MAP = Map.of(
        "aws_instance",          "arn",
        "aws_db_instance",       "arn",
        "aws_vpc",               "arn",
        "aws_subnet",            "arn",
        "aws_security_group",    "arn",
        "aws_lb",                "arn",
        "aws_ecs_cluster",       "arn",
        "aws_ecs_service",       "id",     // ECS 서비스는 id가 ARN
        "aws_s3_bucket",         "arn",
        "aws_lambda_function",   "arn",
        "aws_route53_zone",      "arn"
    );

    // 속성 비교 매핑: TF 속성명 → Neo4j 프로퍼티명
    private static final Map<String, Map<String, String>> PROPERTY_MAP = Map.of(
        "aws_instance", Map.of(
            "instance_type", "instanceType",
            "ami", "amiId",
            "private_ip", "privateIp",
            "public_ip", "publicIp"
        ),
        "aws_db_instance", Map.of(
            "engine", "engine",
            "engine_version", "engineVersion",
            "instance_class", "instanceClass",
            "multi_az", "multiAz",
            "storage_encrypted", "encrypted"
        )
        // ... 나머지 리소스 타입별 매핑
    );

    public List<TfResource> parse(String stateJson) {
        JsonNode root = objectMapper.readTree(stateJson);
        JsonNode resources = root.path("values").path("root_module").path("resources");

        List<TfResource> result = new ArrayList<>();
        for (JsonNode res : resources) {
            String type = res.path("type").asText();
            String arnField = ARN_FIELD_MAP.get(type);
            if (arnField == null) continue;  // 지원하지 않는 리소스 타입 skip

            String arn = res.path("values").path(arnField).asText();
            Map<String, Object> props = extractProperties(res, PROPERTY_MAP.get(type));

            result.add(new TfResource(type, arn, res.path("address").asText(), props));
        }
        return result;
    }
}
```

### 9.3 EventBridge 이벤트→리소스 매핑 구체화

**지적**: extractResourceId()와 getCollectorFor() 매핑이 추상화만 됨.

**반영**:
```java
@Component
public class EventResourceMapper {

    // CloudTrail 이벤트명 → 리소스 타입 + ID 추출 전략
    private static final Map<String, EventMapping> EVENT_MAP = Map.of(
        "RunInstances",
            new EventMapping("EC2", e -> extractFromResponseElements(e, "instancesSet.items[0].instanceId")),
        "TerminateInstances",
            new EventMapping("EC2", e -> extractFromRequestParams(e, "instancesSet.items[0].instanceId")),
        "AuthorizeSecurityGroupIngress",
            new EventMapping("SecurityGroup", e -> extractFromRequestParams(e, "groupId")),
        "RevokeSecurityGroupIngress",
            new EventMapping("SecurityGroup", e -> extractFromRequestParams(e, "groupId")),
        "CreateDBInstance",
            new EventMapping("RDS", e -> extractFromRequestParams(e, "dBInstanceIdentifier")),
        "DeleteDBInstance",
            new EventMapping("RDS", e -> extractFromRequestParams(e, "dBInstanceIdentifier")),
        "ModifyDBInstance",
            new EventMapping("RDS", e -> extractFromRequestParams(e, "dBInstanceIdentifier"))
    );

    public Optional<ResourceRef> map(CloudTrailEvent event) {
        EventMapping mapping = EVENT_MAP.get(event.getEventName());
        if (mapping == null) return Optional.empty();

        String resourceId = mapping.idExtractor().apply(event);
        return Optional.of(new ResourceRef(mapping.resourceType(), resourceId));
    }

    private record EventMapping(String resourceType, Function<CloudTrailEvent, String> idExtractor) {}
}
```

### 9.4 snapshot_diffs 보관 정책 추가

**지적**: snapshot_diffs 테이블의 보관/정리 규칙 누락.

**반영**:
```yaml
ariadne:
  snapshot:
    retention:
      # 기존: 스냅샷 보관 정책
      hourly: 48
      daily: 30
      weekly: 12
      # 추가: diff 보관 정책
      diffs:
        hourly-diffs: 48       # 최근 48시간의 시간별 diff 유지
        daily-diffs: 90        # 90일간 일별 집계 diff 유지
        weekly-diffs: 24       # 24주간 주별 집계 diff 유지
```

```java
// diff 정리: 스냅샷이 삭제되면 연관 diff도 cascade 삭제
// 추가: 일별/주별 집계 diff 생성 (여러 시간별 diff를 하나로 머지)
@Scheduled(cron = "0 30 2 * * *")  // 매일 새벽 2:30
public void aggregateAndCleanupDiffs() {
    // 1. 어제의 시간별 diff들을 하나의 일별 diff로 집계
    // 2. 48시간 이전의 시간별 diff 삭제
    // 3. 90일 이전의 일별 diff 삭제
}
```

### 9.5 JSONB 스케일 대응

**지적**: 전체 그래프 JSONB 저장은 리소스 증가 시 스토리지 부담.

**반영**: 점진적 마이그레이션 전략.
```
Phase 4 v1 (현재): 전체 그래프 JSONB 저장
  - 리소스 ~200개 기준 스냅샷 1건 ≈ 500KB
  - 시간별 48건 + 일별 30건 + 주별 12건 ≈ 최대 ~45MB (관리 가능)

Phase 4 v2 (필�� 시): 증분(incremental) 저장으로 전환
  - 최초 스냅샷만 전체 저장
  - 이후 스냅샷은 diff만 저장 (added/removed/modified만)
  - 특정 시점 복원 시: 최초 + diff 체인 적용

판단 기준: 스냅샷 1건이 5MB를 넘으면 v2로 전환 검토
```
