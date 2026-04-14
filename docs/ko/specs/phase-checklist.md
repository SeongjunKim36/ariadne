# Phase별 체크리스트 — 진행도 추적

> 각 Phase 진행 시 이 문서를 업데이트한다.
> `[x]` 체크 + 완료일 기록. 전체 진행률을 한눈에 볼 수 있다.

---

## Phase 0: 논제 고정 + 환경 준비 (1주)

**목표**: 개발 환경 세팅, `docker compose up`으로 전체 스택 기동

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 0-1 | 기획서 완성 | [x] | 2026-04-13 | project-a-infra-mapper.md |
| 0-2 | 상세 기획서 완성 | [x] | 2026-04-13 | specs/ 디렉토리 |
| 0-3 | GitHub 저장소 생성 + 라이센스(MIT) | [x] | 2026-04-13 | LICENSE 파일 생성 완료, GitHub push 대기 |
| 0-4 | Spring Boot 프로젝트 스캐폴딩 | [x] | 2026-04-13 | Java 17, Gradle 8.12, Spring Boot 3.4.4 |
| 0-5 | build.gradle.kts 의존성 설정 | [x] | 2026-04-13 | SDN, AWS SDK v2, Lombok, Testcontainers |
| 0-6 | docker-compose.yml 작성 (Neo4j + PostgreSQL) | [x] | 2026-04-13 | PostgreSQL 포트: 5436 (충돌 회피) |
| 0-7 | docker-compose.dev.yml 작성 (LocalStack 포함) | [x] | 2026-04-13 | |
| 0-8 | application.yml 기본 설정 | [x] | 2026-04-13 | default + docker + dev + test 프로필 |
| 0-9 | AWS 읽기 전용 IAM 정책 파일 | [x] | 2026-04-13 | iam/ariadne-readonly-policy.json |
| 0-10 | LocalStack 테스트 환경 구축 확인 | [x] | 2026-04-13 | Testcontainers LocalStack + `AwsConfigLocalStackTest` 검증 완료 |
| 0-11 | `docker compose up` + Spring Boot 기동 확인 | [x] | 2026-04-13 | **Phase 0 종료 조건 충족** — Neo4j(healthy) + PostgreSQL(healthy) + Spring Boot(2.8s 기동) |
| 0-12 | React 프로젝트 초기화 (Vite + TypeScript + Tailwind) | [x] | 2026-04-13 | React Flow, Zustand, SWR 등 의존성 포함 |
| 0-13 | .gitignore, README 초안 작성 | [x] | 2026-04-13 | .gitignore 완료, README는 Phase 5에서 완성 |

**Phase 0 진행률**: 13/13 (100%) — 종료

---

## Phase 1: AWS 수집기 MVP (5-6주)

**목표**: 핵심 리소스 9종 수집 + Neo4j 저장 + React Flow 시각화

### Week 1: 스캐폴딩 + 첫 수집기

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 1-1 | AwsResource 추상 노드 엔티티 (@CompositeProperty tags) | [x] | 2026-04-13 | graph/node/AwsResource 추가 |
| 1-2 | AwsCollectContext, CollectResult 모델 | [x] | 2026-04-13 | collector 공통 모델 추가 |
| 1-3 | ResourceCollector 인터페이스 | [x] | 2026-04-13 | ResourceCollector + BaseCollector 추가 |
| 1-4 | BaseCollector 추상 클래스 (재시도 + 쓰로틀링) | [x] | 2026-04-13 | throttle retry 기본 구현 |
| 1-5 | VpcCollector 구현 (Paginator 사용) | [x] | 2026-04-13 | LocalStack 통합 테스트로 검증 |
| 1-6 | SubnetCollector 구현 + BELONGS_TO 관계 | [x] | 2026-04-13 | LocalStack 통합 테스트로 검증 |
| 1-7 | Neo4j UNIQUE CONSTRAINT + INDEX 생성 스크립트 | [x] | 2026-04-13 | `backend/src/main/resources/neo4j/phase-1-schema.cypher` |
| 1-8 | LocalStack에 VPC/Subnet 생성 → 수집 → Neo4j Browser 확인 | [x] | 2026-04-14 | `ariadne-local-vpc` / `ariadne-local-subnet-a` 수집 + Neo4j `BELONGS_TO` 확인 (`subnet-1b70fef0 -> vpc-debba5fc`), LocalStack 미구현 서비스는 warning 처리 |

