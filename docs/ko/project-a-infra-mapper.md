# Ariadne — AWS 인프라 맵퍼 기획서

> 상태: Phase 3 완료 (감사 엔진 + NL Query + Tier 레이블링 + 감사 설명 + 아키텍처 요약 완료) · 최초 작성: 2026-04-10 · **타당성 검증 완료: 2026-04-13** · 관련 문서: [Theseus - 코드 토폴로지 맵퍼](./project-b-code-mapper.md)

## 이름의 유래

그리스 신화에서 아리아드네(Ariadne)는 미궁에 들어간 테세우스에게 빠져나올 수 있는 실타래를 쥐어준 인물이다. 이 프로젝트는 거대한 AWS 미궁에서 길을 찾게 해주는 "실"의 역할을 한다. 자매 프로젝트 [Theseus](./project-b-code-mapper.md)는 그 실을 따라 코드의 내부를 탐험한다.

## 한 줄 요약

AWS 계정에 포인트하면 EC2/RDS/ECS/VPC/SG/nginx 같은 리소스를 자동으로 스캔해 **라이브 토폴로지 그래프**를 만들고, **자연어로 질의**할 수 있는 오픈소스 인프라 지도.

## 왜 이걸 만드나 (문제 정의)

- 지금 prod에 뭐가 돌고 있는지 한눈에 보여주는 **신뢰 가능한** 지도가 없다
- 손으로 그린 아키텍처 다이어그램은 그리는 순간부터 실제와 어긋나기 시작한다 (drift)
- AWS 콘솔은 리소스 리스트는 주지만, 리소스 **간의 관계**는 그래프로 보여주지 않는다
- 변경이 생겨도 "누가 언제 뭘 바꿨나" 추적이 어렵다
- 신입이 들어왔을 때 "우리 인프라는 이렇게 생겼어" 할 교재가 없다
- 보안 감사(열려있는 SG, 과한 IAM)를 수동으로 해야 한다

## 타겟 사용자 & 유스케이스

- **백엔드/플랫폼 엔지니어**: 프로덕션 장애 시 영향 범위 파악
- **신입/온보딩**: 시스템 이해 교재
- **보안 담당**: 주기적 감사 자동화
- **SRE/DevOps**: 드리프트 감지 + 변경 추적

## 성공 지표 (이 툴이 답해야 하는 5개 질문)

이 5개에 답할 수 있으면 Phase 4까지 핵심 가치가 완성된 것으로 간주한다.

1. **"prod 환경에 뭐가 돌아가고 있어?"** → 전체 토폴로지 한 장
2. **"이 RDS(`dongne-prod-db`)를 쓰는 애들이 누구야?"** → 역참조 추적
3. **"prod vs staging 차이가 뭐야?"** → 환경 diff
4. **"0.0.0.0/0으로 열린 SG 중 위험한 거 있어?"** → 자동 보안 감사
5. **"어제 대비 오늘 뭐가 바뀌었어?"** → 드리프트 타임라인

## 비범위 (Non-goals)

스코프 크립을 막기 위해 **하지 않을 것**을 명시적으로 박아둔다.

- **Terraform 대체 아님** — 쓰기/프로비저닝 도구가 아니라 읽기/시각화 도구
- **모니터링 도구 아님** — 메트릭/알러트는 Datadog/CloudWatch의 영역
- **비용 최적화 도구 아님** — 비용 오버레이는 있다면 선택적 부가 기능
- **멀티 클라우드 아님 (최소 1년간)** — AWS만. GCP/Azure는 나중
- **실시간 스트리밍 아님** — 주기 스냅샷 + EventBridge 푸시로 충분
- **모든 AWS 서비스 커버 아님** — 핵심 리소스만 (EC2/RDS/ECS/VPC/SG/ALB/S3/Lambda/Route53)

## 기존 툴 대비 차별점

