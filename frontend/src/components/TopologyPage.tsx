import { startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react';
import { formatDistanceToNow } from 'date-fns';
import { Filter, Focus, LoaderCircle, Search, Sparkles, TriangleAlert, X } from 'lucide-react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  ReactFlowProvider,
  type NodeMouseHandler,
} from 'reactflow';
import useSWR from 'swr';
import 'reactflow/dist/style.css';

import {
  fetchGraph,
  fetchLatestScan,
  fetchResourceDetail,
  fetchScanPreflight,
  fetchScanStatus,
  triggerScan,
} from '../lib/api';
import { buildTopologyElements } from '../lib/layout';
import type {
  GraphNodeRecord,
  ResourceDetailResponse,
  ScanPreflightResponse,
  ScanStatusResponse,
} from '../lib/types';
import { topologyNodeTypes } from './topologyNodes';

function value(record: Record<string, unknown>, key: string) {
  const raw = record[key];
  return typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean'
    ? String(raw)
    : '';
}

function matchesSearch(node: GraphNodeRecord, query: string) {
  if (!query) {
    return true;
  }
  const haystack = Object.values(node.data)
    .map((entry) => (entry == null ? '' : String(entry).toLowerCase()))
    .join(' ');
  return haystack.includes(query.toLowerCase());
}

function collectAncestorIds(nodeId: string, nodesById: Map<string, GraphNodeRecord>) {
  const ancestors = new Set<string>();
  let current = nodesById.get(nodeId);
  while (current?.parentNode) {
    ancestors.add(current.parentNode);
    current = nodesById.get(current.parentNode);
  }
  return ancestors;
}

function belongsToVpc(
  node: GraphNodeRecord,
  selectedVpc: string,
  nodesById: Map<string, GraphNodeRecord>,
  neighborsById: Map<string, Set<string>>,
) {
  if (!selectedVpc) {
    return true;
  }
  const queue: string[] = [node.id];
  const visited = new Set<string>();

  while (queue.length > 0) {
    const currentId = queue.shift();
    if (!currentId || visited.has(currentId)) {
      continue;
    }
    visited.add(currentId);
    const current = nodesById.get(currentId);
    if (!current) {
      continue;
    }
    if (current.type === 'vpc-group' && value(current.data, 'resourceId') === selectedVpc) {
      return true;
    }
    if (current.parentNode && !visited.has(current.parentNode)) {
      queue.push(current.parentNode);
    }
    for (const neighborId of neighborsById.get(currentId) ?? new Set<string>()) {
      if (!visited.has(neighborId)) {
        queue.push(neighborId);
      }
    }
  }
  return false;
}

function normalizePropertyEntries(record: Record<string, unknown>) {
  const tags = Object.entries(record)
    .filter(([key]) => key.startsWith('tag_'))
    .sort(([left], [right]) => left.localeCompare(right));
  const primary = Object.entries(record)
    .filter(([key]) => !key.startsWith('tag_'))
    .sort(([left], [right]) => left.localeCompare(right));
  return [...primary, ...tags];
}

function scanStatusTone(status: ScanStatusResponse['status'] | null | undefined) {
  switch (status) {
    case 'COMPLETED':
      return 'good';
    case 'FAILED':
      return 'bad';
    case 'RUNNING':
      return 'running';
    default:
      return 'idle';
  }
}

function scanStatusCopy(scan: ScanStatusResponse | null | undefined) {
  if (!scan) {
    return 'No scan has been run from this workspace yet.';
  }
  if (scan.status === 'RUNNING') {
    return 'Collectors are running. The graph will refresh as soon as this scan completes.';
  }
  if (scan.completedAt) {
    return `Finished ${formatDistanceToNow(new Date(scan.completedAt), { addSuffix: true })}.`;
  }
  return 'Scan history is available, but the completion timestamp is missing.';
}

function preflightTone(preflight: ScanPreflightResponse | undefined) {
  if (!preflight) {
    return 'idle';
  }
  return preflight.ready ? 'good' : 'bad';
}

