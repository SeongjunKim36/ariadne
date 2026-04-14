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
