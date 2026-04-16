export type GraphMetadata = {
  totalNodes: number;
  totalEdges: number;
  collectedAt: string | null;
  scanDurationMs: number;
};

export type GraphNodeRecord = {
  id: string;
  type: string;
  data: Record<string, unknown>;
  parentNode: string | null;
};

export type GraphEdgeRecord = {
  id: string;
  source: string;
  target: string;
  type: string;
  data: Record<string, unknown>;
};

export type GraphResponse = {
  nodes: GraphNodeRecord[];
  edges: GraphEdgeRecord[];
  metadata: GraphMetadata;
};

export type ResourceDetailNode = {
  id: string;
  type: string;
  data: Record<string, unknown>;
  parentNode: string | null;
};

export type ResourceConnection = {
  direction: string;
  relationshipType: string;
  relationshipData: Record<string, unknown>;
  node: ResourceDetailNode;
};

export type ResourceDetailResponse = {
  resource: ResourceDetailNode;
  connections: ResourceConnection[];
};

export type ScanStatusResponse = {
  scanId: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  startedAt: string;
  completedAt: string | null;
  totalNodes: number;
  totalEdges: number;
  durationMs: number;
  errorMessage: string | null;
  warningMessage: string | null;
};

export type ScanPreflightResponse = {
  ready: boolean;
  region: string;
  accountId: string | null;
  callerArn: string | null;
  authenticationMode: 'static' | 'default-chain' | string;
  message: string;
  warningMessage: string | null;
};

export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW';

export type AuditFinding = {
  id: number;
  ruleId: string;
  ruleName: string;
  riskLevel: RiskLevel;
  category: string;
  resourceArn: string;
  resourceName: string;
  resourceType: string;
  secondaryArn: string | null;
  secondaryName: string | null;
  detail: string | null;
  remediationHint: string;
};

export type AuditReportResponse = {
  runAt: string;
  totalFindings: number;
  highCount: number;
  mediumCount: number;
  lowCount: number;
  findings: AuditFinding[];
};

export type AuditRuleResponse = {
  ruleId: string;
  name: string;
  description: string;
  riskLevel: RiskLevel;
  category: string;
  remediationHint: string;
};

export type AuditExplanationResponse = {
  generatedAt: string;
  summary: string;
  priorities: string[];
  actions: string[];
};

export type ResourceSummaryResponse = {
  arn: string;
  name: string;
  resourceType: string;
  environment: string | null;
};

export type NlQueryResponse = {
  success: boolean;
  generatedCypher: string | null;
  results: Record<string, unknown>[];
  explanation: string | null;
  subgraph: GraphResponse | null;
  truncated: boolean;
  totalEstimate: number | null;
  error: string | null;
  suggestions: string[];
  clarificationNeeded: boolean;
  clarificationOptions: ResourceSummaryResponse[];
};

export type LabelResponse = {
  arn: string;
  tier: string;
  confidence: string;
  confidenceScore: number;
  source: string;
};

export type ArchitectureSummaryResponse = {
  summary: string;
  language: string;
  generatedAt: string;
};

export type SnapshotSummaryResponse = {
  id: number;
  capturedAt: string;
  accountId: string;
  region: string;
  nodeCount: number;
  edgeCount: number;
  scanDurationMs: number;
  triggerSource: string;
  scanId: string | null;
};

export type PropertyChangeResponse = {
  beforeValue: unknown;
  afterValue: unknown;
};

export type NodeDiffResponse = {
  arn: string | null;
  name: string | null;
  resourceType: string | null;
  changeType: 'ADDED' | 'REMOVED' | 'MODIFIED';
  beforeData: Record<string, unknown>;
  afterData: Record<string, unknown>;
  propertyChanges: Record<string, PropertyChangeResponse>;
};

export type EdgeDiffResponse = {
  edgeId: string;
  sourceArn: string;
  targetArn: string;
  relationshipType: string;
  changeType: 'ADDED' | 'REMOVED' | 'MODIFIED';
  beforeData: Record<string, unknown>;
  afterData: Record<string, unknown>;
  propertyChanges: Record<string, PropertyChangeResponse>;
};

export type SnapshotResponse = {
  snapshot: SnapshotSummaryResponse;
  graph: GraphResponse;
  metadata: Record<string, unknown>;
};

export type SnapshotDiffResponse = {
  id: number;
  diffedAt: string;
  baseSnapshot: SnapshotSummaryResponse;
  targetSnapshot: SnapshotSummaryResponse;
  totalChanges: number;
  addedCount: number;
  removedCount: number;
  modifiedCount: number;
  addedNodes: NodeDiffResponse[];
  removedNodes: NodeDiffResponse[];
  modifiedNodes: NodeDiffResponse[];
  addedEdges: EdgeDiffResponse[];
  removedEdges: EdgeDiffResponse[];
  modifiedEdges: EdgeDiffResponse[];
};

export type TimelineEntryResponse = {
  snapshotId: number;
  capturedAt: string;
  baseSnapshotId: number | null;
  totalChanges: number;
  addedCount: number;
  removedCount: number;
  modifiedCount: number;
  triggerSource: string;
  nodeCount: number;
  edgeCount: number;
};

export type DriftItemResponse = {
  status: 'MISSING' | 'MODIFIED' | 'UNMANAGED';
  terraformAddress: string | null;
  resourceType: string | null;
  arn: string | null;
  resourceId: string | null;
  name: string | null;
  summary: string;
  desiredData: Record<string, unknown>;
  actualData: Record<string, unknown>;
  propertyChanges: Record<string, PropertyChangeResponse>;
};

export type DriftReportResponse = {
  id: number;
  generatedAt: string;
  sourceKind: string;
  sourceName: string;
  totalItems: number;
  missingCount: number;
  modifiedCount: number;
  unmanagedCount: number;
  items: DriftItemResponse[];
};

export type EventLogResponse = {
  id: number;
  receivedAt: string;
  eventTime: string | null;
  eventId: string | null;
  source: string;
  detailType: string;
  resourceArn: string | null;
  resourceType: string | null;
  action: string | null;
  status: string;
  message: string | null;
};
