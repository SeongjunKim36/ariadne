# 프론트엔드 통합 설계서

> 상위 문서: [project-a-infra-mapper.md](../project-a-infra-mapper.md)
> 기술 스택: React 18 + TypeScript + React Flow + Tailwind CSS + Zustand

---

## 1. 페이지 구성

```
/                          → TopologyPage (메인 — 인프라 토���로지 뷰)
/audit                     → AuditPage (보안 감사 대시보드)
/timeline                  → TimelinePage (드리프트 타임라인)
/drift                     → DriftPage (Terraform 드리프트 비교)
/query                     → QueryPage (자연어 질의 — 별도 페이지 또는 TopologyPage 내 오버레이)
/settings                  → SettingsPage (스캔 주기, LLM 설정, 알림 설정)
```

### 공통 레이아웃

```
┌─────────────────────────────────────────────────┐
│  Sidebar (좌)         │  Main Content (우)       │
│                       │                          │
│  [Logo] Ariadne       │  ┌────────────────────┐  │
│                       │  │  TopBar             │  │
│  ● Topology           │  ��  (검색바 + 스캔상태) │  │
│  ● Audit              │  ├────────────────────┤  │
│  ● Timeline           │  │                    │  │
│  ● Drift              │  │  Page Content      │  │
│  ● Settings           │  │                    │  │
│                       │  │                    │  │
│  ─────────────        │  │                    │  │
│  Last Scan: 10:00     │  │                    │  │
│  Nodes: 47            │  │                    │  │
│  [Scan Now]           │  └────────────────────┘  │
└─────────────────────────────────────────────────┘
```

---

## 2. 컴포넌트 트리

```
App
├── Layout
│   ├── Sidebar
│   │   ├── NavItem[]
│   │   ├── ScanStatus          # 마지막 스캔 시각 + 노드/엣지 수
│   │   └── ScanTriggerButton
│   └── TopBar
│       ├── NlQueryInput        # 자연어 검색바 (모든 페이지에서 접근)
│       ├── EnvironmentSelector # prod/staging/dev 전환
│       └── NotificationBell    # 알림 아이콘
│
├── TopologyPage
│   ├── FilterPanel             # 좌측 필터 (접을 수 있음)
│   │   ├─�� ResourceTypeFilter  # 체크박스: EC2, RDS, VPC...
│   │   ├── VpcFilter           # VPC 드롭다운
│   │   ├── TierFilter          # 계층 필���: web/app/db...
│   │   └── SearchBox           # 리소스 이름/ID 검색
│   ├── TopologyCanvas          # React Flow 메인 캔버스
│   │   ├── CustomNodes/        # 커스텀 노드 컴포넌트
│   │   ├── CustomEdges/        # 커스텀 엣지 컴포넌트
│   │   ├── MiniMap
│   │   └── Controls            # 줌/핏/레이아웃 버튼
│   └── DetailPanel             # 우측 패널 (노드 선택 시)
│       ├── ResourceHeader      # 이름 + 타입 + 상태 뱃지
│       ├── ResourceProperties  # 프로퍼티 테이블
│       ├── ConnectionList      # 연결된 리소스 목록
│       └── TierBadge           # 계층 레이블
│
├── AuditPage
│   ├── AuditSummaryCards       # HIGH/MEDIUM/LOW 카운트 카드
│   ├── AuditCategoryTabs       # SG / IAM / Network / S3 / Encryption
│   ├── FindingList             # finding 목록
│   │   └── FindingItem         # 개별 finding (클릭 → 토폴로지 이동)
│   ├── AuditExplanation        # LLM 설명 패널
│   └── RemediationGuide        # 조치 가이드
│
├── TimelinePage
│   ├── TimelineBar             # 수평 시간축 (스냅샷 점)
│   ├── PeriodSelector          # 24h / 7d / 30d / custom
│   ├── DiffSummary             # 변경 요약 (added/removed/modified)
│   ├── ChangeList              # 변경 항목 목록
│   │   └── ChangeItem          # 개별 변경 (클릭 → 상세)
│   └── DiffTopologyView        # 변경 하이라이트 토폴���지 (선택적)
│
├── DriftPage
│   ├── DriftSummary            # MISSING/MODIFIED/UNMANAGED 카운트
│   ├── DriftItemList           # 드리프트 항목 목록
│   │   └── DriftItem           # TF 선언값 vs 실제값 비교
│   └── TerraformSourceConfig   # TF state 소스 설정
│
├── QueryPage (또는 오버레이)
│   ├── QueryInput              # 자연어 입력 + 예시 버튼
│   ├── QueryResult             # 결과 테이블 + 서브그래프
│   ├── GeneratedCypher         # 생성된 Cypher 표시 (접기)
│   └── QueryExplanation        # LLM 설명
│
└── SettingsPage
    ├── ScanSettings            # 스캔 주기, 리전, 수집 대상
    ├── LlmSettings             # API 키, 전송 레벨, 모델 선택
    ├── NotificationSettings    # Slack webhook, 알림 규칙
    └── NginxPluginSettings     # nginx 플��그인 활성화
```

