# Neo4j 스키마 진화 문서

> 이 문서는 Phase 1→4를 거치며 진화하는 Neo4j 그래프 스키마의 **전체 그림**을 한 곳에 정리한다.
> 각 Phase 상세 기획서와 동기화 유지 필요.

---

## 1. 스키마 진화 요약

```
Phase 1: 기본 노드 9종 + 기본 관계 7종
Phase 2: +CidrSource, +IamRole, +EcsTaskDefinition, +NginxConfig
         +ALLOWS_FROM/TO, +ALLOWS_SELF, +EGRESS_TO, +HAS_ROLE, +LIKELY_USES
Phase 3: +tier 프로퍼티 (모든 AwsResource), +AuditFinding (가상)
Phase 4: 스키마 변경 없음 (스냅��은 PostgreSQL)
```

---

## 2. 최종 노드 레이블 (Phase 4 완료 시점)

### 공통 프로퍼티 (AwsResource)

```
(:AwsResource {
  arn: String,                    # UNIQUE CONSTRAINT (PK)
  resourceId: String,             # INDEX
  resourceType: String,           # INDEX
  name: String,
  region: String,                 # "ap-northeast-2" 또는 "global" (Route53 등)
  accountId: String,
  environment: String,            # INDEX — "prod" / "staging" / "dev" / "unknown"
  collectedAt: DateTime,
  stale: Boolean,                 # true면 삭�� 추정 (이번 스캔에서 미수집)
  staleSince: DateTime,           # stale 시작 시각
  tier: String,                   # Phase 3 — "web-tier" / "app-tier" / "db-tier" / ...
  tierConfidence: String,         # "rule" / "auto" / "tentative"
  tags: @CompositeProperty        # SDN @CompositeProperty(prefix="tag")
})
```

### Phase 1 노드 (9종)

| 레이블 | 고유 프로퍼티 | 추가 시점 |
|---|---|---|
| `Ec2Instance` | instanceType, state, privateIp, publicIp, amiId, launchTime, platform | Phase 1 |
| `RdsInstance` | engine, engineVersion, instanceClass, status, endpoint, multiAz, storageGb, encrypted | Phase 1 |
| `Vpc` | cidrBlock, isDefault, state | Phase 1 |
| `Subnet` | cidrBlock, availabilityZone, isPublic, availableIpCount | Phase 1 |
| `SecurityGroup` | groupId, description, inboundRuleCount, outboundRuleCount | Phase 1 |
| `LoadBalancer` | type, scheme, dnsName, state | Phase 1 |
| `EcsCluster` | status, runningTaskCount | Phase 1 |
| `EcsService` | desiredCount, runningCount, launchType, taskDefinition | Phase 1 |
| `S3Bucket` | creationDate, versioningEnabled, encryptionType, publicAccessBlocked | Phase 1 |
| `LambdaFunction` | runtime, handler, memoryMb, timeoutSeconds, lastModified, codeSize | Phase 1 |
| `Route53Zone` | hostedZoneId, domainName, isPrivate, recordCount | Phase 1 |

### Phase 2 추가 노드 (4종)

| 레이블 | 프로퍼티 | 용도 |
|---|---|---|
| `CidrSource` | cidr, label, isPublic, riskLevel | SG 규칙의 외부 CIDR 표현 |
| `IamRole` | roleName, assumeRolePolicy, attachedPolicies | 서비스 연결 IAM 역할 |
| `EcsTaskDefinition` | family, revision, cpu, memory, networkMode, containers(JSON) | ECS 태스크 상세 |
| `NginxConfig` | instanceId, serverNames, upstreams | nginx 설정 (플러그인) |
| `DbSubnetGroup` | name, subnetGroupStatus, description | RDS 서브넷 그룹 |

---

## 3. 최종 관계(엣지) 타입

### Phase 1 관계 (7종)