### Week 2: 핵심 수집기

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 1-9 | SecurityGroupCollector 구현 | [x] | 2026-04-13 | VPC 소속 관계 포함 |
| 1-10 | Ec2Collector 구현 + BELONGS_TO, HAS_SG 관계 | [x] | 2026-04-13 | LocalStack happy-path로 검증 |
| 1-11 | RdsCollector 구현 + DbSubnetGroup + IN_SUBNET_GROUP | [x] | 2026-04-13 | RDS 태그 수집 + HAS_SG/CONTAINS 포함, LocalStack happy-path는 Docker 환경 복구 후 재검증 |
| 1-12 | 삭제된 리소스 stale 마킹 로직 | [x] | 2026-04-14 | 성공한 수집기 타입 기준 stale 처리 + stale 노드 관계 정리 |
| 1-13 | 수집기 단위 테스트 (모킹) | [x] | 2026-04-13 | SecurityGroupCollectorTest, Ec2CollectorTest |

### Week 3: ALB + 오케스트레이터 + API

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 1-14 | AlbCollector 구현 + ROUTES_TO, HAS_SG 관계 | [x] | 2026-04-14 | ALB → EC2/Lambda 라우팅 + VPC/HAS_SG 포함 |
| 1-15 | CollectorOrchestrator (병렬 수집 + 부분 실패 처리) | [x] | 2026-04-14 | 병렬 수집 + warningMessage 노출 |
| 1-16 | ScanRun JPA 엔티티 (PostgreSQL) | [x] | 2026-04-13 | ScanRun + Repository + Service 추가 |
| 1-17 | POST /api/scan (202 Accepted + scanId) | [x] | 2026-04-13 | ScanController 구현 |
| 1-18 | GET /api/scan/{scanId}/status | [x] | 2026-04-13 | ScanController 구현 |
| 1-19 | GET /api/graph (React Flow 형식) | [x] | 2026-04-13 | GraphController + GraphQueryService 구현 |
| 1-20 | GET /api/resources?arn= + GET /api/resources?resourceId= | [x] | 2026-04-14 | ResourceController + ResourceQueryService 추가 |
| 1-21 | 최소 happy-path 통합 테스트 (Testcontainers + LocalStack) | [x] | 2026-04-13 | VPC/Subnet/SG/EC2 LocalStack 스캔 검증 |

### Week 4: 프론��엔드

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 1-22 | React Flow 기본 뷰 (TopologyCanvas) | [x] | 2026-04-14 | `/api/graph` 연동된 실제 topology 화면 구현 |
| 1-23 | 커스텀 노드: Ec2Node, RdsNode, VpcGroupNode, SubnetGroupNode, SgNode, AlbNode | [x] | 2026-04-14 | topologyNodes.tsx 추가 |
| 1-24 | dagre 레이아웃 적용 (compound graph) | [x] | 2026-04-14 | 그룹 내부 leaf 배치에 dagre 적용 |
| 1-25 | FilterPanel (리소스 타입 + 환경 + VPC) | [x] | 2026-04-14 | 필터 + 초기화 액션 포함 |
| 1-26 | DetailPanel (노드 클릭 → 상세 정보) | [x] | 2026-04-14 | `/api/resources` 기반 연결 정보 표시 |
| 1-27 | 검색 기능 (이름/ID) | [x] | 2026-04-14 | name/resourceId/tag 검색 |
| 1-28 | MiniMap | [x] | 2026-04-14 | React Flow MiniMap 연동 |
| 1-29 | 노드 더블클릭 → 연결 하이라이트 (역참조) | [x] | 2026-04-14 | focused resource 주변 관계 강조 |

### Week 5: 확장 수집기

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 1-30 | EcsCollector (Cluster + Service) + RUNS_IN | [x] | 2026-04-14 | ECS cluster/service + ALB target group 연동 |
| 1-31 | S3Collector + TRIGGERS 관계 | [x] | 2026-04-14 | S3 bucket 속성 + Lambda notification 수집 |
| 1-32 | LambdaCollector + BELONGS_TO(Subnet), HAS_SG | [x] | 2026-04-14 | VPC Lambda subnet/sg 관계 반영 |
| 1-33 | Route53Collector (region="global") + HAS_RECORD | [x] | 2026-04-14 | ALB/EC2/RDS DNS 매핑 지원 |
| 1-34 | 프론트엔드 신규 노드: EcsClusterGroupNode, EcsServiceNode, S3Node, LambdaNode, Route53Node | [x] | 2026-04-14 | React Flow 신규 타입/컬러/상세 정보 확장 |
| 1-35 | 그래프 스키마 안정화 (실 데이터 기반 조정) | [x] | 2026-04-14 | LB/Route53 인덱스 + 타입 매핑 보강 |