---

## 3. 상태 관리 (Zustand)

```typescript
// stores/graphStore.ts
interface GraphStore {
  // 그래프 데이터
  nodes: Node[];
  edges: Edge[];
  metadata: GraphMetadata | null;

  // 필터 상태
  filters: {
    resourceTypes: Set<string>;     // 선택된 리소스 타입
    vpcId: string | null;           // 선택된 VPC
    environment: string;            // prod / staging / dev / all
    tiers: Set<string>;             // 선택된 계층
    searchQuery: string;            // 검색어
  };

  // 선택 상태
  selectedNodeId: string | null;
  highlightedNodeIds: Set<string>;  // 역참조 하이라이트

  // 액션
  fetchGraph: (filters?: GraphFilters) => Promise<void>;
  selectNode: (nodeId: string | null) => void;
  highlightConnections: (nodeId: string) => void;
  clearHighlights: () => void;
  updateFilters: (filters: Partial<GraphFilters>) => void;
}

// stores/scanStore.ts
interface ScanStore {
  currentScan: ScanStatus | null;
  lastCompletedScan: ScanResult | null;
  isScanning: boolean;

  triggerScan: () => Promise<void>;
  pollScanStatus: (scanId: string) => void;
}

// stores/auditStore.ts
interface AuditStore {
  latestReport: AuditReport | null;
  selectedCategory: string;         // "all" | "security-group" | "iam" | ...
  selectedLevel: RiskLevel | null;

  runAudit: () => Promise<void>;
  fetchLatest: () => Promise<void>;
  requestExplanation: () => Promise<void>;
}

// stores/timelineStore.ts
interface TimelineStore {
  snapshots: SnapshotSummary[];
  selectedDiff: SnapshotDiff | null;
  period: "24h" | "7d" | "30d" | "custom";
  customRange: { from: Date; to: Date } | null;

  fetchSnapshots: (period: string) => Promise<void>;
  fetchDiff: (fromId: number, toId: number) => Promise<void>;
}

// stores/queryStore.ts
interface QueryStore {
  query: string;
  result: NlQueryResponse | null;
  isLoading: boolean;
  history: QueryHistoryItem[];

  executeQuery: (query: string) => Promise<void>;
  fetchExamples: () => Promise<string[]>;
}
```

---

## 4. React Flow 커스텀 노드 상세

### 4.1 공통 노드 구조

```typescript
// components/nodes/BaseNode.tsx
interface BaseNodeData {
  label: string;
  resourceType: string;
  environment: string;
  state?: string;
  tier?: string;
  tierConfidence?: "auto" | "tentative";
  isStale?: boolean;           // 삭제 추정 노���
  isHighlighted?: boolean;
  diffStatus?: "added" | "removed" | "modified" | null;
}

// 모든 커스텀 노드의 공�� 래퍼
const BaseNode = ({ data, icon, color, children }) => (
  <div className={cn(
    "rounded-lg border-2 p-3 min-w-[160px] shadow-sm",
    `border-${color}-400 bg-${color}-50`,
    data.isHighlighted && "ring-2 ring-blue-500",
    data.isStale && "border-dashed opacity-60",
    data.diffStatus === "added" && "ring-2 ring-green-500",
    data.diffStatus === "removed" && "ring-2 ring-red-500 line-through",
    data.diffStatus === "modified" && "ring-2 ring-orange-500",
  )}>
    <div className="flex items-center gap-2 mb-1">
      {icon}
      <span className="font-medium text-sm truncate">{data.label}</span>
      {data.state && <StateBadge state={data.state} />}
    </div>
    {data.tier && (
      <TierBadge tier={data.tier} confidence={data.tierConfidence} />
    )}
    {data.diffStatus && <DiffBadge status={data.diffStatus} />}
    {children}
    <Handle type="target" position={Position.Left} />
    <Handle type="source" position={Position.Right} />
  </div>
);
```

