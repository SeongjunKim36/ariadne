import { startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react';
import { Filter, Focus, LoaderCircle, Search, Sparkles, TriangleAlert, X } from 'lucide-react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  ReactFlowProvider,
  type NodeMouseHandler,
} from 'reactflow';
import { useSearchParams } from 'react-router-dom';
import useSWR from 'swr';
import 'reactflow/dist/style.css';

import {
  fetchGraph,
  generateLabels,
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
import {
  formatNodeStatus,
  formatRelativeTime,
  formatScanStatusLabel,
  labelNodeType,
  labelTierBadge,
  summarizeWarningMessage,
} from '../lib/uiCopy';
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
    return '아직 이 작업공간에서 실행한 스캔이 없습니다.';
  }
  if (scan.status === 'RUNNING') {
    return '수집기를 실행 중입니다. 스캔이 끝나면 그래프를 자동으로 새로고칩니다.';
  }
  if (scan.completedAt) {
    return `${formatRelativeTime(scan.completedAt)}에 완료되었습니다.`;
  }
  return '스캔 기록은 있지만 완료 시각을 찾지 못했습니다.';
}

function preflightTone(preflight: ScanPreflightResponse | undefined) {
  if (!preflight) {
    return 'idle';
  }
  return preflight.ready ? 'good' : 'bad';
}