| 툴 | 한계 | 이 프로젝트의 차이 |
|---|---|---|
| AWS Resource Explorer | 리스트만, 관계 그래프 없음 | 그래프 + 의미 층 |
| Cloudcraft | 수동 정리, 유료, 정적 | 자동 + 라이브 + 오픈소스 |
| CloudMapper (Duo) | 오래됨, 보안에만 특화 | 현대적 스택 + LLM 통합 |
| Steampipe | SQL을 직접 써야 함 | 자연어 질의 |
| Terraform graph | IaC만 봄 (실제 상태 X) | 실제 AWS 상태 기반 |
| Datadog Infra Map | 비싸고 닫힘, 코드 연결 약함 | 오픈소스 + 코드와 연결 가능 |
| **Cartography (Lyft)** | **Neo4j 기반으로 가장 유사. 그러나 시각화 UI 없음 (Neo4j Browser 의존), 자연어 질의 없음 (Cypher 직접 작성), 드리프트 타임라인 없음** | **운영자 친화 UI + NL 질의 + 드리프트 타임라인 + 코드 연결(Theseus)** |
| AWS Config + Advanced Query | AWS 네이티브, 관계 그래프 약함, 시각화 없음 | 그래프 기반 관계 추적 + 커스텀 시각화 |
| Prowler | 보안 감사 특화, 토폴로지 시각화 없음 | 보안 감사 + 토폴로지 + NL 질의 통합 |

**핵심 차별점**: 라이브 AWS 상태 + LLM 자연어 질의 + 드리프트 타임라인 + 운영자 친화 UI를 오픈소스로 묶은 조합. 개별 기능은 기존 도구에 존재하지만 **이 조합을 하나의 UX로 제공하는 오픈소스는 없다.**

> **Cartography와의 명확한 선 긋기**: Cartography는 수집+그래프 저장에 강하지만 "수집 후 사용자 경험"이 없다. Ariadne는 수집은 Cartography와 동일 수준을 목표로 하되, **그 위에 얹는 운영자 워크플로우(5대 질문 즉답, 시각화, 드리프트)**가 본질이다. 장기적으로 Cartography를 수집 백엔드로 플러그인하는 것도 고려할 수 있다.

## 기술 스택 (확정)

- **백엔드**: Spring Boot 3.x + Java 21 (확정 — 주력 스택 활용, Spring Data Neo4j 생산성, 포트폴리오 일관성)
- **그래프 저장소**: Neo4j Community Edition (CE)
  - ⚠️ CE는 클러스터링 불가 — 단일 노드 운영 전제. 규모 확장 시 Enterprise 또는 AuraDB 검토
- **메타데이터/스냅샷 히스토리**: PostgreSQL
  - v1에서는 Neo4j에 최신 상태만, PostgreSQL에 스냅샷 히스토리를 저장하는 역할 분리
  - 만약 v1 복잡도가 과하면 Neo4j 단일로 시작 후 히스토리 기능 시 PostgreSQL 도입 검토
- **프론트**: React + React Flow + Tailwind
  - 대규모 계정 대응: 클러스터링/접기/레이어 전환 UI 필수 (Phase 1에서 기초 설계)
- **AI**: Claude API (claude-sonnet-4-6 기본, 복잡한 추론은 opus)
  - ⚠️ LLM에 인프라 데이터가 전송됨 — 전송 경계 정책 필요 (리스크 섹션 참조)
- **수집기**: AWS SDK (주기 스냅샷 — 매시간 기본)
  - EventBridge 구독은 Phase 4에서 **선택적 부가 기능**으로 도입 (실시간 아님, 준실시간 보강)
  - 기본 모델은 "주기 스냅샷 + diff"이며, EventBridge는 스냅샷 사이 중요 변경 보강용