### 4.2 노드 타입별 상세

```typescript
// EC2 노드 — 서버 아이콘, 인스턴스 타입, IP 표시
const Ec2Node = ({ data }) => (
  <BaseNode data={data} icon={<ServerIcon />} color="orange">
    <div className="text-xs text-gray-500">
      {data.instanceType} · {data.privateIp}
    </div>
  </BaseNode>
);

// RDS 노드 — DB 아이콘, 엔진 + 버전, 엔드포인트
const RdsNode = ({ data }) => (
  <BaseNode data={data} icon={<DatabaseIcon />} color="blue">
    <div className="text-xs text-gray-500">
      {data.engine} {data.engineVersion}
    </div>
  </BaseNode>
);

// VPC 그룹 노드 — 자식 노드를 감��는 컨테이너
const VpcGroupNode = ({ data }) => (
  <div className="rounded-xl border-2 border-gray-300 bg-gray-50/50 p-4 min-w-[300px] min-h-[200px]">
    <div className="flex items-center gap-2 mb-2 text-gray-600">
      <NetworkIcon className="w-4 h-4" />
      <span className="font-medium text-sm">{data.label}</span>
      <span className="text-xs text-gray-400">{data.cidrBlock}</span>
    </div>
    {/* 자식 노드는 React Flow parentNode로 자동 배치 */}
  </div>
);

// Subnet 그룹 노드 — VPC 내부 구획
const SubnetGroupNode = ({ data }) => (
  <div className={cn(
    "rounded-lg border border-dashed p-3 min-w-[200px] min-h-[100px]",
    data.isPublic ? "border-yellow-400 bg-yellow-50/30" : "border-gray-300 bg-white/50"
  )}>
    <div className="text-xs text-gray-500">
      {data.label} · {data.availabilityZone} · {data.cidrBlock}
      {data.isPublic && <span className="ml-1 text-yellow-600">public</span>}
    </div>
  </div>
);

// SecurityGroup 노드 — 방패 아이콘, 규칙 수
const SgNode = ({ data }) => (
  <BaseNode data={data} icon={<ShieldIcon />} color="red">
    <div className="text-xs text-gray-500">
      in: {data.inboundRuleCount} · out: {data.outboundRuleCount}
    </div>
  </BaseNode>
);

// ALB 노드 — 로드밸런서, scheme 표시
const AlbNode = ({ data }) => (
  <BaseNode data={data} icon={<LoadBalancerIcon />} color="purple">
    <div className="text-xs text-gray-500">
      {data.scheme} · {data.type}
    </div>
  </BaseNode>
);

// ECS 노드들
const EcsClusterGroupNode = /* VPC와 유사한 그룹 노드 */;
const EcsServiceNode = ({ data }) => (
  <BaseNode data={data} icon={<ContainerIcon />} color="green">
    <div className="text-xs text-gray-500">
      {data.launchType} · {data.runningCount}/{data.desiredCount}
    </div>
  </BaseNode>
);

// S3, Lambda, Route53
const S3Node = ({ data }) => (
  <BaseNode data={data} icon={<BucketIcon />} color="emerald">
    <div className="text-xs text-gray-500">
      {data.encrypted ? "encrypted" : "unencrypted"}
      {!data.publicAccessBlocked && <span className="text-red-500 ml-1">public!</span>}
    </div>
  </BaseNode>
);

const LambdaNode = ({ data }) => (
  <BaseNode data={data} icon={<LambdaIcon />} color="amber">
    <div className="text-xs text-gray-500">{data.runtime}</div>
  </BaseNode>
);

const Route53Node = ({ data }) => (
  <BaseNode data={data} icon={<DnsIcon />} color="violet">
    <div className="text-xs text-gray-500">{data.domainName}</div>
  </BaseNode>
);

// CidrSource 가상 노드 (Phase 2)
const CidrSourceNode = ({ data }) => (
  <div className={cn(
    "rounded-full border-2 p-2 text-center min-w-[100px]",
    data.isPublic ? "border-red-500 bg-red-50" : "border-gray-400 bg-gray-50"
  )}>
    <div className="text-xs font-mono">{data.cidr}</div>
    <div className="text-xs text-gray-500">{data.label}</div>
  </div>
);
```