function DetailPanel({
  resourceDetail,
  fallbackNode,
  onClose,
}: {
  resourceDetail: ResourceDetailResponse | undefined;
  fallbackNode: GraphNodeRecord | undefined;
  onClose: () => void;
}) {
  const resource = resourceDetail?.resource ?? (fallbackNode
    ? {
        id: fallbackNode.id,
        type: fallbackNode.type,
        data: fallbackNode.data,
        parentNode: fallbackNode.parentNode,
      }
    : null);

  if (!resource) {
    return (
      <aside className="detail-panel empty">
        <div className="detail-panel-hero">
          <Sparkles size={18} />
          <div>
            <p className="detail-panel-title">Select a resource</p>
            <p className="detail-panel-caption">Click any node to inspect tags, shape, and connections.</p>
          </div>
        </div>
      </aside>
    );
  }

  return (
    <aside className="detail-panel" data-testid="detail-panel">
      <div className="detail-panel-header">
        <div>
          <p className="detail-panel-title">{value(resource.data, 'name') || value(resource.data, 'resourceId')}</p>
          <p className="detail-panel-caption">{value(resource.data, 'resourceType')} · {value(resource.data, 'resourceId')}</p>
        </div>
        <button type="button" className="ghost-icon-button" onClick={onClose} aria-label="Close details">
          <X size={16} />
        </button>
      </div>

      <section className="detail-panel-section">
        <p className="detail-section-label">Properties</p>
        <div className="detail-grid">
          {normalizePropertyEntries(resource.data).map(([key, raw]) => (
            <div key={key} className="detail-grid-row">
              <span className="detail-grid-key">{key}</span>
              <span className="detail-grid-value">{String(raw)}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="detail-panel-section">
        <p className="detail-section-label">Connections</p>
        <div className="connection-list">
          {(resourceDetail?.connections ?? []).length === 0 ? (
            <p className="detail-panel-caption">No active neighboring resources found.</p>
          ) : (
            resourceDetail?.connections.map((connection) => (
              <div key={`${connection.relationshipType}-${connection.node.id}`} className="connection-card">
                <div>
                  <p className="connection-title">
                    {value(connection.node.data, 'name') || value(connection.node.data, 'resourceId')}
                  </p>
                  <p className="connection-caption">
                    {connection.direction} · {connection.relationshipType}
                  </p>
                </div>
                <span className="connection-pill">{value(connection.node.data, 'resourceType') || connection.node.type}</span>
              </div>
            ))
          )}
        </div>
      </section>
    </aside>
  );
}

export function TopologyPage() {
  const nodeTypes = useMemo(() => topologyNodeTypes, []);
  const { data: graph, error, isLoading, mutate } = useSWR('graph', fetchGraph, {
    shouldRetryOnError: false,
  });
  const { data: latestScan, mutate: mutateLatestScan } = useSWR('latest-scan', fetchLatestScan, {
    shouldRetryOnError: false,
  });
  const { data: scanPreflight } = useSWR('scan-preflight', fetchScanPreflight, {
    shouldRetryOnError: false,
    revalidateOnFocus: false,
  });

  const [search, setSearch] = useState('');
  const [environment, setEnvironment] = useState('');
  const [selectedVpc, setSelectedVpc] = useState('');
  const [activeTypes, setActiveTypes] = useState<string[]>([]);
  const [selectedArn, setSelectedArn] = useState<string | null>(null);
  const [focusedArn, setFocusedArn] = useState<string | null>(null);
  const [activeScanId, setActiveScanId] = useState<string | null>(null);
  const [scanActionError, setScanActionError] = useState<string | null>(null);

  const deferredSearch = useDeferredValue(search);
  const { data: resourceDetail } = useSWR(
    selectedArn ? ['resource', selectedArn] : null,
    ([, arn]) => fetchResourceDetail(arn),
    { shouldRetryOnError: false },
  );
  const pollScanId = activeScanId ?? (latestScan?.status === 'RUNNING' ? latestScan.scanId : null);
  const { data: polledScan } = useSWR(
    pollScanId ? ['scan-status', pollScanId] : null,
    ([, scanId]) => fetchScanStatus(scanId),
    {
      shouldRetryOnError: false,
      refreshInterval: (current) => (current?.status === 'RUNNING' ? 1500 : 0),
    },
  );
  const currentScan = polledScan ?? latestScan;
  const scanIsRunning = currentScan?.status === 'RUNNING';

  useEffect(() => {
    if (latestScan?.status === 'RUNNING') {
      setActiveScanId((current) => current ?? latestScan.scanId);
    }
  }, [latestScan?.scanId, latestScan?.status]);

  useEffect(() => {
    if (!polledScan) {
      return;
    }

    mutateLatestScan(polledScan, { revalidate: false });
    if (polledScan.status !== 'RUNNING') {
      setActiveScanId(null);
      void mutate();
    }
  }, [polledScan, mutateLatestScan, mutate]);

  const nodesById = new Map((graph?.nodes ?? []).map((node) => [node.id, node]));
  const neighborsById = new Map<string, Set<string>>();
  for (const edge of graph?.edges ?? []) {
    const sourceNeighbors = neighborsById.get(edge.source) ?? new Set<string>();
    sourceNeighbors.add(edge.target);
    neighborsById.set(edge.source, sourceNeighbors);

    const targetNeighbors = neighborsById.get(edge.target) ?? new Set<string>();
    targetNeighbors.add(edge.source);
    neighborsById.set(edge.target, targetNeighbors);
  }
  const allTypes = Array.from(new Set((graph?.nodes ?? []).map((node) => node.type))).sort();
  const environmentOptions = Array.from(new Set(
    (graph?.nodes ?? [])
      .map((node) => value(node.data, 'environment'))
      .filter(Boolean),
  )).sort();
  const vpcOptions = (graph?.nodes ?? [])
    .filter((node) => node.type === 'vpc-group')
    .map((node) => ({
      id: value(node.data, 'resourceId'),
      label: value(node.data, 'name') || value(node.data, 'resourceId'),
    }));

  const matchedNodes = (graph?.nodes ?? []).filter((node) => {
    const typeMatch = activeTypes.length === 0 || activeTypes.includes(node.type);
    const envMatch = !environment || value(node.data, 'environment') === environment;
    const vpcMatch = belongsToVpc(node, selectedVpc, nodesById, neighborsById);
    const searchMatch = matchesSearch(node, deferredSearch);
    return typeMatch && envMatch && vpcMatch && searchMatch;
  });

  const visibleIds = new Set<string>();
  for (const node of matchedNodes) {
    visibleIds.add(node.id);
    for (const ancestorId of collectAncestorIds(node.id, nodesById)) {
      visibleIds.add(ancestorId);
    }
  }
  const cidrAllowed = activeTypes.length === 0 || activeTypes.includes('cidr');
  if (cidrAllowed) {
    for (const edge of graph?.edges ?? []) {
      const sourceNode = nodesById.get(edge.source);
      const targetNode = nodesById.get(edge.target);
      if (sourceNode?.type === 'cidr' && visibleIds.has(edge.target)) {
        visibleIds.add(edge.source);
      }
      if (targetNode?.type === 'cidr' && visibleIds.has(edge.source)) {
        visibleIds.add(edge.target);
      }
    }
  }
  if (activeTypes.length === 0 && !environment && !selectedVpc && !deferredSearch) {
    for (const node of graph?.nodes ?? []) {
      visibleIds.add(node.id);
    }
  }

  const visibleNodes = (graph?.nodes ?? []).filter((node) => visibleIds.has(node.id));
  const visibleEdges = (graph?.edges ?? []).filter((edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target));
  const hasFilters = Boolean(search || environment || selectedVpc || activeTypes.length > 0);

  const emphasizedIds = new Set<string>();
  if (focusedArn) {
    emphasizedIds.add(focusedArn);
    for (const ancestorId of collectAncestorIds(focusedArn, nodesById)) {
      emphasizedIds.add(ancestorId);
    }
    for (const edge of visibleEdges) {
      if (edge.source === focusedArn || edge.target === focusedArn) {
        emphasizedIds.add(edge.source);
        emphasizedIds.add(edge.target);
        for (const ancestorId of collectAncestorIds(edge.source, nodesById)) {
          emphasizedIds.add(ancestorId);
        }
        for (const ancestorId of collectAncestorIds(edge.target, nodesById)) {
          emphasizedIds.add(ancestorId);
        }
      }
    }
  }

  useEffect(() => {
    if (selectedArn && !visibleIds.has(selectedArn)) {
      setSelectedArn(null);
    }
    if (focusedArn && !visibleIds.has(focusedArn)) {
      setFocusedArn(null);
    }
  }, [selectedArn, focusedArn, visibleIds]);

  const fallbackNode = selectedArn ? nodesById.get(selectedArn) : undefined;
  const flow = buildTopologyElements(visibleNodes, visibleEdges, selectedArn, emphasizedIds);

  const onNodeClick: NodeMouseHandler = (_, node) => {
    setSelectedArn(node.id);
  };

  const onNodeDoubleClick: NodeMouseHandler = (_, node) => {
    setFocusedArn((current) => (current === node.id ? null : node.id));
  };

  const toggleType = (type: string) => {
    startTransition(() => {
      setActiveTypes((current) => {
        if (current.length === 0) {
          return allTypes.filter((entry) => entry !== type);
        }
        return current.includes(type)
          ? current.filter((entry) => entry !== type)
          : [...current, type].sort();
      });
    });
  };

  const startScan = async () => {
    setScanActionError(null);
    try {
      const scan = await triggerScan();
      setActiveScanId(scan.scanId);
      await mutateLatestScan(scan, { revalidate: false });
    } catch (scanError) {
      const message = scanError instanceof Error
        ? scanError.message
        : 'A new scan could not be started.';
      setScanActionError(message);
    }
  };

  if (isLoading) {
    return (
      <div className="topology-loading">
        <LoaderCircle className="spin" size={18} />
        <span>Loading the latest infrastructure map…</span>
      </div>
    );
  }

  if (error || !graph) {
    return (
      <div className="topology-empty-state">
        <p className="topology-empty-title">Graph API is not reachable yet.</p>
        <p className="topology-empty-copy">Check whether the backend is running and exposing <code>/api/graph</code>.</p>
      </div>
    );
  }

  return (
    <div className="topology-page">
      <section className="topology-main">
        <header className="topology-toolbar">
          <div className="search-shell">
            <Search size={16} />
            <input
              value={search}
              onChange={(event) => startTransition(() => setSearch(event.target.value))}
              placeholder="Search by name, resource id, engine, IP, or tags"
            />
          </div>
          <div className="toolbar-actions">
            <button type="button" className="ghost-button" onClick={() => mutate()}>
              Refresh graph
            </button>
            <button
              type="button"
              className="ghost-button primary-button"
              onClick={() => void startScan()}
              disabled={!scanPreflight?.ready || scanIsRunning}
              data-testid="start-scan-button"
            >
              {scanIsRunning ? <LoaderCircle className="spin" size={14} /> : null}
              {scanIsRunning ? 'Scanning…' : 'Start scan'}
            </button>
            {focusedArn ? (
              <button type="button" className="ghost-button" onClick={() => setFocusedArn(null)}>
                <Focus size={14} />
                Reset focus
              </button>
            ) : null}
          </div>
        </header>

        <section
          className="scan-status-bar"
          data-tone={scanStatusTone(currentScan?.status)}
          data-testid="scan-status-banner"
        >
          <div className="scan-status-main">
            <div className="scan-status-heading">
              <span className="status-pill" data-tone={scanStatusTone(currentScan?.status)}>
                {currentScan?.status ?? 'IDLE'}
              </span>
              <span className="status-pill" data-tone={preflightTone(scanPreflight)}>
                {scanPreflight?.ready ? 'AWS READY' : 'AWS BLOCKED'}
              </span>
            </div>
            <p className="scan-status-copy">{scanStatusCopy(currentScan)}</p>
            <p className="scan-status-copy secondary">
              {scanPreflight?.message ?? 'Checking whether local AWS credentials are ready for a scan…'}
            </p>
            <p className="scan-status-meta">
              {scanPreflight?.accountId
                ? `${scanPreflight.accountId} · ${scanPreflight.region}`
                : scanPreflight?.region ?? 'ap-northeast-2'}
              {currentScan ? ` · ${currentScan.totalNodes} nodes · ${currentScan.totalEdges} edges` : ''}
            </p>
          </div>

          <div className="scan-status-side">
            {currentScan?.errorMessage ? (
              <div className="scan-callout" data-tone="bad">
                <TriangleAlert size={16} />
                <span>{currentScan.errorMessage}</span>
              </div>
            ) : null}
            {currentScan?.warningMessage ? (
              <div className="scan-callout" data-tone="warn">
                <TriangleAlert size={16} />
                <span>{currentScan.warningMessage}</span>
              </div>
            ) : null}
            {scanActionError ? (
              <div className="scan-callout" data-tone="bad">
                <TriangleAlert size={16} />
                <span>{scanActionError}</span>
              </div>
            ) : null}
            {scanPreflight?.warningMessage ? (
              <div className="scan-callout" data-tone="warn">
                <TriangleAlert size={16} />
                <span>{scanPreflight.warningMessage}</span>
              </div>
            ) : null}
          </div>
        </section>

        <div className="topology-content">
          <aside className="filter-panel">
            <div className="filter-panel-header">
              <Filter size={16} />
              <div>
                <p className="filter-panel-title">Filter Map</p>
                <p className="filter-panel-caption">{visibleNodes.length} visible nodes</p>
              </div>
            </div>

            <label className="field">
              <span>Environment</span>
              <select value={environment} onChange={(event) => setEnvironment(event.target.value)}>
                <option value="">All environments</option>
                {environmentOptions.map((entry) => (
                  <option key={entry} value={entry}>{entry}</option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>VPC</span>
              <select value={selectedVpc} onChange={(event) => setSelectedVpc(event.target.value)}>
                <option value="">All VPCs</option>
                {vpcOptions.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
              </select>
            </label>

            <div className="type-list">
              {allTypes.map((type) => {
                const active = activeTypes.length === 0 || activeTypes.includes(type);
                return (
                  <button
                    key={type}
                    type="button"
                    className="type-chip"
                    data-active={active}
                    onClick={() => toggleType(type)}
                  >
                    {type}
                  </button>
                );
              })}
            </div>

            {hasFilters ? (
              <button
                type="button"
                className="ghost-button filter-reset"
                onClick={() => {
                  setSearch('');
                  setEnvironment('');
                  setSelectedVpc('');
                  setActiveTypes([]);
                }}
              >
                Clear filters
              </button>
            ) : null}

            <div className="filter-meta">
              <p>Last collected {graph.metadata.collectedAt ? formatDistanceToNow(new Date(graph.metadata.collectedAt), { addSuffix: true }) : 'unknown'}</p>
              <p>{graph.metadata.totalNodes} nodes · {graph.metadata.totalEdges} edges · {Math.round(graph.metadata.scanDurationMs / 100) / 10}s</p>
            </div>
          </aside>

          <div className="topology-canvas-shell" data-testid="topology-canvas">
            <ReactFlowProvider>
              <>
                <ReactFlow
                  className="topology-flow"
                  nodes={flow.nodes}
                  edges={flow.edges}
                  nodeTypes={nodeTypes}
                  fitView
                  nodesDraggable={false}
                  nodesConnectable={false}
                  onNodeClick={onNodeClick}
                  onNodeDoubleClick={onNodeDoubleClick}
                  onPaneClick={() => setSelectedArn(null)}
                  minZoom={0.3}
                  maxZoom={1.7}
                >
                  <MiniMap
                    pannable
                    zoomable
                    nodeColor={(node) => {
                      if (node.type === 'vpc-group' || node.type === 'subnet-group') {
                        return '#cbd5e1';
                      }
                      if (node.type === 'ecs-cluster-group') {
                        return '#94a3b8';
                      }
                      if (node.type === 'sg') {
                        return '#fb7185';
                      }
                      if (node.type === 'cidr') {
                        return '#f59e0b';
                      }
                      if (node.type === 'rds') {
                        return '#60a5fa';
                      }
                      if (node.type === 'route53' || node.type === 'alb') {
                        return '#a78bfa';
                      }
                      if (node.type === 's3' || node.type === 'ecs-service') {
                        return '#34d399';
                      }
                      return '#f59e0b';
                    }}
                  />
                  <Controls showInteractive={false} />
                  <Background gap={20} size={1} color="#d7dce5" />
                </ReactFlow>
                {visibleNodes.length === 0 ? (
                  <div className="canvas-empty-overlay">
                    <p>No nodes match the current filters.</p>
                    <span>Clear one or two constraints and the topology will repopulate.</span>
                  </div>
                ) : null}
              </>
            </ReactFlowProvider>
          </div>

          <DetailPanel
            resourceDetail={resourceDetail}
            fallbackNode={fallbackNode}
            onClose={() => setSelectedArn(null)}
          />
        </div>
      </section>
    </div>
  );
}
