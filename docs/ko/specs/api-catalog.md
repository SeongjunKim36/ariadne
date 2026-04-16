# API 카탈로그

> 전체 REST API 엔드포인트를 Phase별로 정리한 문서.
> 프론트엔드 개발 시 참조용.

---

## 공통 사항

- Base URL: `http://localhost:8080`
- Content-Type: `application/json`
- 인증: v1에서는 없음 (단일 사용자 전제). 추후 API Key 또는 Basic Auth 고려.
- 에러 응답: `{ "error": "메시지", "code": "ERROR_CODE" }`

---

## Phase 1 API (수집기 + 그래프 조회)

### 스캔

| Method | Path | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/scan` | 수동 스캔 트리거 | — | `202` `{ scanId, status: "RUNNING", startedAt }` |
| GET | `/api/scan/{scanId}/status` | 스캔 상태 조회 | — | `{ scanId, status, progress: { collected, total, failed[] } }` |
| GET | `/api/scan/latest` | 마지막 스캔 결과 | — | `{ scanId, status, completedAt, totalNodes, totalEdges, durationMs }` |

### 그래프

| Method | Path | 설명 | 쿼리 파라미터 | 응답 |
|---|---|---|---|---|
| GET | `/api/graph` | 전체 그래프 | `env`, `type` (쉼표 구분), `vpc`, `tier` | `{ nodes[], edges[], metadata }` |
| GET | `/api/resources` | 리소스 상세 | `arn` 또는 `resourceId` | `ResourceDetail` |
| GET | `/api/resources/connections` | 연결된 리소스 | `arn` | `ConnectedResource[]` |
| GET | `/api/resources/search` | 리소스 검색 | `q` (이름/ID), `type`, `env` | `ResourceSummary[]` |

### 응답 타입

```typescript
interface GraphResponse {
  nodes: {
    id: string;              // ARN
    type: string;            // "ec2" | "rds" | "vpc-group" | ...
    data: Record<string, any>;
    parentNode?: string;     // 그룹 노드의 ID (VPC/Subnet)
  }[];
  edges: {
    id: string;
    source: string;
    target: string;
    type: string;            // "BELONGS_TO" | "HAS_SG" | ...
    data?: Record<string, any>;
  }[];
  metadata: {
    totalNodes: number;
    totalEdges: number;
    collectedAt: string;
    scanDurationMs: number;
  };
}

interface ResourceDetail {
  arn: string;
  resourceId: string;
  resourceType: string;
  name: string;
  region: string;
  environment: string;
  tier?: string;
  tierConfidence?: string;
  properties: Record<string, any>;  // 타입별 고유 프로퍼티
  tags: Record<string, string>;
  connections: {
    incoming: ConnectionSummary[];   // 이 리소스를 가리키는 것들
    outgoing: ConnectionSummary[];   // 이 리소스가 가리키는 것들
  };
}
```

---

## Phase 2 API (상세 수집 + 보안)

### 감사 로그

| Method | Path | 설명 | 쿼리 파라미터 | 응답 |
|---|---|---|---|---|
| GET | `/api/audit/llm` | LLM 호출 감사 로그 | `from`, `to`, `page`, `size` | `Page<LlmAuditLog>` |
| GET | `/api/audit/llm/stats` | LLM 사용 통계 | `period` (7d/30d) | `{ dailyCalls, avgTokens, totalCostUsd }` |

---

## Phase 3 API (AI 의미층)

### 보안 감사

| Method | Path | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/audit/run` | 감사 실행 | — | `AuditReport` |
| GET | `/api/audit/latest` | 최근 감사 결과 | — | `AuditReport` |
| GET | `/api/audit/findings` | 감사 항목 조회 | `level`, `category` | `AuditFinding[]` |
| GET | `/api/audit/rules` | 등록된 규칙 목록 | — | `AuditRule[]` |
| POST | `/api/audit/explain` | 감사 결과 LLM 설명 | — | `AuditExplanation` |

### 자연어 질의

| Method | Path | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/query` | 자연어 질의 | `{ query: string }` | `NlQueryResponse` |
| GET | `/api/query/examples` | 예시 질의 목록 | — | `string[]` |

### 레이블링

| Method | Path | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/labels/generate` | 자동 레이블링 실행 | — | `LabelResponse[]` |
| GET | `/api/labels` | 현재 레이블 목록 | — | `{ arn, tier, confidence, source }[]` |
| PUT | `/api/labels/{arn}` | 수동 레이블 수정 | `{ tier: string }` | `LabelResponse` |

### 아키텍처 요약

| Method | Path | 설명 | 쿼리 파라미터 | 응답 |
|---|---|---|---|---|
| POST | `/api/summary/generate` | 아키텍처 요약 생성 | `lang` (ko/en) | `ArchitectureSummaryResponse` |

### 응답 타입

