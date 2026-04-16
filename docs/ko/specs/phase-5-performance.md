# Phase 5 성능 및 릴리즈 레디니스 메모

> 작성일: 2026-04-16

## 1. 목표

Phase 5에서 확인하려던 핵심은 세 가지였다.

- 실제 AWS 계정 기준으로 scan → snapshot → audit → timeline 흐름이 모두 이어지는지
- Topology 전체 조회가 수백 개 리소스 규모에서도 무너지지 않는지
- 공개 저장소 기준으로 README, 스크린샷, 데모 자산이 갖춰졌는지

## 2. 적용한 최적화

### 그래프 조회 경로

`GraphQueryService`의 기본 조회 경로를 다음 순서로 변경했다.

1. 활성(`stale = false`) + detail-only 제외 + 환경/타입/tier 조건으로 **기본 노드 집합**을 먼저 조회
2. 부모 관계(`BELONGS_TO`, `IN_SUBNET_GROUP`, `RUNS_IN`)만 따로 조회해 **부모 계층을 확장**
3. 최종 가시 노드 집합 내부의 엣지만 다시 조회

기존 방식은 모든 노드/엣지를 한 번에 가져온 뒤 Java에서 필터링했기 때문에, 리소스 수가 늘수록 네트워크 비용과 메모리 비용이 같이 커졌다.

### Neo4j 인덱스

다음 인덱스를 명시적으로 추가했다.

- `idx_stale` on `AwsResource(stale)`
- `idx_tier` on `AwsResource(tier)`

기존 인덱스(`resourceId`, `resourceType`, `environment`)와 함께 활성 리소스 및 tier 필터를 더 빠르게 줄일 수 있게 했다.

또한 과거 데이터 호환을 위해 `stale IS NULL` 노드에는 초기화 마이그레이션을 한 번 수행한다.

## 3. 대규모 회귀 테스트

`GraphQueryServicePerformanceIntegrationTest`에서 아래 구성으로 그래프를 시드했다.

- VPC 1개
- Subnet 24개
- EC2 432개
- 총 노드 457개
- 총 엣지 456개

측정 방식:

- 한 번 워밍업 후 `fetchGraph(null, Set.of(), null, null)`을 다시 실행
- Testcontainers 기반 Neo4j/PostgreSQL 환경에서 통합 테스트로 측정

실제 측정 결과:

- `457 nodes / 456 edges / 97ms`

안전 가드:

- 테스트에서는 `2.5s` 이내를 예산으로 두고 회귀를 감시한다
- 이 값은 CI/로컬 컨테이너 편차를 고려한 넉넉한 budget이고, 실제 실행 수치는 훨씬 작게 나왔다

## 4. 실계정 검증 메모

`AWS_PROFILE=ariadne` 기준 재검증 결과:

- preflight: `ready = true`
- 실스캔: `24 nodes / 45 edges`
- snapshot: 2개 누적 확인
- latest diff: 0 changes
- audit: `62 findings (HIGH 27 / MEDIUM 14 / LOW 21)`

즉 Phase 4에서 만든 snapshot/timeline/drift 흐름이 Phase 5 실계정 기준에서도 이어진다.

## 5. 데모 자산

공개용 데모 GIF를 추가했다.

- `docs/assets/demo/ariadne-demo.gif`

이 GIF는 실제 앱 스크린샷을 바탕으로 Topology → Audit → Timeline 흐름을 짧게 보여주는 공개용 요약 자산이다.

## 6. 남겨둔 메모

- `vpc` 필터가 있는 경로는 정확성 우선으로 기존 BFS 기반 fallback을 유지했다
- React Flow 개발 경고(`nodeTypes/edgeTypes object`)는 기능 문제는 아니지만 별도 UI polish 항목으로 다시 볼 수 있다
- 수천 개 이상의 리소스에 대한 더 공격적인 클러스터링/서버사이드 필터 연동은 차기 최적화 후보로 남긴다