### 4.3 커스텀 엣지

```typescript
// 관계 타입별 엣지 스타일
const edgeStyles: Record<string, EdgeStyle> = {
  BELONGS_TO:    { stroke: "#9CA3AF", strokeDasharray: "5,5", animated: false },
  HAS_SG:       { stroke: "#EF4444", strokeWidth: 1.5, animated: false },
  ROUTES_TO:    { stroke: "#8B5CF6", strokeWidth: 2, animated: true },
  ALLOWS_FROM:  { stroke: "#F59E0B", strokeWidth: 1, animated: false },
  ALLOWS_SELF:  { stroke: "#F59E0B", strokeDasharray: "3,3" },
  EGRESS_TO:    { stroke: "#6B7280", strokeDasharray: "3,3" },
  LIKELY_USES:  { stroke: "#3B82F6", strokeDasharray: "8,4", animated: true },
  RUNS_IN:      { stroke: "#10B981", strokeWidth: 1.5 },
  HAS_RECORD:   { stroke: "#7C3AED", strokeWidth: 1 },
  TRIGGERS:     { stroke: "#F97316", animated: true },
};
```

---

## 5. 레이아웃 전략

### dagre 설정

```typescript
import dagre from "dagre";

const layoutGraph = (nodes: Node[], edges: Edge[]) => {
  const g = new dagre.graphlib.Graph({ compound: true }); // 그룹 노드 지원
  g.setGraph({
    rankdir: "LR",           // 좌→우 배치
    nodesep: 60,             // 노드 간 수직 간격
    ranksep: 120,            // 랭크 간 수평 간격
    marginx: 40,
    marginy: 40,
  });
  g.setDefaultEdgeLabel(() => ({}));

  // VPC/Subnet은 그룹 → 내부 노드는 parent 설정
  nodes.forEach(node => {
    if (node.type === "vpc-group" || node.type === "subnet-group") {
      g.setNode(node.id, { width: 400, height: 300 });
    } else {
      g.setNode(node.id, { width: 180, height: 80 });
    }
    if (node.parentNode) {
      g.setParent(node.id, node.parentNode);
    }
  });

  edges.forEach(edge => g.setEdge(edge.source, edge.target));
  dagre.layout(g);

  return nodes.map(node => ({
    ...node,
    position: { x: g.node(node.id).x, y: g.node(node.id).y },
  }));
};
```

### 리소스 50개 초과 시 자동 클러스터링

```typescript
const shouldCluster = (nodes: Node[]) => nodes.length > 50;

// 환경별 클러스터링: prod/staging/dev를 별도 영��으로
// VPC별 클러스터링: 각 VPC를 독립 그룹으로
// 계층별 클러스터링: web → app → db 순서로 수직 배치
```

---

## 6. API ��동 패턴

```typescript
// api/client.ts
const api = {
  graph: {
    fetch: (filters?: GraphFilters) =>
      get<GraphResponse>("/api/graph", { params: filters }),
  },
  scan: {
    trigger: () => post<{ scanId: string; status: string; startedAt: string }>("/api/scan"),
    status: (scanId: string) => get<ScanStatus>(`/api/scan/${scanId}/status`),
    latest: () => get<ScanResult>("/api/scan/latest"),
  },
  resources: {
    detail: (arn: string) =>
      get<ResourceDetail>("/api/resources", { params: { arn } }),
    connections: (arn: string) =>
      get<ConnectedResource[]>("/api/resources/connections", { params: { arn } }),
  },
  audit: {
    run: () => post<AuditReport>("/api/audit/run"),
    latest: () => get<AuditReport>("/api/audit/latest"),
    explain: () => post<AuditExplanation>("/api/audit/explain"),
  },
  query: {
    execute: (query: string) => post<NlQueryResponse>("/api/query", { query }),
    examples: () => get<string[]>("/api/query/examples"),
  },
  timeline: {
    snapshots: (period: string) => get<SnapshotSummary[]>("/api/snapshots", { params: { period } }),
    diff: (fromId: number, toId: number) =>
      get<SnapshotDiff>("/api/snapshots/diff", { params: { from: fromId, to: toId } }),
    latest: () => get<SnapshotDiff>("/api/snapshots/diff/latest"),
    changes: (from: string, to: string) =>
      get<TimelineEntry[]>("/api/timeline", { params: { from, to } }),
  },
  drift: {
    detect: () => post<DriftReport>("/api/drift/terraform"),
    latest: () => get<DriftReport>("/api/drift/latest"),
  },
  events: {
    list: (params?: { from?: string; to?: string; type?: string; page?: number }) =>
      get<Page<EventLog>>("/api/events", { params }),
  },
  notifications: {
    list: (params?: { page?: number; size?: number }) =>
      get<Page<Notification>>("/api/notifications", { params }),
    test: () => post<{ sent: boolean }>("/api/notifications/test"),
  },
};
```