- **테스트 환경**: LocalStack (AWS 서비스 로컬 에뮬레이션) + 실제 AWS 계정(dongne-v2) 검증
- **배포**: Docker Compose (단일 노드) → 여유되면 K8s
- **외부 참조 (코드 의존 아님)**:
  - [Cartography](https://github.com/cartography-cncf/cartography) — Neo4j 스키마 설계 참조 (노드 레이블, 관계 네이밍, 인덱싱 전략)
  - [Prowler](https://github.com/prowler-cloud/prowler) — 보안 감사 규칙의 메타데이터(제목/심각도/설명/조치문) 참조 (Apache 2.0)

## 개발 원칙

코드 전반에 걸쳐 반드시 지키는 원칙. 코드 리뷰 시 이 목록을 체크리스트로 사용한다.

### 설계 원칙

1. **이기적인 담당자 원칙 (Selfish Owner)** — 각 모듈/클래스는 자기 일만 알고, 남의 일은 모른다. 자기 일만 하고 다음에 넘긴다.
   - 예: `Ec2Collector`는 EC2 수집만 한다. Neo4j 저장은 `CollectorOrchestrator`의 일.
   - 예: `RedactionEngine`은 리댁션만 한다. 어떤 맥락에서 호출되는지 모른다.

2. **추상화를 통한 역할 분리** — 인터페이스로 계약하고, 구현은 교체 가능하게.
   - `ResourceCollector<T>` 인터페이스 → 리소스별 구현체는 독립적으로 추가/제거 가능
   - `AuditRule` 인터페이스 → 새 규칙을 빈으로 등록하면 자동으로 감사 엔진에 포함

3. **느슨한 결합** — 컴포넌트와 엔티티 간 직접 의존 최소화.
   - 수집기 ↔ 그래프 저장 ↔ API 계층은 서로를 인터페이스로만 안다.
   - 이벤트/콜백 기반 연동 선호 (e.g. 스캔 완료 → 스냅샷 캡처는 이벤트로 트리거)

4. **설계적으로 풀어내기** — 문제를 빠른 핫픽스가 아닌 구조로 해결한다.
   - 예: LLM 환각 문제 → if문으로 특정 패턴 차단이 아닌, CypherValidator + EXPLAIN + 스키마 화이트리스트 구조로 해결

5. **불필요한 레이어 추가 금지** — 레이어가 가치를 만들지 않으면 만들지 않는다.
   - DTO ↔ Entity 변환이 1:1이면 DTO를 만들지 않는다.
   - Service가 Repository를 그대로 위임만 하면 Controller에서 직접 호출한다.

### 코딩 규칙

6. **외부 엔티티의 Repository 직접 의존 금지** — 다른 도메인의 데이터가 필요하면 해당 도메인의 Service를 통해 접근한다.
   - 예: `AuditEngine`이 SG 데이터 필요 → `SecurityGroupRepository` 직접 주입 X → `GraphQueryService`를 통해 Cypher 질의

7. **N+1 문제 유발 금지** — 루프 안에서 DB 호출하지 않는다.
   - 수집기: 리소스 목록 전체를 한 번에 수집 → 한 번에 저장 (배치)
   - Neo4j: `UNWIND` + `MERGE`로 배치 업서트
   - JPA: `@EntityGraph` 또는 fetch join으로 연관 데이터 한 번에 로딩

8. **Transaction은 필요한 곳에만** — 읽기 전용 조회에 `@Transactional` 걸지 않는다.
   - 쓰기: 스캔 결과 저장, 스냅샷 캡처 시에만 트랜잭션 적용
   - Neo4j 읽기: 트랜잭션 없이 직접 질의 (Spring Data Neo4j 기본 동작)

9. **불필요한 코드 제거** — 주석 처리된 코드, 사용하지 않는 import/변수/메서드를 남기지 않는다.
   - 하위호환성을 위해 죽은 코드를 유지하지 않는다. 필요하면 git에서 복원한다.

10. **불필요한 if문 금지** — 검증(validation) 외의 분기를 최소화한다.
    - 다형성, 전략 패턴, Map 디스패치로 대체한다.
    - 예: 리소스 타입별 처리 → `Map<String, ResourceCollector>` 디스패치, if-else 체인 아님

11. **읽기 쉬운 코드** — 코드가 스스로를 설명한다. 주석은 "왜"에만 쓴다.
    - 메서드명이 동작을 설명: `collectEc2Instances()`, `redactSensitiveValues()`, `validateCypherAgainstSchema()`
    - 매직넘버/매직스트링 금지 → 상수 또는 enum으로

12. **RESTful 엔드포인트** — REST 규약을 따른다.
    - 명사형 리소스 (`/api/resources`, `/api/snapshots`), 동사는 HTTP 메서드로
    - 비동기 작업: `202 Accepted` + 상태 폴링 패턴
    - 일관된 에러 응답 포맷

### 적용 예시 (이 프로젝트에서)

```
[BAD]  AuditEngine에서 Ec2Repository + RdsRepository + SgRepository를 각각 주입받아 직접 조회
[GOOD] AuditEngine은 AuditRule.cypher()로 Neo4j에 직접 Cypher 질의 — 다른 도메인 Repository 모름

[BAD]  CollectorOrchestrator 안에서 for(collector) { collect → save → resolveRelationships } 순차
[GOOD] 1단계: 전체 병렬 수집 → 2단계: 배치 저장 → 3단계: 관계 해소 (단계 분리, 각 단계는 자기 일만)

[BAD]  if (resourceType == "EC2") { ... } else if (resourceType == "RDS") { ... }
[GOOD] collectors.get(resourceType).collect(context)  — Map 디스패치
```

## 로드맵 (예상 5~6개월)

> 기존 3-4개월 → 5-6개월로 조정. 기능 범위는 유지하되, Phase 간 의존성 / 보안 하드닝 / 버퍼를 반영한 현실적 일정.

### Phase 0: 논제 고정 + 환경 준비 (1주)

- 이 문서 완성
- 저장소 생성, 이름 결정, 라이센스 결정 (MIT 권장)
- AWS 읽기 전용 IAM 역할 준비 (describe* + list* 권한만)
- Non-goals 목록 확정
- Spring Boot 프로젝트 스캐폴딩 (Spring Initializr), Docker Compose 기본 구성 (Neo4j + PostgreSQL)
- LocalStack 테스트 환경 구축
- **종료 조건**: `docker compose up`으로 Neo4j + 빈 Spring Boot 서버 뜨면 끝

### Phase 1: AWS 수집기 MVP (5~6주)

> 보틀넥 구간. 리소스 타입마다 파서를 개별 구현해야 하므로 넉넉히 잡는다.

**1차 수집 (3~4주) — 핵심 5종**:
- EC2, RDS, VPC/Subnet, Security Group, ALB/NLB
- 리소스 → Neo4j 노드, 관계(속함/연결/라우팅) → 엣지
- 기본 React Flow 뷰어 + 필터링 + 클러스터링 기초 설계

**2차 수집 (2주) — 확장 4종**:
- ECS/Fargate, S3, Lambda, Route53
- 그래프 스키마 안정화

- **종료 조건**: dongne-v2 AWS 계정 돌려서 "전체 지도 1장" 나오면 끝

### Phase 2: 상세 설정 수집 + 보안 하드닝 (3~4주)

**상세 수집 (2주)**:
- ECS 태스크 정의: 이미지, 환경변수(자동 리댁션), 네트워크 모드
- SG 규칙 → "누가 누구한테 접근 가능한지" 엣지
- IAM 정책 → "누가 뭘 할 수 있는지" (선택적)

**nginx 설정 수집 (선택적 플러그인)**:
- ⚠️ SSM Run Command는 읽기 전용 IAM 범위를 벗어남 — 별도 옵트인 플러그인으로 분리
- 사용자가 SSM 권한을 명시적으로 부여한 경우에만 동작
- SSM Agent 미설치 인스턴스는 graceful skip

**보안 하드닝 (1~2주)**:
- 최소권한 IAM 정책 템플릿 제공 + 문서화
- 민감정보 자동 리댁션 엔진: 정규식 기반 (DB 비밀번호, API 키, 토큰 등)
- LLM 전송 경계 정책 구현:
  - 리소스 ID/이름만 전송 (태그, 환경변수 원문은 전송하지 않음)
  - 사용자 설정으로 전송 범위 조절 가능
  - 로컬 LLM 옵션 안내 (민감 환경용)
- 감사 로깅: 어떤 데이터가 언제 외부로 나갔는지 기록

### Phase 3: AI 의미층 (3~4주) ← 포트폴리오 하이라이트

**규칙 기반 보안 감사 (먼저)**:
- SG 규칙: 0.0.0.0/0 오픈, 불필요한 포트 노출, 과도한 CIDR 범위
- IAM 정책: 와일드카드 권한, 미사용 역할, 교차 계정 접근
- 결과를 구조화된 보고서로 출력 (위험도 High/Medium/Low)

**LLM 의미층 (위에 얹기)**:
- LLM 자동 레이블링: "웹 계층 / 앱 계층 / DB 계층 / 캐시 / 배치"
- 자연어 질의: "결제 관련 인프라 보여줘" → Cypher 생성 → 결과 검증 레이어 → 서브그래프 반환
  - ⚠️ LLM 환각 대응: 생성된 Cypher를 Neo4j 스키마와 대조 검증 후 실행
- LLM 보안 감사 보조: 규칙 기반 엔진 결과를 LLM이 설명/우선순위화 (LLM 단독 판단 아님)
- 자동 아키텍처 요약: 한국어/영어 설명 생성

### Phase 4: 드리프트 & 타임라인 (3~4주)

- 주기 스냅샷 (매시간 기본) → PostgreSQL 히스토리 → 변경 타임라인 뷰
- Terraform state vs 실제 AWS 비교 → IaC 드리프트 감지
- EventBridge 구독 (선택적 부가 기능): 스냅샷 사이 중요 변경 보강
  - ⚠️ "실시간"이 아닌 "준실시간 보강" — 기본 모델은 주기 스냅샷
- CloudTrail 연동 (선택): "누가 언제 이 SG 바꿨나"
- 중요 변경 알림 (Slack/SMS): "prod DB 포트가 퍼블릭에 열림"

### Phase 5: dongne-v2 적용 + 포트폴리오 (2~3주)

- dongne-v2 실제 AWS 계정에 붙이기
- 대규모 계정 성능 테스트: 리소스 수백 개 이상 시 React Flow 렌더링 + Neo4j 쿼리 최적화
- 케이스 스터디 블로그: "AWS 계정에 이 툴 돌렸더니 발견한 것들"
- 오픈소스 공개 + **영어 README** + 데모 GIF/영상
- "왜 Cartography/Backstage/Datadog이 아닌가" 섹션 작성

### 일정 요약

| Phase | 기간 | 누적 |
|---|---|---|
| 0: 논제 고정 + 환경 | 1주 | 1주 |
| 1: 수집기 MVP | 5-6주 | 6-7주 |
| 2: 상세 수집 + 보안 | 3-4주 | 9-11주 |
| 3: AI 의미층 | 3-4주 | 12-15주 |
| 4: 드리프트 | 3-4주 | 15-19주 |
| 5: 적용 + 포트폴리오 | 2-3주 | 17-22주 |
| **버퍼** | **1-2주** | **18-24주 (약 5-6개월)** |

## 리스크와 대응

### 기존 식별 리스크

1. **보안 (최대 리스크)** — AWS 자격증명을 받는 순간 공격 표면이 생긴다
   - 대응: 최소권한 IAM 강제 (describe* + list* only), 감사 로깅, KMS로 토큰 암호화, 쓰기 권한 원천 차단
   - IAM 정책 템플릿을 프로젝트에 포함하여 사용자가 과도한 권한을 부여하지 않도록 가이드
2. **민감정보 노출** — ECS 환경변수 덤프에 비밀번호 들어갈 수 있음
   - 대응: 정규식 기반 자동 리댁션 + 화이트리스트 모드
3. **스코프 폭발 (멀티 계정/리전)** — Phase 1은 **단일 계정 + 단일 리전**으로 고정
4. **API 요율 제한** — describe API 초당 호출 제한 → 증분 스캔 + 지수 백오프

### 신규 식별 블라인드스팟 (타당성 검증에서 발견)

5. **LLM 데이터 외부 전송 리스크** — Claude API로 리소스명, 네트워크 구성, IAM 관계가 외부로 나감
   - 대응: 전송 경계 정책 수립 — 리소스 ID/이름/관계 구조만 전송, 환경변수 원문/태그 값은 전송하지 않음
   - 사용자 설정으로 전송 범위 조절 가능 (strict / normal / verbose)
   - 민감 환경용 로컬 LLM 옵션 안내 (Ollama 등)
   - 감사 로그: 어떤 데이터가 언제 외부로 나갔는지 기록

6. **SSM Run Command와 읽기 전용 IAM의 모순** — nginx 설정 수집에 SSM이 필요하지만 이는 "읽기 전용 관찰기" 범주를 넘어섬
   - 대응: nginx 수집을 별도 옵트인 플러그인으로 분리. 기본 설치에는 포함하지 않음
   - 사용자가 SSM 권한을 명시적으로 부여한 경우에만 동작
   - SSM Agent 미설치 인스턴스는 graceful skip + 경고 로그

7. **LLM 환각 (Cypher 생성 시)** — 존재하지 않는 노드 레이블/관계 타입을 생성할 수 있음
   - 대응: Cypher 검증 레이어 — 생성된 쿼리를 Neo4j 스키마(노드 레이블, 관계 타입, 프로퍼티 목록)와 대조 후 실행
   - 파싱 실패 시 사용자에게 "이 질문을 이해하지 못했습니다" 응답 (오류 쿼리 실행 차단)

8. **업데이트 모델 혼란 (스냅샷 vs 실시간)** — "실시간 스트리밍 아님"과 "EventBridge 실시간 변경"이 모순으로 보일 수 있음
   - 해소: **기본 모델은 주기 스냅샷 (매시간)**. EventBridge는 Phase 4의 선택적 부가 기능으로, 스냅샷 사이 중요 변경(SG 수정, 인스턴스 삭제 등)을 보강하는 용도
   - 사용자에게는 "준실시간 보강(near-realtime supplement)"으로 명시, "실시간 스트리밍"이라 부르지 않음

9. **Neo4j Community Edition 라이선스 제약** — CE는 클러스터링/온라인 백업 불가
   - 대응: v1은 단일 노드 전제이므로 CE로 충분. README에 CE 제약 명시
   - 규모 확장 필요 시 Neo4j AuraDB (매니지드) 또는 Enterprise 검토 가이드 제공

10. **테스트 환경 부재** — 실제 AWS 계정 없이는 수집기 개발/테스트가 어려움
    - 대응: LocalStack으로 핵심 AWS 서비스 에뮬레이션 (EC2, RDS, VPC, SG, ALB, S3)
    - CI에서 LocalStack 기반 통합 테스트 실행
    - 최종 검증은 dongne-v2 실계정에서 수행

11. **대규모 계정 성능** — 리소스 수백~수천 개 계정에서 React Flow 렌더링 + Neo4j 쿼리 성능
    - 대응: React Flow 가상화/클러스터링, Neo4j 인덱싱 전략, Phase 5에서 성능 테스트 포함

## 킬러 시너지 (Project B와 결합 시)

두 프로젝트 모두 Neo4j 기반 그래프이므로 나중에 **하나의 엣지로 연결** 가능:

```
[코드: BillService.createBill]
        ↓
[코드: BillMapper → bill 테이블]
        ↓ (← 여기가 두 프로젝트를 잇는 다리)
[인프라: RDS dongne-prod-db]
        ↓
[인프라: 같은 RDS 쓰는 다른 ECS 태스크 / 백업 S3 / 속한 VPC]
```

그러면 이런 질문이 가능해진다: **"BillService가 쓰는 DB가 죽으면 영향 범위는?"** — 코드부터 하드웨어까지 전 경로를 한 번에 추적.

이 조합을 깔끔하게 해낸 오픈소스는 현재 없음. 포트폴리오의 진짜 무기이자 블로그/발표 소재.

## 미정 사항 (결정 필요)

- [x] 프로젝트 이름: **Ariadne** (2026-04-10 확정)
- [x] 백엔드 언어: **Spring Boot 3.x + Java 21** (2026-04-13 확정 — 주력 스택, Spring Data Neo4j 생산성, 포트폴리오 서사 일관성)
- [x] 경쟁 분석: **Cartography 대비 차별점** 명시 (2026-04-13 추가)
- [x] 일정: **5-6개월**로 현실적 조정 (2026-04-13 — 기능 유지, 기간 확장)
- [ ] 저장소 호스팅 (GitHub 계정)
- [ ] Phase 0 실제 시작일
- [ ] 혼자 할지 vs 동료 끌어들일지
- [ ] Neo4j + PostgreSQL 이중 저장소 vs Neo4j 단일로 v1 시작 여부
- [x] LLM 전송 경계 정책 세부 레벨 (strict/normal/verbose 기본값 결정 — `strict` 기본, 2026-04-15)
- [ ] LocalStack CI 통합 테스트 범위 결정