### Week 6: 통합 + 검증

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 1-36 | dongne-v2 실계정 연동 테스트 | [x] | 2026-04-14 | `Ariadne_ReadOnly` IAM 사용자로 실계정 스캔 성공 (22 nodes / 21 edges, 환경 태그 부재로 `unknown`) |
| 1-37 | 전체 수집 → React Flow 시각화 E2E 확인 | [x] | 2026-04-14 | Playwright로 scan trigger → 상태 전이 → topology/detail 흐름 검증 |
| 1-38 | LIKELY_USES 추론 관계 생성 (같은 SG + DB 포트) | [x] | 2026-04-14 | GraphInferenceService 추가 + 스캔 후 추론 관계 재생성 |
| 1-39 | "prod 환경에 뭐가 돌아가고 있어?" 답변 가능 확인 | [x] | 2026-04-14 | GraphQueryService 통합 테스트로 prod VPC 토폴로지 검증 |
| 1-40 | "이 RDS를 쓰는 애들이 누구야?" 답변 가능 확인 | [x] | 2026-04-14 | ResourceQueryService 통합 테스트로 LIKELY_USES 역참조 검증 |
| 1-41 | 버그 수정 + UI 다듬기 | [x] | 2026-04-14 | scan preflight API, 친절한 AWS 오류 메시지, scan status banner 추가 |

**Phase 1 진행률**: 41/41 (100%) — 종료

---

## Phase 2: 상세 설정 수집 + 보안 하드닝 (3-4주)

### Week 1: SG 상세 + ECS 태스크 정의

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 2-1 | SG 인바운드 규칙 파싱 → ALLOWS_FROM/ALLOWS_TO 엣지 | [x] | 2026-04-14 | IPv4/IPv6 CIDR + SG reference를 규칙 엣지로 집계 저장 |
| 2-2 | SG 아웃바운드 규칙 → EGRESS_TO 엣지 | [x] | 2026-04-14 | CIDR/SG 대상 아웃바운드 규칙을 `EGRESS_TO`로 저장 |
| 2-3 | Managed Prefix List 처리 | [x] | 2026-04-14 | `describeManagedPrefixLists` + `getManagedPrefixListEntries`로 prefix list CIDR 확장 |
| 2-4 | Self-reference SG 처리 → ALLOWS_SELF | [x] | 2026-04-14 | 인바운드/아웃바운드 self rule을 `ALLOWS_SELF`로 저장 |
| 2-5 | CidrSource 가상 노드 생성 (0.0.0.0/0 → riskLevel:HIGH) | [x] | 2026-04-14 | `CIDR_SOURCE` 노드 + `Public Internet`/`riskLevel` 매핑 |
| 2-6 | EcsTaskDefinition 수집기 + USES_TASK_DEF 관계 | [x] | 2026-04-14 | task definition 상세 수집 + 환경변수 기본 리댁션 + DetailPanel 연결 표시 |
| 2-7 | 리댁션 엔진 기본 구현 (키 패턴 + 값 패턴) | [x] | 2026-04-14 | `RedactionEngine` 추가 + JDBC password 부분 리댁션 + ECS env var 공통 엔진 적용 |
| 2-8 | 리댁션 확장: secrets, repositoryCredentials, logConfiguration | [x] | 2026-04-14 | ECS container definition 전체 리댁션 적용, secret/repo credential 제거 + log option 마스킹 |
| 2-9 | React Flow: CidrSourceNode + SG 규칙 엣지 시각화 | [x] | 2026-04-14 | `cidr` 노드 타입, rule edge 스타일, VPC 필터 연결 컨텍스트 반영 |

### Week 2: IAM + nginx 플러그인

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 2-10 | IAM Role 수집기 (서비스 연결 역할) + HAS_ROLE 관계 | [ ] | | |
| 2-11 | nginx 플러그인 구조 (enabled: false 기본) | [ ] | | |
| 2-12 | SSM RunCommand → nginx config 수집 (find + cat) | [ ] | | |
| 2-13 | nginx config 파싱 → upstream/server_name/proxy_pass | [ ] | | |
| 2-14 | RUNS_NGINX, PROXIES_TO 관계 생성 | [ ] | | |
| 2-15 | 플러그인 활성화 시 추가 IAM 안내 | [ ] | | |