```typescript
interface NlQueryResponse {
  success: boolean;
  generatedCypher?: string;
  results?: Record<string, any>[];
  explanation?: string;
  subgraph?: GraphResponse;     // 관련 서브그래프
  truncated?: boolean;
  totalEstimate?: number;
  error?: string;
  suggestions?: string[];       // 빈 결과 시 유사 질의 제안
  clarificationNeeded?: boolean;
  clarificationOptions?: ResourceSummary[];
}

interface AuditReport {
  runAt: string;
  totalFindings: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  findings: AuditFinding[];
}

interface AuditFinding {
  id: number;
  ruleId: string;
  ruleName: string;
  riskLevel: "HIGH" | "MEDIUM" | "LOW";
  category: string;
  resourceArn: string;
  resourceName: string;
  resourceType: string;
  secondaryArn?: string | null;
  secondaryName?: string | null;
  detail?: string | null;
  remediationHint: string;
}

interface AuditExplanation {
  generatedAt: string;
  summary: string;
  priorities: string[];
  actions: string[];
}

interface LabelResponse {
  arn: string;
  tier: string;
  confidence: string;
  confidenceScore: number;
  source: string;
}

interface ArchitectureSummaryResponse {
  summary: string;
  language: "ko" | "en";
  generatedAt: string;
}
```

---

## Phase 4 API (드리프트 & 타임라인)

### 스냅샷

| Method | Path | 설명 | 쿼리 파라미터 | 응답 |
|---|---|---|---|---|
| GET | `/api/snapshots` | 스냅샷 목록 | `period` (24h/7d/30d), `page`, `size` | `Page<SnapshotSummary>` |
| GET | `/api/snapshots/{id}` | 스냅샷 상세 | — | `Snapshot` |
| GET | `/api/snapshots/diff` | 두 스냅샷 비교 | `from`, `to` | `SnapshotDiff` |
| GET | `/api/snapshots/diff/latest` | 최근 diff | — | `SnapshotDiff` |

### 타임라인

| Method | Path | 설명 | 쿼리 파라미터 | 응답 |
|---|---|---|---|---|
| GET | `/api/timeline` | 변경 타임라인 | `from`, `to` | `TimelineEntry[]` |

### Terraform 드리프트

| Method | Path | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/drift/terraform` | 드리프트 감지 실행 | — | `DriftReport` |
| GET | `/api/drift/latest` | 최근 드리프트 보고서 | — | `DriftReport` |

### EventBridge 이벤트

| Method | Path | 설명 | 쿼리 파라미터 | 응답 |
|---|---|---|---|---|
| GET | `/api/events` | 이벤트 로그 | `from`, `to`, `type`, `page` | `Page<EventLog>` |

### 알림

| Method | Path | 설명 | 요청 | 응답 |
|---|---|---|---|---|
| GET | `/api/notifications` | 발송된 알림 목록 | `page`, `size` | `Page<Notification>` |
| POST | `/api/notifications/test` | 테스트 알림 발송 | — | `{ sent: boolean }` |

### 응답 타입

```typescript
interface SnapshotDiff {
  baseSnapshot: { id: number; capturedAt: string };
  targetSnapshot: { id: number; capturedAt: string };
  summary: { added: number; removed: number; modified: number; totalChanges: number };
  changes: DiffChange[];
}

interface DiffChange {
  type: "ADDED" | "REMOVED" | "MODIFIED";
  arn: string;
  resourceName: string;
  resourceType: string;
  changes?: Record<string, { old: any; new: any }>;  // MODIFIED만
}

interface DriftReport {
  runAt: string;
  items: DriftItem[];
  summary: { missing: number; modified: number; unmanaged: number };
}

interface DriftItem {
  type: "MISSING" | "MODIFIED" | "UNMANAGED";
  arn: string;
  resourceName: string;
  resourceType: string;
  terraformModule?: string;
  changes?: Record<string, { tfValue: any; actualValue: any }>;
}

interface SnapshotSummary {
  id: number;
  capturedAt: string;
  nodeCount: number;
  edgeCount: number;
  scanDurationMs: number;
  hasChanges: boolean;         // 이전 스냅샷 대비 변경 있는지
  changeSeverity?: "HIGH" | "MEDIUM" | "LOW" | null;  // 변경 중요도 (타임라인 마커 색상)
}

interface TimelineEntry {
  snapshotId: number;
  capturedAt: string;
  summary: { added: number; removed: number; modified: number };
  highlights: string[];        // 주요 변경 요약 ("prod-api-sg 인바운드 규칙 추가" 등)
}

interface EventLog {
  id: number;
  eventName: string;
  resourceType: string;
  resourceId: string;
  timestamp: string;
  source: string;              // "ec2.amazonaws.com" 등
  processed: boolean;
}
```

---

## API 엔드포인트 총 수

| Phase | 엔드포인트 수 | 카테고리 |
|---|---|---|
| 1 | 7 | 스캔(3) + 그래프(4) |
| 2 | 2 | 감사 로그(2) |
| 3 | 9 | 감사(5) + 질의(2) + 레이블(3) + 요약(1) — 감사 2개는 Phase 2와 겹침 |
| 4 | 9 | 스냅샷(4) + 타임라인(1) + 드리프트(2) + 이벤트(1) + 알림(2) |
| **총** | **27** | |