### SWR/React Query 훅

```typescript
// hooks/useGraph.ts
export const useGraph = () => {
  const filters = useGraphStore(s => s.filters);
  return useSWR(
    ["/api/graph", filters],
    () => api.graph.fetch(filters),
    { refreshInterval: 60_000 }  // 1분마다 갱신
  );
};

// hooks/useScanStatus.ts — 스캔 중 폴링
export const useScanStatus = (scanId: string | null) => {
  return useSWR(
    scanId ? `/api/scan/${scanId}/status` : null,
    () => api.scan.status(scanId!),
    { refreshInterval: scanId ? 2_000 : 0 }  // 스캔 중 2초 폴링
  );
};
```

---

## 7. Phase별 프론트엔드 구현 범위

| Phase | 프론트 구현 |
|---|---|
| 1 | TopologyCanvas + 11종 커스텀 노드 (Ec2, Rds, VpcGroup, SubnetGroup, Sg, Alb, EcsClusterGroup, EcsService, S3, Lambda, Route53) + FilterPanel + DetailPanel + dagre 레이아웃 + MiniMap |
| 2 | CidrSourceNode + IamRoleNode + SG 규칙 엣지 시각화 + 감사 대시보드(규칙 기반만) |
| 3 | NlQueryInput + QueryResult + AuditExplanation(LLM) + TierBadge + 레이블 필터 |
| 4 | TimelineBar + DiffSummary + ChangeList + DiffTopologyView + DriftPage + EventLog UI |

### 노드 타입 ↔ Neo4j 레이블 매핑 (전체)

| React Flow 노드 타입 | Neo4j 레이블 | Phase |
|---|---|---|
| `ec2` | `Ec2Instance` | 1 |
| `rds` | `RdsInstance` | 1 |
| `vpc-group` | `Vpc` | 1 |
| `subnet-group` | `Subnet` | 1 |
| `sg` | `SecurityGroup` | 1 |
| `alb` | `LoadBalancer` | 1 |
| `ecs-cluster-group` | `EcsCluster` | 1 |
| `ecs-service` | `EcsService` | 1 |
| `s3` | `S3Bucket` | 1 |
| `lambda` | `LambdaFunction` | 1 |
| `route53` | `Route53Zone` | 1 |
| `cidr-source` | `CidrSource` | 2 |
| `iam-role` | `IamRole` | 2 |
| `db-subnet-group` | `DbSubnetGroup` | 1 (내부용, 시각화에선 Subnet 그룹으로 표현) |
| `ecs-task-def` | `EcsTaskDefinition` | 2 (DetailPanel에서만 표시, 토폴로지 노드 아님) |
| `nginx-config` | `NginxConfig` | 2 (플러그인, DetailPanel에서만 표시) |

---

## 8. 의존성

```json
{
  "dependencies": {
    "react": "^18.3",
    "react-dom": "^18.3",
    "reactflow": "^11.11",
    "dagre": "^0.8",
    "zustand": "^4.5",
    "swr": "^2.2",
    "axios": "^1.7",
    "tailwindcss": "^3.4",
    "@headlessui/react": "^2.0",
    "lucide-react": "^0.400",
    "date-fns": "^3.6",
    "react-router-dom": "^6.23"
  },
  "devDependencies": {
    "typescript": "^5.5",
    "vite": "^5.4",
    "@types/dagre": "^0.7"
  }
}
```