### Week 3: 보안 하드닝

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 2-16 | LlmGateway 강제 인터셉터 (sanitize 우회 차단) | [ ] | | |
| 2-17 | LlmDataSanitizer (strict/normal/verbose) | [ ] | | |
| 2-18 | field allowlist 적용 | [ ] | | |
| 2-19 | fail-closed 동작 (레벨 미설정 시 STRICT) | [ ] | | |
| 2-20 | 감사 로그 테이블 (llm_audit_log) + API | [ ] | | |
| 2-21 | 통합 테스트: 리댁션 정확성 | [ ] | | |
| 2-22 | 통합 테스트: 전송 레벨별 데이터 검증 | [ ] | | |

### Week 4 (버퍼): 안정화

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 2-23 | dongne-v2 실계정 상세 수집 테스트 | [ ] | | |
| 2-24 | SG 시각화 UX 개선 | [ ] | | |
| 2-25 | 리댁션 엔진 엣지 케이스 처리 | [ ] | | |

**Phase 2 진행률**: 9/25 (36%)

---

## Phase 3: AI 의미층 (3-4주)

### Week 1: 규칙 기반 감사 엔진

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 3-1 | AuditRule 인터페이스 | [ ] | | |
| 3-2 | SG 규칙 8개 구현 (SG-001~008) | [ ] | | |
| 3-3 | IAM 규칙 7개 구현 (IAM-001~007) | [ ] | | |
| 3-4 | Network 규칙 4개 구현 (NET-001~004) | [ ] | | |
| 3-5 | S3 규칙 3개 + Encryption 규칙 3개 (S3-001~003, ENC-001~003) | [ ] | | |
| 3-6 | AuditEngine — 전체 감사 실행 | [ ] | | |
| 3-7 | 감사 API (run, latest, findings, rules) | [ ] | | |
| 3-8 | 감사 대���보드 UI (카드 + 카테고리 탭 + finding 목록) | [ ] | | |
| 3-9 | finding 클릭 → 토폴로지 하이라이트 연동 | [ ] | | |

### Week 2: NL Query 기반

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 3-10 | ClaudeClient Spring 서비스 | [ ] | | |
| 3-11 | LlmGateway + Circuit Breaker + 일일 비용 추적 | [ ] | | |
| 3-12 | SchemaContextBuilder (Neo4j 실 스키마 추출) | [ ] | | |
| 3-13 | CypherValidator (레이블+관계+프로퍼티 화이트리스트 + EXPLAIN) | [ ] | | |
| 3-14 | NlQueryService (생성→검증→실행 파이프라인) | [ ] | | |
| 3-15 | 빈 결과 / 모호한 질의 / 대량 결�� 처리 | [ ] | | |
| 3-16 | TierLabeler 규칙 기반 레이블링 | [ ] | | |

### Week 3: LLM 완성

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 3-17 | NL Query UI (검색 바 + 예시 + 결과 + 서브그래프) | [ ] | | |
| 3-18 | LLM 레이블링 (미분류 보조 + confidence 기반) | [ ] | | |
| 3-19 | LlmAuditAssistant (감사 결과 설명 + 우선순위) | [ ] | | |
| 3-20 | 아키텍처 요약 생성기 (한국어/영어) | [ ] | | |
| 3-21 | 계층 레이블 시각화 (TierBadge + 필터) | [ ] | | |
| 3-22 | LLM fallback (실패 시 규칙/템플릿 응답) | [ ] | | |

### Week 4 (버퍼): 통합 + 안정화

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 3-23 | dongne-v2 실데이터 NL Query 정확도 테스트 | [ ] | | |
| 3-24 | 프롬프트 튜닝 (실 질의 패턴 기반) | [ ] | | |
| 3-25 | "prod vs staging 차이가 뭐야?" 답변 가능 확인 | [ ] | | 5대 질문 #3 |
| 3-26 | "0.0.0.0/0으로 열린 SG 중 위험한 거?" 답변 가능 확인 | [ ] | | 5대 질문 #4 |

**Phase 3 진행률**: 0/26 (0%)

---

## Phase 4: 드리프트 & 타임라인 (3-4주)