| 관계 | 방향 | 프로퍼티 | 의미 |
|---|---|---|---|
| `BELONGS_TO` | Resource→Subnet, Subnet→Vpc, Lambda→Subnet | — | 네트워크 소속 |
| `HAS_SG` | Resource→SecurityGroup | — | 보안 그룹 연결 |
| `ROUTES_TO` | LoadBalancer→Resource | port, protocol | 트래픽 라우팅 |
| `RUNS_IN` | EcsService→EcsCluster | — | ECS 구조 |
| `HAS_RECORD` | Route53Zone→Resource | recordType, recordName | DNS 매핑 |
| `TRIGGERS` | S3Bucket→LambdaFunction | — | 이벤트 트리거 |
| `IN_SUBNET_GROUP` | RdsInstance→DbSubnetGroup | — | RDS 네트워크 |
| `CONTAINS` | DbSubnetGroup→Subnet | — | 서브넷 그룹 구성 |

### Phase 2 추가 관계 (7종)

| 관계 | 방향 | 프로퍼티 | 의미 |
|---|---|---|---|
| `ALLOWS_FROM` | SecurityGroup→SecurityGroup | port, protocol, direction | SG간 접근 허용 |
| `ALLOWS_TO` | CidrSource→SecurityGroup | port, protocol | 외부→SG 접근 |
| `ALLOWS_SELF` | SecurityGroup→SecurityGroup (자기참조) | port, protocol | 자기 참조 규칙 |
| `EGRESS_TO` | SecurityGroup→CidrSource | port, protocol | 아웃바운드 |
| `HAS_ROLE` | Resource→IamRole | — | IAM 역할 연결 |
| `USES_TASK_DEF` | EcsService→EcsTaskDefinition | — | 태스크 정의 참조 |
| `LIKELY_USES` | Compute→RdsInstance | confidence, reason | 추론된 사용 관계 |
| `PROXIES_TO` | NginxConfig→Resource | upstream, port | nginx 라우팅 (플러그인) |
| `RUNS_NGINX` | Ec2Instance→NginxConfig | serverName | nginx 호스팅 (플러그인) |

---

## 4. 제약 조건 및 인덱스

```cypher
-- 제약 조건
CREATE CONSTRAINT unique_arn FOR (n:AwsResource) REQUIRE n.arn IS UNIQUE;
CREATE CONSTRAINT unique_cidr FOR (n:CidrSource) REQUIRE n.cidr IS UNIQUE;

-- 인덱스
CREATE INDEX idx_resource_id FOR (n:AwsResource) ON (n.resourceId);
CREATE INDEX idx_resource_type FOR (n:AwsResource) ON (n.resourceType);
CREATE INDEX idx_environment FOR (n:AwsResource) ON (n.environment);
CREATE INDEX idx_tier FOR (n:AwsResource) ON (n.tier);
CREATE INDEX idx_stale FOR (n:AwsResource) ON (n.stale);
CREATE INDEX idx_vpc_id FOR (n:Vpc) ON (n.resourceId);
CREATE INDEX idx_sg_group_id FOR (n:SecurityGroup) ON (n.groupId);
```

---

## 5. 주요 Cypher 질의 패턴

### "이 RDS를 쓰는 서비스는?" (5대 질문 #2)
```cypher
MATCH (r:RdsInstance {name: $rdsName})
OPTIONAL MATCH (compute)-[:LIKELY_USES]->(r)
OPTIONAL MATCH (compute2)-[:HAS_SG]->(sg)<-[:HAS_SG]-(r)
WHERE compute2 <> r
RETURN DISTINCT coalesce(compute, compute2) AS user,
       compute IS NOT NULL AS confirmed
```

### "prod vs staging 차이" (5대 질문 #3)
```cypher
MATCH (prod:AwsResource {environment: 'prod'})
WITH collect(prod.resourceType) AS prodTypes, count(prod) AS prodCount
MATCH (stg:AwsResource {environment: 'staging'})
WITH prodTypes, prodCount, collect(stg.resourceType) AS stgTypes, count(stg) AS stgCount
RETURN prodCount, stgCount, prodTypes, stgTypes
```

### "0.0.0.0/0 열린 SG" (5대 질문 #4)
```cypher
MATCH (cidr:CidrSource {cidr: '0.0.0.0/0'})-[:ALLOWS_TO]->(sg:SecurityGroup)
MATCH (resource)-[:HAS_SG]->(sg)
RETURN sg.name, resource.name, resource.resourceType
```

### 전체 토폴로지 (React Flow용)
```cypher
MATCH (n:AwsResource)
WHERE n.stale IS NULL OR n.stale = false
OPTIONAL MATCH (n)-[r]->(m:AwsResource)
RETURN n, r, m
```