function preflightCopy(preflight: ScanPreflightResponse | undefined) {
  if (!preflight) {
    return '스캔 가능한 AWS 자격 증명을 확인하는 중입니다…';
  }
  if (preflight.ready) {
    return 'AWS 자격 증명이 준비되어 있어 바로 스캔할 수 있습니다.';
  }
  return 'AWS 자격 증명 또는 권한을 먼저 확인해 주세요.';
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
            <p className="detail-panel-title">리소스를 선택해 주세요</p>
            <p className="detail-panel-caption">노드를 클릭하면 태그, 속성, 연결 정보를 볼 수 있습니다.</p>
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
        <button type="button" className="ghost-icon-button" onClick={onClose} aria-label="상세 패널 닫기">
          <X size={16} />
        </button>
      </div>

      <section className="detail-panel-section">
        <p className="detail-section-label">속성</p>
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
        <p className="detail-section-label">연결</p>
        <div className="connection-list">
          {(resourceDetail?.connections ?? []).length === 0 ? (
            <p className="detail-panel-caption">현재 연결된 이웃 리소스가 없습니다.</p>
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
  const [searchParams, setSearchParams] = useSearchParams();
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
  const [selectedTier, setSelectedTier] = useState('');
  const [activeTypes, setActiveTypes] = useState<string[]>([]);
  const [showSecurityEdges, setShowSecurityEdges] = useState(true);
  const [showInferredEdges, setShowInferredEdges] = useState(true);
  const [selectedArn, setSelectedArn] = useState<string | null>(null);
  const [focusedArn, setFocusedArn] = useState<string | null>(null);
  const [activeScanId, setActiveScanId] = useState<string | null>(null);
  const [scanActionError, setScanActionError] = useState<string | null>(null);
  const [isGeneratingLabels, setIsGeneratingLabels] = useState(false);
  const [labelActionMessage, setLabelActionMessage] = useState<string | null>(null);

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
  const focusFromUrl = searchParams.get('focus');
  const relatedFromUrl = searchParams.get('related');
  const relatedIds = useMemo(
    () => (relatedFromUrl ? relatedFromUrl.split(',').map((entry) => entry.trim()).filter(Boolean) : []),
    [relatedFromUrl],
  );
  const nodeTypes = useMemo(() => topologyNodeTypes, []);

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

  useEffect(() => {
    if (!focusFromUrl) {
      return;
    }
    setSelectedArn(focusFromUrl);
    setFocusedArn(focusFromUrl);
  }, [focusFromUrl]);

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
  const tierOptions = Array.from(new Set(
    (graph?.nodes ?? [])
      .map((node) => value(node.data, 'tier'))
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
    const tierMatch = !selectedTier || value(node.data, 'tier') === selectedTier;
    const searchMatch = matchesSearch(node, deferredSearch);
    return typeMatch && envMatch && vpcMatch && tierMatch && searchMatch;
  });

  const visibleIds = new Set<string>();
  for (const node of matchedNodes) {
    visibleIds.add(node.id);
    for (const ancestorId of collectAncestorIds(node.id, nodesById)) {
      visibleIds.add(ancestorId);
    }
  }
  const cidrAllowed = activeTypes.length === 0 || activeTypes.includes('cidr');
  if (cidrAllowed && showSecurityEdges) {
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
  const filteredEdges = (graph?.edges ?? []).filter((edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target));
  const visibleEdges = filteredEdges.filter((edge) => {
    if (!showSecurityEdges && ['ALLOWS_FROM', 'ALLOWS_TO', 'ALLOWS_SELF', 'EGRESS_TO'].includes(edge.type)) {
      return false;
    }
    if (!showInferredEdges && edge.type === 'LIKELY_USES') {
      return false;
    }
    return true;
  });
  const hasFilters = Boolean(search || environment || selectedVpc || selectedTier || activeTypes.length > 0);
  const securityEdgeCount = filteredEdges.filter((edge) => ['ALLOWS_FROM', 'ALLOWS_TO', 'ALLOWS_SELF', 'EGRESS_TO'].includes(edge.type)).length;
  const publicExposureCount = filteredEdges.filter((edge) => {
    if (edge.type !== 'ALLOWS_TO') {
      return false;
    }
    const sourceNode = nodesById.get(edge.source);
    return sourceNode?.type === 'cidr' && value(sourceNode.data, 'isPublic') === 'true';
  }).length;
  const selfReferenceCount = filteredEdges.filter((edge) => edge.type === 'ALLOWS_SELF').length;
  const inferredLinkCount = filteredEdges.filter((edge) => edge.type === 'LIKELY_USES').length;

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
  for (const relatedId of relatedIds) {
    emphasizedIds.add(relatedId);
    for (const ancestorId of collectAncestorIds(relatedId, nodesById)) {
      emphasizedIds.add(ancestorId);
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
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      next.set('focus', node.id);
      next.delete('related');
      return next;
    }, { replace: true });
  };

  const onNodeDoubleClick: NodeMouseHandler = (_, node) => {
    const nextFocused = focusedArn === node.id ? null : node.id;
    setFocusedArn(nextFocused);
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      if (nextFocused == null) {
        next.delete('focus');
      } else {
        next.set('focus', nextFocused);
      }
      next.delete('related');
      return next;
    }, { replace: true });
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
        : '새 스캔을 시작하지 못했습니다.';
      setScanActionError(message);
    }
  };

  const generateTierLabels = async () => {
    setIsGeneratingLabels(true);
    setLabelActionMessage(null);
    try {
      const labels = await generateLabels();
      await mutate();
      setLabelActionMessage(`${labels.length}개의 티어 라벨을 적용했습니다.`);
    } catch (labelError) {
      const message = labelError instanceof Error
        ? labelError.message
        : '티어 라벨을 생성하지 못했습니다.';
      setLabelActionMessage(message);
    } finally {
      setIsGeneratingLabels(false);
    }
  };

  if (isLoading) {
    return (
      <div className="topology-loading">
        <LoaderCircle className="spin" size={18} />
        <span>최신 인프라 맵을 불러오는 중입니다…</span>
      </div>
    );
  }

  if (error || !graph) {
    return (
      <div className="topology-empty-state">
        <p className="topology-empty-title">그래프 API에 아직 연결되지 않았습니다.</p>
        <p className="topology-empty-copy">백엔드가 실행 중인지, 그리고 <code>/api/graph</code>를 열고 있는지 확인해 주세요.</p>
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
              placeholder="이름, 리소스 ID, 엔진, IP, 태그로 검색"
            />
          </div>
          <div className="toolbar-actions">
            <button type="button" className="ghost-button" onClick={() => mutate()}>
              그래프 새로고침
            </button>
            <button type="button" className="ghost-button" onClick={() => void generateTierLabels()} disabled={isGeneratingLabels}>
              {isGeneratingLabels ? <LoaderCircle className="spin" size={14} /> : <Sparkles size={14} />}
              {isGeneratingLabels ? '티어 생성 중…' : '티어 라벨 생성'}
            </button>
            <button
              type="button"
              className="ghost-button primary-button"
              onClick={() => void startScan()}
              disabled={!scanPreflight?.ready || scanIsRunning}
              data-testid="start-scan-button"
            >
              {scanIsRunning ? <LoaderCircle className="spin" size={14} /> : null}
              {scanIsRunning ? '스캔 중…' : '스캔 시작'}
            </button>
            {focusedArn ? (
              <button type="button" className="ghost-button" onClick={() => setFocusedArn(null)}>
                <Focus size={14} />
                포커스 해제
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
                {formatScanStatusLabel(currentScan?.status)}
              </span>
              <span className="status-pill" data-tone={preflightTone(scanPreflight)}>
                {scanPreflight?.ready ? 'AWS 준비됨' : 'AWS 확인 필요'}
              </span>
            </div>
            <p className="scan-status-copy">{scanStatusCopy(currentScan)}</p>
            <p className="scan-status-copy secondary">
              {preflightCopy(scanPreflight)}
            </p>
            <p className="scan-status-meta">
              {scanPreflight?.accountId
                ? `${scanPreflight.accountId} · ${scanPreflight.region}`
                : scanPreflight?.region ?? 'ap-northeast-2'}
              {currentScan ? ` · ${currentScan.totalNodes}개 노드 · ${currentScan.totalEdges}개 연결` : ''}
            </p>
          </div>

          <div className="scan-status-side">
            {currentScan?.errorMessage ? (
              <div className="scan-callout" data-tone="bad">
                <TriangleAlert size={16} />
                <span>{currentScan.errorMessage}</span>
              </div>
            ) : null}
            {summarizeWarningMessage(currentScan?.warningMessage) ? (
              <div className="scan-callout" data-tone="warn">
                <TriangleAlert size={16} />
                <span>{summarizeWarningMessage(currentScan?.warningMessage)}</span>
              </div>
            ) : null}
            {scanActionError ? (
              <div className="scan-callout" data-tone="bad">
                <TriangleAlert size={16} />
                <span>{scanActionError}</span>
              </div>
            ) : null}
            {summarizeWarningMessage(scanPreflight?.warningMessage) ? (
              <div className="scan-callout" data-tone="warn">
                <TriangleAlert size={16} />
                <span>{summarizeWarningMessage(scanPreflight?.warningMessage)}</span>
              </div>
            ) : null}
            {labelActionMessage ? (
              <div className="scan-callout" data-tone="warn">
                <Sparkles size={16} />
                <span>{labelActionMessage}</span>
              </div>
            ) : null}
          </div>
        </section>

        <div className="topology-content">
          <aside className="filter-panel">
            <div className="filter-panel-header">
              <Filter size={16} />
              <div>
                <p className="filter-panel-title">맵 필터</p>
                <p className="filter-panel-caption">현재 {visibleNodes.length}개 노드가 표시됩니다.</p>
              </div>
            </div>

            <label className="field">
              <span>환경</span>
              <select value={environment} onChange={(event) => setEnvironment(event.target.value)}>
                <option value="">전체 환경</option>
                {environmentOptions.map((entry) => (
                  <option key={entry} value={entry}>{formatNodeStatus(entry)}</option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>VPC</span>
              <select value={selectedVpc} onChange={(event) => setSelectedVpc(event.target.value)}>
                <option value="">전체 VPC</option>
                {vpcOptions.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>티어</span>
              <select value={selectedTier} onChange={(event) => setSelectedTier(event.target.value)}>
                <option value="">전체 티어</option>
                {tierOptions.map((option) => (
                  <option key={option} value={option}>{labelTierBadge(option)}</option>
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
                    {labelNodeType(type)}
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
                  setSelectedTier('');
                  setActiveTypes([]);
                  setShowSecurityEdges(true);
                  setShowInferredEdges(true);
                }}
              >
                필터 초기화
              </button>
            ) : null}

            <section className="security-panel">
              <div className="security-panel-header">
                <div>
                  <p className="security-panel-title">보안 관점</p>
                  <p className="filter-panel-caption">리소스 맵은 유지한 채 보안 규칙 연결만 선택적으로 볼 수 있습니다.</p>
                </div>
              </div>

              <div className="security-metrics">
                <div className="security-metric-card">
                  <span className="security-metric-value">{securityEdgeCount}</span>
                  <span className="security-metric-label">보안 규칙 연결</span>
                </div>
                <div className="security-metric-card danger">
                  <span className="security-metric-value">{publicExposureCount}</span>
                  <span className="security-metric-label">공개 노출</span>
                </div>
                <div className="security-metric-card">
                  <span className="security-metric-value">{selfReferenceCount}</span>
                  <span className="security-metric-label">자기 참조</span>
                </div>
              </div>

              <div className="security-toggle-list">
                <button
                  type="button"
                  className="type-chip"
                  data-active={showSecurityEdges}
                  onClick={() => setShowSecurityEdges((current) => !current)}
                >
                  보안 규칙 연결
                </button>
                <button
                  type="button"
                  className="type-chip"
                  data-active={showInferredEdges}
                  onClick={() => setShowInferredEdges((current) => !current)}
                >
                  추론된 DB 연결 ({inferredLinkCount})
                </button>
              </div>

              <div className="security-legend">
                <div className="security-legend-row">
                  <span className="security-swatch inbound" />
                  <span>인바운드 / 공개 유입</span>
                </div>
                <div className="security-legend-row">
                  <span className="security-swatch egress" />
                  <span>아웃바운드 / 외부 송신</span>
                </div>
                <div className="security-legend-row">
                  <span className="security-swatch inferred" />
                  <span>애플리케이션 ↔ DB 추론 연결</span>
                </div>
              </div>
            </section>

            <div className="filter-meta">
              <p>마지막 수집 {formatRelativeTime(graph.metadata.collectedAt)}</p>
              <p>{graph.metadata.totalNodes}개 노드 · {graph.metadata.totalEdges}개 연결 · {Math.round(graph.metadata.scanDurationMs / 100) / 10}초</p>
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
                  onlyRenderVisibleElements
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
                    <p>현재 필터에 맞는 노드가 없습니다.</p>
                    <span>조건을 한두 개만 풀면 인프라 맵이 다시 채워집니다.</span>
                  </div>
                ) : null}
              </>
            </ReactFlowProvider>
          </div>

            <DetailPanel
              resourceDetail={resourceDetail}
              fallbackNode={fallbackNode}
              onClose={() => {
                setSelectedArn(null);
                setFocusedArn(null);
                setSearchParams((current) => {
                  const next = new URLSearchParams(current);
                  next.delete('focus');
                  next.delete('related');
                  return next;
                }, { replace: true });
              }}
            />
        </div>
      </section>
    </div>
  );
}