### Week 1: 스냅샷 시스템

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 4-1 | Snapshot JPA 엔티티 (JSONB) | [ ] | | |
| 4-2 | SnapshotDiff JPA 엔티티 (modifiedEdges 포함) | [ ] | | |
| 4-3 | SnapshotService — 그래프 캡처 + 저장 | [ ] | | |
| 4-4 | DiffCalculator — 노드 + 엣지 diff (추가/삭제/수정) | [ ] | | |
| 4-5 | @Scheduled 매시간 스냅샷 | [ ] | | |
| 4-6 | 스냅샷 API (목록, 상세, diff, latest) | [ ] | | |

### Week 2: 타임라인 UI + Terraform 드리프트

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 4-7 | TimelineBar 컴포넌트 (스냅샷 점 + 변경 마커) | [ ] | | |
| 4-8 | PeriodSelector (24h / 7d / 30d / custom) | [ ] | | |
| 4-9 | DiffSummary + ChangeList UI | [ ] | | |
| 4-10 | diff 토폴로지 하이라이트 (added/removed/modified) | [ ] | | |
| 4-11 | 스냅샷 보관 정책 + diff 보관 정책 | [ ] | | |
| 4-12 | 보관 정리 스케줄러 (매일 새벽 2시) | [ ] | | |
| 4-13 | TfStateParser (ARN 추출 + 속성 매핑) | [ ] | | |
| 4-14 | TerraformDriftDetector (state vs 실제 비교) | [ ] | | |
| 4-15 | 드리프트 API + DriftPage UI | [ ] | | |

### Week 3: EventBridge + 알림

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 4-16 | EventBridge 리스너 (SQS 기반) | [ ] | | 선택적 |
| 4-17 | EventResourceMapper (이벤트→리소스 매핑) | [ ] | | |
| 4-18 | 부분 재수집 로직 (단일 리소스) | [ ] | | |
| 4-19 | 알림 규칙 엔진 | [ ] | | |
| 4-20 | Slack 알림 발송기 | [ ] | | |
| 4-21 | 이벤트 로그 API + UI | [ ] | | |

### Week 4 (버퍼): ��합 + 안정화

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 4-22 | dongne-v2 24시간 스냅샷 누적 테스트 | [ ] | | |
| 4-23 | diff 정확성 검증 (의도적 변경 → diff 확인) | [ ] | | |
| 4-24 | Terraform state 연동 E2E 테스트 | [ ] | | |
| 4-25 | "어제 대비 오늘 ���가 바뀌었어?" 답변 가능 확인 | [ ] | | 5대 질문 #5 |

**Phase 4 진행률**: 0/25 (0%)

---

## Phase 5: dongne-v2 적용 + 포트폴리오 (2-3주)

| # | 항목 | 상태 | 완료일 | 비고 |
|---|---|---|---|---|
| 5-1 | dongne-v2 실 AWS 계정 전체 연동 | [ ] | | |
| 5-2 | 대규모 성능 테스트 (리소스 수백 개) | [ ] | | |
| 5-3 | React Flow 렌더링 최적화 (가상화/클러스터링) | [ ] | | 필요 시 |
| 5-4 | Neo4j 쿼리 최적화 (EXPLAIN PROFILE) | [ ] | | 필요 시 |
| 5-5 | 영어 README 작성 (설치 가이드, 스크린샷) | [ ] | | |
| 5-6 | 데모 GIF / 영상 녹화 | [ ] | | |
| 5-7 | "왜 Cartography/Backstage/Datadog이 아닌가" 섹션 | [ ] | | README 또는 블로그 |
| 5-8 | 케이스 스터디 블로그 초안 | [ ] | | "AWS 계정에 돌렸더니" |
| 5-9 | 오픈소스 공개 (GitHub public) | [ ] | | |
| 5-10 | Portfolio 리포 업데이트 | [ ] | | |

**Phase 5 진행률**: 0/10 (0%)

---

## 전체 진행률

| Phase | 항목 수 | 완료 | 진행률 |
|---|---|---|---|
| 0: 환경 준비 | 13 | 2 | 15% |
| 1: 수집기 MVP | 41 | 0 | 0% |
| 2: 상세 수집 + 보안 | 25 | 0 | 0% |
| 3: AI 의미층 | 26 | 0 | 0% |
| 4: 드리프트 | 25 | 0 | 0% |
| 5: 포트폴리오 | 10 | 0 | 0% |
| **전체** | **140** | **2** | **1%** |
