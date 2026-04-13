# Phase 1: AWS 수집기 MVP — 상세 기획서

> 상위 문서: [project-a-infra-mapper.md](../project-a-infra-mapper.md)
> 예상 기간: 5-6주 · 종료 조건: dongne-v2 AWS 계정에서 "전체 지도 1장" 출력
> 개발 원칙: [메인 기획서 > 개발 원칙](../project-a-infra-mapper.md#개발-원칙) 참조 — 모든 코드에 적용

---

## 1. 목표

AWS 계정의 핵심 리소스 9종을 수집해 Neo4j 그래프에 저장하고, React Flow로 토폴로지를 시각화하는 MVP를 완성한다.

### 종료 조건 (Definition of Done)

- [ ] `docker compose up`으로 전체 스택 기동 (Spring Boot + Neo4j + PostgreSQL + React)
- [ ] dongne-v2 AWS 계정 스캔 시 모든 핵심 리소스가 Neo4j에 노드/엣지로 저장됨
- [ ] React Flow에서 전체 토폴로지를 필터/그룹 가능한 상태로 렌더링
- [ ] "prod 환경에 뭐가 돌아가고 있어?" (5대 질문 #1) 에 답할 수 있음
- [ ] "이 RDS를 쓰는 애들이 누구야?" (5대 질문 #2) 에 답할 수 있음

---

## 2. 프로젝트 구조

```
ariadne/
├── docker-compose.yml
├── docker-compose.dev.yml          # LocalStack 포함 개발용
├── iam/
│   └── ariadne-readonly-policy.json # 최소권한 IAM 정책 템플릿
├── backend/                         # Spring Boot 모듈
│   ├── build.gradle.kts
│   ├── src/main/java/com/ariadne/
│   │   ├── AriadneApplication.java
│   │   ├── config/
│   │   │   ├── AwsConfig.java       # AWS SDK 클라이언트 빈 설정
│   │   │   ├── Neo4jConfig.java      # Spring Data Neo4j 설정
│   │   │   └── SchedulingConfig.java # 스캔 스케줄러 설정
│   │   ├── collector/               # AWS 리소스 수집기
│   │   │   ├── CollectorOrchestrator.java   # 전체 수집 오케스트레이션
│   │   │   ├── ResourceCollector.java       # 수집기 인터페이스
│   │   │   ├── ec2/
│   │   │   │   └── Ec2Collector.java
│   │   │   ├── rds/
│   │   │   │   └── RdsCollector.java
│   │   │   ├── vpc/
│   │   │   │   ├── VpcCollector.java
│   │   │   │   └── SubnetCollector.java
│   │   │   ├── sg/
│   │   │   │   └── SecurityGroupCollector.java
│   │   │   ├── elb/
│   │   │   │   └── AlbCollector.java
│   │   │   ├── ecs/
│   │   │   │   └── EcsCollector.java
│   │   │   ├── s3/
│   │   │   │   └── S3Collector.java
│   │   │   ├── lambda/
│   │   │   │   └── LambdaCollector.java
│   │   │   └── route53/
│   │   │       └── Route53Collector.java
│   │   ├── graph/                   # Neo4j 그래프 모델
│   │   │   ├── node/                # @Node 엔티티
│   │   │   │   ├── AwsResource.java         # 추상 베이스
│   │   │   │   ├── Ec2Instance.java
│   │   │   │   ├── RdsInstance.java
│   │   │   │   ├── Vpc.java
│   │   │   │   ├── Subnet.java
│   │   │   │   ├── SecurityGroup.java
│   │   │   │   ├── LoadBalancer.java
│   │   │   │   ├── EcsCluster.java
│   │   │   │   ├── EcsService.java
│   │   │   │   ├── S3Bucket.java
│   │   │   │   ├── LambdaFunction.java
│   │   │   │   └── Route53Zone.java
│   │   │   ├── relationship/        # 관계 정의
│   │   │   │   └── RelationshipTypes.java
│   │   │   └── repository/          # Spring Data Neo4j 리포지토리
│   │   │       ├── Ec2Repository.java
│   │   │       ├── RdsRepository.java
│   │   │       └── ...
│   │   ├── api/                     # REST API
│   │   │   ├── GraphController.java         # 전체 그래프 조회
│   │   │   ├── ResourceController.java      # 개별 리소스 조회
│   │   │   ├── ScanController.java          # 수동 스캔 트리거
│   │   │   └── dto/
│   │   │       ├── GraphResponse.java       # React Flow 형식 응답
│   │   │       └── ResourceDetailResponse.java
│   │   └── scan/
│   │       └── ScanScheduler.java           # @Scheduled 기반 주기 스캔
│   └── src/test/
│       ├── java/com/ariadne/
│       │   ├── collector/           # 수집기 단위 테스트
│       │   └── integration/         # LocalStack 통합 테스트
│       └── resources/
│           └── application-test.yml
├── frontend/                        # React 앱
│   ├── package.json
│   ├── src/
│   │   ├── App.tsx
│   │   ├── components/
│   │   │   ├── TopologyView.tsx     # React Flow 메인 뷰
│   │   │   ├── FilterPanel.tsx      # 리소스 타입별 필터
│   │   │   ├── ResourceDetail.tsx   # 노드 클릭 시 상세 패널
│   │   │   └── nodes/               # 커스텀 노드 컴포넌트
│   │   │       ├── Ec2Node.tsx
│   │   │       ├── RdsNode.tsx
│   │   │       ├── VpcGroupNode.tsx # VPC를 그룹 노드로
│   │   │       └── ...
│   │   ├── hooks/
│   │   │   ├── useGraph.ts          # 그래프 데이터 fetch
│   │   │   └── useFilters.ts
│   │   ├── api/
│   │   │   └── client.ts            # API 클라이언트
│   │   └── types/
│   │       └── graph.ts             # 타입 정의
│   └── tailwind.config.js
└── docs/
    └── architecture.md
```

---

## 3. Neo4j 그래프 스키마

### 3.1 노드 레이블 및 프로퍼티

모든 노드는 `AwsResource` 공통 프로퍼티를 가진다:

```
(:AwsResource {
  arn: String (PK),        # AWS ARN — 전역 고유 식별자
  resourceId: String,      # AWS 리소스 ID (i-xxx, vpc-xxx 등)
  resourceType: String,    # "EC2", "RDS", "VPC" 등
  name: String,            # Name 태그 또는 리소스 이름
  region: String,          # ap-northeast-2
  accountId: String,       # AWS 계정 ID
  environment: String,     # "prod" / "staging" / "dev" (태그에서 추론)
  collectedAt: DateTime,   # 수집 시각
  tags: Map<String,String> # AWS 태그 (민감정보 리댁션 적용)
})
```

#### EC2 Instance
```
(:Ec2Instance:AwsResource {
  instanceType: String,    # t3.medium
  state: String,           # running / stopped
  privateIp: String,
  publicIp: String,        # nullable
  amiId: String,
  launchTime: DateTime,
  platform: String         # linux / windows
})
```

#### RDS Instance
```
(:RdsInstance:AwsResource {
  engine: String,          # mysql / postgresql
  engineVersion: String,
  instanceClass: String,   # db.r5.large
  status: String,          # available
  endpoint: String,        # hostname:port
  multiAz: Boolean,
  storageGb: Integer,
  encrypted: Boolean
})
```

#### VPC
```
(:Vpc:AwsResource {
  cidrBlock: String,       # 10.0.0.0/16
  isDefault: Boolean,
  state: String
})
```

#### Subnet
```
(:Subnet:AwsResource {
  cidrBlock: String,
  availabilityZone: String,
  isPublic: Boolean,       # 라우팅 테이블 기반 판단
  availableIpCount: Integer
})
```

#### Security Group
```
(:SecurityGroup:AwsResource {
  groupId: String,
  description: String,
  inboundRuleCount: Integer,
  outboundRuleCount: Integer
})
```

#### ALB / NLB
```
(:LoadBalancer:AwsResource {
  type: String,            # application / network
  scheme: String,          # internet-facing / internal
  dnsName: String,
  state: String
})
```

#### ECS Cluster / Service
```
(:EcsCluster:AwsResource {
  status: String,
  runningTaskCount: Integer,
  registeredContainerInstanceCount: Integer
})

(:EcsService:AwsResource {
  desiredCount: Integer,
  runningCount: Integer,
  launchType: String,      # FARGATE / EC2
  taskDefinition: String   # family:revision
})
```

#### S3 Bucket
```
(:S3Bucket:AwsResource {
  creationDate: DateTime,
  versioningEnabled: Boolean,
  encryptionType: String,  # AES256 / aws:kms / none
  publicAccessBlocked: Boolean
})
```

#### Lambda Function
```
(:LambdaFunction:AwsResource {
  runtime: String,         # java21 / python3.12
  handler: String,
  memoryMb: Integer,
  timeoutSeconds: Integer,
  lastModified: DateTime,
  codeSize: Long
})
```

#### Route53 Zone / Record
```
(:Route53Zone:AwsResource {
  hostedZoneId: String,
  domainName: String,
  isPrivate: Boolean,
  recordCount: Integer
})
```

### 3.2 관계(엣지) 정의

```cypher
# 네트워크 소속 관계
(:Ec2Instance)-[:BELONGS_TO]->(:Subnet)
(:Subnet)-[:BELONGS_TO]->(:Vpc)
(:RdsInstance)-[:BELONGS_TO]->(:Subnet)   # 서브넷 그룹의 서브넷들
(:LambdaFunction)-[:BELONGS_TO]->(:Vpc)   # VPC 내 Lambda만

# 보안 그룹 연결
(:Ec2Instance)-[:HAS_SG]->(:SecurityGroup)
(:RdsInstance)-[:HAS_SG]->(:SecurityGroup)
(:LoadBalancer)-[:HAS_SG]->(:SecurityGroup)
(:EcsService)-[:HAS_SG]->(:SecurityGroup)
(:LambdaFunction)-[:HAS_SG]->(:SecurityGroup)

# 로드밸런서 라우팅
(:LoadBalancer)-[:ROUTES_TO {port: Integer, protocol: String}]->(:Ec2Instance)
(:LoadBalancer)-[:ROUTES_TO {port: Integer, protocol: String}]->(:EcsService)

# ECS 구조
(:EcsService)-[:RUNS_IN]->(:EcsCluster)
(:EcsService)-[:USES_TASK_DEF {taskDefinition: String}]->(:EcsCluster)

# DNS 매핑
(:Route53Zone)-[:HAS_RECORD {recordType: String, recordName: String}]->(:LoadBalancer)
(:Route53Zone)-[:HAS_RECORD {recordType: String, recordName: String}]->(:Ec2Instance)
(:Route53Zone)-[:HAS_RECORD {recordType: String, recordName: String}]->(:RdsInstance)

# Lambda 트리거 (S3 → Lambda, ALB → Lambda)
(:S3Bucket)-[:TRIGGERS]->(:LambdaFunction)
(:LoadBalancer)-[:ROUTES_TO]->(:LambdaFunction)

# 범용 접근 관계 (SG 규칙 기반 — Phase 2에서 상세화)
(:SecurityGroup)-[:ALLOWS_FROM {port: String, protocol: String, cidr: String}]->(:SecurityGroup)
```

### 3.3 Neo4j 인덱스

```cypher
CREATE INDEX idx_arn FOR (n:AwsResource) ON (n.arn);
CREATE INDEX idx_resource_id FOR (n:AwsResource) ON (n.resourceId);
CREATE INDEX idx_resource_type FOR (n:AwsResource) ON (n.resourceType);
CREATE INDEX idx_environment FOR (n:AwsResource) ON (n.environment);
CREATE INDEX idx_vpc_id FOR (n:Vpc) ON (n.resourceId);
```

---

## 4. Spring Boot 백엔드 상세 설계

### 4.1 의존성 (build.gradle.kts)

```kotlin
dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")  // PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // AWS SDK v2
    implementation(platform("software.amazon.awssdk:bom:2.25+"))
    implementation("software.amazon.awssdk:ec2")
    implementation("software.amazon.awssdk:rds")
    implementation("software.amazon.awssdk:ecs")
    implementation("software.amazon.awssdk:elasticloadbalancingv2")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:lambda")
    implementation("software.amazon.awssdk:route53")
    implementation("software.amazon.awssdk:sts")   // 계정 ID 확인용

    // DB
    runtimeOnly("org.postgresql:postgresql")

    // 유틸
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:neo4j")
    testImplementation("org.testcontainers:localstack")
    testImplementation("org.testcontainers:postgresql")
}
```

### 4.2 수집기 인터페이스

```java
public interface ResourceCollector<T extends AwsResource> {

    /** 이 수집기가 담당하는 리소스 타입 */
    String resourceType();

    /** AWS에서 리소스 목록 수집 */
    List<T> collect(AwsCollectContext context);

    /** 수집된 리소스 간 관계 생성 (노드 저장 후 호출) */
    List<GraphRelationship> resolveRelationships(List<T> resources, GraphLookup lookup);
}
```

```java
@Value
public class AwsCollectContext {
    String accountId;
    String region;
    Instant collectedAt;
}
```

### 4.3 수집 오케스트레이션

```java
@Service
@RequiredArgsConstructor
public class CollectorOrchestrator {

    private final List<ResourceCollector<?>> collectors;
    private final Neo4jTemplate neo4jTemplate;

    /**
     * 전체 수집 프로세스:
     * 1) 모든 수집기를 병렬 실행하여 리소스 수집
     * 2) 노드를 Neo4j에 저장 (MERGE — 존재하면 업데이트)
     * 3) 관계를 해소하여 엣지 저장
     */
    @Async
    public CompletableFuture<ScanResult> runFullScan(AwsCollectContext context) {
        // 1. 병렬 수집
        List<CompletableFuture<CollectResult>> futures = collectors.stream()
            .map(c -> CompletableFuture.supplyAsync(() -> {
                List<?> resources = c.collect(context);
                return new CollectResult(c.resourceType(), resources);
            }))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 2. 노드 저장 (MERGE)
        futures.forEach(f -> {
            CollectResult result = f.join();
            result.resources().forEach(r -> neo4jTemplate.save(r));
        });

        // 3. 관계 해소
        GraphLookup lookup = buildLookup(); // Neo4j에서 현재 노드 맵 조회
        collectors.forEach(c -> {
            List<GraphRelationship> rels = c.resolveRelationships(/* ... */);
            rels.forEach(this::saveRelationship);
        });

        return CompletableFuture.completedFuture(new ScanResult(/* ... */));
    }
}
```

### 4.4 수집기 구현 예시 (EC2)

```java
@Component
@RequiredArgsConstructor
public class Ec2Collector implements ResourceCollector<Ec2Instance> {

    private final Ec2Client ec2Client;

    @Override
    public String resourceType() { return "EC2"; }

    @Override
    public List<Ec2Instance> collect(AwsCollectContext ctx) {
        DescribeInstancesResponse response = ec2Client.describeInstances();

        return response.reservations().stream()
            .flatMap(r -> r.instances().stream())
            .map(instance -> Ec2Instance.builder()
                .arn(buildArn(ctx, instance.instanceId()))
                .resourceId(instance.instanceId())
                .resourceType("EC2")
                .name(extractNameTag(instance.tags()))
                .region(ctx.getRegion())
                .accountId(ctx.getAccountId())
                .environment(inferEnvironment(instance.tags()))
                .collectedAt(ctx.getCollectedAt())
                .tags(toTagMap(instance.tags()))
                // EC2 고유
                .instanceType(instance.instanceTypeAsString())
                .state(instance.state().nameAsString())
                .privateIp(instance.privateIpAddress())
                .publicIp(instance.publicIpAddress())
                .amiId(instance.imageId())
                .launchTime(instance.launchTime())
                .platform(instance.platformAsString())
                // 관계 해소용 임시 필드
                .subnetId(instance.subnetId())
                .securityGroupIds(instance.securityGroups().stream()
                    .map(GroupIdentifier::groupId).toList())
                .build())
            .toList();
    }

    @Override
    public List<GraphRelationship> resolveRelationships(
            List<Ec2Instance> resources, GraphLookup lookup) {
        List<GraphRelationship> rels = new ArrayList<>();
        for (Ec2Instance ec2 : resources) {
            // EC2 → Subnet (BELONGS_TO)
            lookup.findByResourceId(ec2.getSubnetId())
                .ifPresent(subnet -> rels.add(
                    new GraphRelationship(ec2.getArn(), "BELONGS_TO", subnet.getArn())));

            // EC2 → SG (HAS_SG)
            for (String sgId : ec2.getSecurityGroupIds()) {
                lookup.findByResourceId(sgId)
                    .ifPresent(sg -> rels.add(
                        new GraphRelationship(ec2.getArn(), "HAS_SG", sg.getArn())));
            }
        }
        return rels;
    }
}
```

### 4.5 REST API 설계

| Method | Path | 설명 | 응답 |
|---|---|---|---|
| POST | `/api/scan` | 수동 스캔 트리거 | `ScanResult` (수집 통계) |
| GET | `/api/scan/status` | 현재 스캔 상태 | `ScanStatus` |
| GET | `/api/graph` | 전체 그래프 (React Flow 형식) | `GraphResponse` |
| GET | `/api/graph?env=prod` | 환경별 필터 | `GraphResponse` |
| GET | `/api/graph?type=EC2,RDS` | 리소스 타입 필터 | `GraphResponse` |
| GET | `/api/graph?vpc=vpc-xxx` | VPC별 필터 | `GraphResponse` |
| GET | `/api/resources/{arn}` | 개별 리소스 상세 | `ResourceDetailResponse` |
| GET | `/api/resources/{arn}/connections` | 연결된 리소스 목록 | `List<ConnectedResource>` |

#### GraphResponse (React Flow 형식)

```json
{
  "nodes": [
    {
      "id": "arn:aws:ec2:ap-northeast-2:123:instance/i-abc",
      "type": "ec2",
      "position": { "x": 0, "y": 0 },
      "data": {
        "label": "web-server-1",
        "resourceType": "EC2",
        "state": "running",
        "instanceType": "t3.medium",
        "environment": "prod"
      }
    }
  ],
  "edges": [
    {
      "id": "edge-1",
      "source": "arn:aws:ec2:...",
      "target": "arn:aws:ec2:...:subnet/subnet-xxx",
      "type": "BELONGS_TO",
      "animated": false
    }
  ],
  "metadata": {
    "totalNodes": 47,
    "totalEdges": 82,
    "collectedAt": "2026-04-15T10:00:00Z",
    "scanDurationMs": 12340
  }
}
```

---

## 5. React Flow 프론트엔드 상세 설계

### 5.1 커스텀 노드 타입

| 노드 타입 | 시각화 | 색상 계열 |
|---|---|---|
| `ec2` | 서버 아이콘 + 이름 + 상태 뱃지 | 주황 (AWS EC2 색상) |
| `rds` | DB 아이콘 + 이름 + 엔진 | 파랑 |
| `vpc` | 그룹 노드 (자식 노드 감싸기) | 회색 테두리 |
| `subnet` | 그룹 노드 (VPC 내부) | 연회색 |
| `sg` | 방패 아이콘 + 이름 | 빨강 |
| `alb` | 로드밸런서 아이콘 + DNS | 보라 |
| `ecs-cluster` | 그룹 노드 + 서비스들 | 초록 |
| `ecs-service` | 컨테이너 아이콘 + 이름 | 연초록 |
| `s3` | 버킷 아이콘 + 이름 | 녹색 |
| `lambda` | Lambda 아이콘 + 이름 | 주황-노랑 |
| `route53` | DNS 아이콘 + 도메인 | 보라 |

### 5.2 레이아웃 전략

- **기본 레이아웃**: dagre 알고리즘 (계층형)
  - VPC → Subnet → EC2/RDS 순서로 좌→우 또는 상→하 배치
- **그룹 노드**: VPC와 Subnet은 React Flow의 Group Node로 구현
  - 내부 리소스를 시각적으로 감싸서 네트워크 경계 표현
- **클러스터링**: 리소스 50개 초과 시 environment별로 자동 그룹화
- **미니맵**: React Flow의 MiniMap 컴포넌트로 전체 조감도

### 5.3 상호작용

- **노드 클릭**: 우측 패널에 리소스 상세 정보 표시
- **노드 더블클릭**: 해당 리소스의 연결된 리소스만 하이라이트 (역참조 추적)
- **필터 패널**: 리소스 타입 체크박스 + 환경 드롭다운 + VPC 드롭다운
- **검색**: 리소스 이름/ID로 검색 → 해당 노드로 포커스 이동

---

## 6. Docker Compose 구성

```yaml
# docker-compose.yml
services:
  backend:
    build: ./backend
    ports:
      - "8080:8080"
    environment:
      SPRING_NEO4J_URI: bolt://neo4j:7687
      SPRING_NEO4J_AUTHENTICATION_USERNAME: neo4j
      SPRING_NEO4J_AUTHENTICATION_PASSWORD: ariadne-dev
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ariadne
      AWS_REGION: ap-northeast-2
    depends_on:
      neo4j:
        condition: service_healthy
      postgres:
        condition: service_healthy

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    environment:
      REACT_APP_API_URL: http://localhost:8080

  neo4j:
    image: neo4j:5-community
    ports:
      - "7474:7474"   # Neo4j Browser
      - "7687:7687"   # Bolt
    environment:
      NEO4J_AUTH: neo4j/ariadne-dev
      NEO4J_PLUGINS: '["apoc"]'
    volumes:
      - neo4j_data:/data
    healthcheck:
      test: ["CMD-SHELL", "neo4j status"]
      interval: 10s
      timeout: 5s
      retries: 5

  postgres:
    image: postgres:16-alpine
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: ariadne
      POSTGRES_USER: ariadne
      POSTGRES_PASSWORD: ariadne-dev
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ariadne"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  neo4j_data:
  postgres_data:
```

```yaml
# docker-compose.dev.yml (extends docker-compose.yml)
services:
  localstack:
    image: localstack/localstack:latest
    ports:
      - "4566:4566"
    environment:
      SERVICES: ec2,rds,ecs,elbv2,s3,lambda,route53,sts
      DEFAULT_REGION: ap-northeast-2

  backend:
    environment:
      AWS_ENDPOINT_URL: http://localstack:4566
      AWS_ACCESS_KEY_ID: test
      AWS_SECRET_ACCESS_KEY: test
```

---

## 7. 구현 순서 (주차별)

### Week 1: 프로젝트 스캐폴딩 + 첫 수집기

1. Spring Initializr로 프로젝트 생성
2. Docker Compose 구성 (Neo4j + PostgreSQL + LocalStack)
3. Spring Data Neo4j 설정 + `AwsResource` 추상 노드 엔티티
4. `VpcCollector` 구현 (가장 단순 — describe_vpcs → 노드 저장)
5. `SubnetCollector` 구현 + `BELONGS_TO` 관계
6. 동작 확인: LocalStack에 VPC/Subnet 생성 → 수집 → Neo4j Browser에서 확인

### Week 2: 핵심 수집기 완성

7. `SecurityGroupCollector` 구현
8. `Ec2Collector` 구현 + BELONGS_TO(Subnet), HAS_SG 관계
9. `RdsCollector` 구현 + BELONGS_TO(Subnet), HAS_SG 관계
10. `AlbCollector` 구현 + ROUTES_TO, HAS_SG 관계
11. `CollectorOrchestrator` — 병렬 수집 + 2단계(노드→관계) 저장

### Week 3: API + 기초 프론트엔드

12. `GraphController` — 전체 그래프 조회 API (React Flow 형식 변환)
13. `ResourceController` — 개별 리소스 상세 + 연결 목록
14. React 프로젝트 초기화 + React Flow 기본 뷰
15. 커스텀 노드 컴포넌트 (EC2, RDS, VPC 그룹, SG)
16. dagre 레이아웃 적용

### Week 4: 프론트엔드 완성 + 필터링

17. 필터 패널 (리소스 타입, 환경, VPC)
18. 노드 클릭 → 상세 패널
19. 노드 더블클릭 → 연결 하이라이트 (역참조)
20. 검색 기능
21. 미니맵 + 전체 조감도

### Week 5: 확장 수집기 (2차)

22. `EcsCollector` (Cluster + Service) + RUNS_IN 관계
23. `S3Collector` + TRIGGERS(Lambda) 관계
24. `LambdaCollector` + BELONGS_TO(VPC), HAS_SG 관계
25. `Route53Collector` + HAS_RECORD 관계
26. 프론트엔드에 신규 노드 타입 추가

### Week 6: 통합 테스트 + dongne-v2 검증

27. LocalStack 통합 테스트 작성
28. dongne-v2 실계정 연동 테스트
29. 그래프 스키마 안정화 (실 데이터 기반 조정)
30. 버그 수정 + UI 다듬기
31. Phase 1 종료 조건 체크리스트 확인

---

## 8. 테스트 전략

### 단위 테스트
- 각 수집기의 AWS 응답 → 노드 변환 로직
- 관계 해소 로직 (올바른 엣지 생성)
- 환경 추론 로직 (태그 → environment 매핑)

### 통합 테스트 (Testcontainers + LocalStack)
- LocalStack에 AWS 리소스 생성 → 수집기 실행 → Neo4j 확인
- 전체 스캔 오케스트레이션 → API 응답 검증

### E2E (수동)
- dongne-v2 실계정 스캔 → React Flow 시각화 확인
- 5대 질문 #1, #2에 답할 수 있는지 수동 검증

---

## 9. IAM 최소권한 정책

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AriadneReadOnly",
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeInstances",
        "ec2:DescribeVpcs",
        "ec2:DescribeSubnets",
        "ec2:DescribeSecurityGroups",
        "ec2:DescribeRouteTables",
        "rds:DescribeDBInstances",
        "rds:DescribeDBSubnetGroups",
        "ecs:ListClusters",
        "ecs:DescribeClusters",
        "ecs:ListServices",
        "ecs:DescribeServices",
        "ecs:DescribeTaskDefinition",
        "elasticloadbalancing:DescribeLoadBalancers",
        "elasticloadbalancing:DescribeTargetGroups",
        "elasticloadbalancing:DescribeTargetHealth",
        "elasticloadbalancing:DescribeListeners",
        "s3:ListAllMyBuckets",
        "s3:GetBucketLocation",
        "s3:GetBucketEncryption",
        "s3:GetBucketVersioning",
        "s3:GetBucketPublicAccessBlock",
        "s3:GetBucketNotification",
        "lambda:ListFunctions",
        "lambda:GetFunction",
        "lambda:ListEventSourceMappings",
        "route53:ListHostedZones",
        "route53:ListResourceRecordSets",
        "sts:GetCallerIdentity"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## 10. 환경 추론 전략

태그에서 환경(prod/staging/dev)을 추론하는 전략:

```java
public static String inferEnvironment(Map<String, String> tags) {
    // 1순위: 명시적 Environment 태그
    String env = tags.getOrDefault("Environment",
                 tags.getOrDefault("env",
                 tags.getOrDefault("Env", null)));
    if (env != null) return normalize(env);

    // 2순위: Name 태그에서 추론
    String name = tags.getOrDefault("Name", "");
    if (name.contains("prod")) return "prod";
    if (name.contains("staging") || name.contains("stg")) return "staging";
    if (name.contains("dev")) return "dev";

    // 3순위: 알 수 없음
    return "unknown";
}
```

---

## 11. Codex 검토 반영 사항

> 아래는 Codex(GPT-5.4) 검토에서 지적된 사항과 반영 방안이다. 구현 시 반드시 적용할 것.

### 11.1 Spring Data Neo4j 타입 수정

**지적**: `AwsResource.tags: Map<String,String>`은 SDN 기본 프로퍼티 타입이 아님.

**반영**:
```java
@Node
public abstract class AwsResource {
    // ...
    @CompositeProperty(prefix = "tag")
    private Map<String, String> tags;
    // 또는 태그가 많으면 별도 노드:
    // @Relationship("HAS_TAG") private List<Tag> tags;
}
```

### 11.2 AWS SDK 페이지���이션 필수 적용

**지적**: `describeInstances()` 1회 호출로는 실계정 전체 수집 누락.

**반영**: 모든 수집기에 Paginator 사용 강제.
```java
// Before (잘못됨)
ec2Client.describeInstances();

// After (올바름)
ec2Client.describeInstancesPaginator().reservations().stream()
    .flatMap(r -> r.instances().stream())
    .map(this::toNode)
    .toList();
```

### 11.3 삭제된 리소스 처리 정��

**지적**: AWS에서 삭제된 리소스의 Neo4j 정리 전략 없음.

**반영**: 스캔 완료 후 **stale 마킹 → 다음 스캔에도 없으면 삭제** 전략.
```java
// 수집 후
1. 이번 스캔에서 수집된 ARN 목록을 Set으로 보관
2. Neo4j의 기존 노드 중 이번 스캔에 없는 것 → stale=true, staleSince=now
3. 이전 스캔에서 이미 stale이고 이번에도 없으면 → 노드 + 관계 삭제
4. stale 노드는 UI에서 점선 + "삭제 추정" 뱃지로 표시
```

### 11.4 Neo4j 스키마 수정

**지적 1**: ARN은 INDEX가 아닌 UNIQUE CONSTRAINT 필요.
```cypher
-- Before
CREATE INDEX idx_arn FOR (n:AwsResource) ON (n.arn);
-- After
CREATE CONSTRAINT unique_arn FOR (n:AwsResource) REQUIRE n.arn IS UNIQUE;
```

**지적 2**: `USES_TASK_DEF→EcsCluster`는 의미 오류. Phase 2에서 TaskDefinition 별도 노드로 분리.
```cypher
-- Before (잘못됨)
(:EcsService)-[:USES_TASK_DEF {taskDefinition}]->(:EcsCluster)

-- After
(:EcsService)-[:RUNS_IN]->(:EcsCluster)
-- TaskDefinition은 Phase 2에서 별도 노드로 추가
```

**지적 3**: RDS는 DbSubnetGroup, Lambda는 Subnet 관계 필요.
```cypher
(:RdsInstance)-[:IN_SUBNET_GROUP]->(:DbSubnetGroup)
(:DbSubnetGroup)-[:CONTAINS]->(:Subnet)
(:LambdaFunction)-[:BELONGS_TO]->(:Subnet)  -- VPC가 아닌 Subnet으로 수정
```

**지적 4**: Route53 등 글로벌 리소스는 region = "global".
```java
// Route53Collector
.region("global")  // ap-northeast-2가 아닌 "global"
```

### 11.5 API 설계 수정

**지적 1**: 비동기 스캔은 `202 Accepted + scanId`.
```
POST /api/scan → 202 Accepted
{
  "scanId": "scan-20260415-001",
  "status": "RUNNING",
  "startedAt": "2026-04-15T10:00:00Z"
}

GET /api/scan/{scanId}/status → 200 OK
{
  "scanId": "scan-20260415-001",
  "status": "COMPLETED",  // RUNNING | COMPLETED | FAILED | PARTIAL
  "progress": { "collected": 8, "total": 9, "failed": ["Route53"] }
}
```

**지적 2**: ARN을 path에서 query param으로 변경.
```
-- Before
GET /api/resources/{arn}
-- After
GET /api/resources?arn=arn:aws:ec2:ap-northeast-2:123:instance/i-abc
GET /api/resources/{resourceId}  -- 짧은 ID(i-abc)로도 조회 가능
```

**지적 3**: position 계산은 프론트엔드 책임. 백엔드 GraphResponse에서 position 제거.
```json
// GraphResponse — position 필드 제거, dagre는 프론트에서 계산
{
  "nodes": [{ "id": "arn:...", "type": "ec2", "data": {...} }],
  "edges": [...]
}
```

### 11.6 오케스트레이터 부분 실패 처리

**지적**: 수집기 실패/타임아웃/부분 성공 처리 없음.

**반영**:
```java
@Async
public CompletableFuture<ScanResult> runFullScan(AwsCollectContext ctx) {
    List<CollectResult> results = collectors.stream()
        .map(c -> {
            try {
                return CompletableFuture.supplyAsync(() -> c.collect(ctx))
                    .orTimeout(60, TimeUnit.SECONDS)
                    .join();
            } catch (Exception e) {
                log.error("수집기 실패: {}", c.resourceType(), e);
                return CollectResult.failed(c.resourceType(), e.getMessage());
            }
        })
        .toList();

    // 부분 성공도 허용 — 실패한 수집기만 보고
    var succeeded = results.stream().filter(CollectResult::isSuccess).toList();
    var failed = results.stream().filter(r -> !r.isSuccess()).toList();

    // 성공한 것만 저장
    succeeded.forEach(this::saveNodes);
    resolveRelationships(succeeded);

    return CompletableFuture.completedFuture(
        ScanResult.partial(succeeded, failed));
}
```

### 11.7 PostgreSQL 용도 명확화

**지적**: JPA 엔티티/테이블이 정의되지 않음.

**반영**: Phase 1에서 PostgreSQL은 **스캔 메타데이터** 저장용으로만 사용.
```java
@Entity
@Table(name = "scan_runs")
public class ScanRun {
    @Id @GeneratedValue
    private Long id;
    private String scanId;
    private Instant startedAt;
    private Instant completedAt;
    private ScanStatus status;     // RUNNING, COMPLETED, FAILED, PARTIAL
    private Integer totalNodes;
    private Integer totalEdges;
    private Long durationMs;

    @Column(columnDefinition = "jsonb")
    private String collectorResults;  // 수집기별 성공/실패 상세
}
```
스냅샷 히스토리는 Phase 4에서 추가.

### 11.8 일정 재조정

**지적**: Week 2 과부하, 통합 테스트가 너무 늦음.

**반영**:
| 주차 | 기존 | 수정 |
|---|---|---|
| Week 2 | SG+EC2+RDS+ALB+오케스트레이터 | SG+EC2+RDS만. 오케스트레이터는 Week 3으로 |
| Week 3 | API+프론트엔드 전체 | ALB+오케스트레이터+API. **최소 happy-path 통합 테스트 시작** |
| Week 4 | 프론트엔드 완성 | 프론트엔드 전체 (기본 뷰+필터+상세) |
| Week 5 | 확장 수집기 4종 | 확장 수집기 4종 + 스키마 안정화 (Week 6에서 앞당김) |
| Week 6 | 통합 테스트+검증 | dongne-v2 검증 + 버그 수정 + 종료 조건 체크 |

### 11.9 compute→RDS 사용 관계 보강

**지적**: `HAS_SG`만으로는 "이 RDS를 쓰는 서비스는?" 질문에 약함.

**반영**: 동일 VPC + 동일 SG 포트 매칭 기반 **추론 관계** 추가.
```cypher
-- 추론 규칙: 같은 SG를 공유하고, SG에 DB 포트(3306/5432) 규칙이 있으면
-- compute → LIKELY_USES → RDS 추론 엣지 생성
(:Ec2Instance)-[:LIKELY_USES {confidence: "medium", reason: "shared-sg-db-port"}]->(:RdsInstance)
(:EcsService)-[:LIKELY_USES {confidence: "medium", reason: "shared-sg-db-port"}]->(:RdsInstance)
```
이 추론 엣지는 Phase 2의 SG 규칙 상세화 후 정확도가 올라감.
