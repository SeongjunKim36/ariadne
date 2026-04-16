import { useMemo, useState } from 'react';
import {
  Bot,
  BrainCircuit,
  LoaderCircle,
  Search,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
} from 'lucide-react';
import ReactFlow, { Background, Controls, MiniMap, ReactFlowProvider } from 'reactflow';
import { useNavigate } from 'react-router-dom';
import useSWR from 'swr';
import 'reactflow/dist/style.css';

import {
  fetchLabels,
  fetchQueryExamples,
  generateArchitectureSummary,
  generateLabels,
  runNlQuery,
} from '../lib/api';
import { buildTopologyElements } from '../lib/layout';
import type {
  ArchitectureSummaryResponse,
  NlQueryResponse,
} from '../lib/types';
import { topologyNodeTypes } from './topologyNodes';

function value(record: Record<string, unknown>, key: string) {
  const raw = record[key];
  return typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean'
    ? String(raw)
    : '';
}

function resourceArnFromRow(row: Record<string, unknown>) {
  const candidates = ['resourceArn', 'arn', 'databaseArn', 'sourceArn', 'targetArn'];
  for (const candidate of candidates) {
    const resolved = value(row, candidate);
    if (resolved.startsWith('arn:aws:')) {
      return resolved;
    }
  }
  return null;
}

function resultTitle(row: Record<string, unknown>) {
  return value(row, 'resourceName')
    || value(row, 'name')
    || value(row, 'databaseName')
    || value(row, 'environment')
    || 'Result';
}

function resultCaption(row: Record<string, unknown>) {
  const bits = [
    value(row, 'resourceType'),
    value(row, 'environment'),
    value(row, 'state') || value(row, 'status'),
  ].filter(Boolean);
  return bits.join(' · ');
}

function resultDetail(row: Record<string, unknown>) {
  const detailBits = Object.entries(row)
    .filter(([key]) => !['resourceArn', 'resourceName', 'resourceType', 'environment', 'state', 'status'].includes(key))
    .map(([key, raw]) => `${key}: ${Array.isArray(raw) ? raw.join(', ') : String(raw)}`)
    .slice(0, 3);
  return detailBits.join(' · ');
}

function QuerySubgraph({
  response,
  selectedArn,
}: {
  response: NlQueryResponse | null;
  selectedArn: string | null;
}) {
  const nodeTypes = useMemo(() => topologyNodeTypes, []);
  const graph = response?.subgraph;
  const nodesById = new Map((graph?.nodes ?? []).map((node) => [node.id, node]));
  const emphasizedIds = new Set<string>();
  if (selectedArn) {
    emphasizedIds.add(selectedArn);
  }
  const flow = buildTopologyElements(graph?.nodes ?? [], graph?.edges ?? [], selectedArn, emphasizedIds);

  if (!graph || graph.nodes.length === 0) {
    return (
      <div className="query-subgraph-empty">
        <Bot size={18} />
        <p>This query returned a tabular answer without a focused subgraph.</p>
      </div>
    );
  }

  return (
    <div className="query-subgraph-shell">
      <ReactFlowProvider>
        <ReactFlow
          className="query-subgraph-flow"
          nodes={flow.nodes}
          edges={flow.edges}
          nodeTypes={nodeTypes}
          fitView
          nodesDraggable={false}
          nodesConnectable={false}
          minZoom={0.35}
          maxZoom={1.7}
        >
          <MiniMap pannable zoomable />
          <Controls showInteractive={false} />
          <Background gap={18} size={1} color="#d7dce5" />
        </ReactFlow>
      </ReactFlowProvider>
      <div className="query-subgraph-meta">
        <span>{graph.metadata.totalNodes} nodes</span>
        <span>{graph.metadata.totalEdges} edges</span>
        {selectedArn && nodesById.has(selectedArn) ? <span>focused: {value(nodesById.get(selectedArn)!.data, 'name') || selectedArn}</span> : null}
      </div>
    </div>
  );
}

export function QueryPage() {
  const navigate = useNavigate();
  const { data: examples } = useSWR('query-examples', fetchQueryExamples, { shouldRetryOnError: false });
  const { data: labels, mutate: mutateLabels } = useSWR('labels-current', fetchLabels, { shouldRetryOnError: false });

  const [queryInput, setQueryInput] = useState('prod에 뭐가 돌아가고 있어?');
  const [queryResponse, setQueryResponse] = useState<NlQueryResponse | null>(null);
  const [selectedArn, setSelectedArn] = useState<string | null>(null);
  const [queryError, setQueryError] = useState<string | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [isGeneratingLabels, setIsGeneratingLabels] = useState(false);
  const [labelMessage, setLabelMessage] = useState<string | null>(null);
  const [summary, setSummary] = useState<ArchitectureSummaryResponse | null>(null);
  const [summaryLanguage, setSummaryLanguage] = useState<'ko' | 'en'>('ko');
  const [isGeneratingSummary, setIsGeneratingSummary] = useState(false);

  async function handleRunQuery(queryText = queryInput) {
    setIsRunning(true);
    setQueryError(null);
    try {
      const response = await runNlQuery(queryText);
      setQueryResponse(response);
      const firstArn = response.results
        .map(resourceArnFromRow)
        .find((candidate): candidate is string => Boolean(candidate));
      setSelectedArn(firstArn ?? response.clarificationOptions[0]?.arn ?? null);
    } catch (error) {
      setQueryError(error instanceof Error ? error.message : 'Query execution failed');
      setQueryResponse(null);
      setSelectedArn(null);
    } finally {
      setIsRunning(false);
    }
  }

  async function handleGenerateLabels() {
    setIsGeneratingLabels(true);
    setLabelMessage(null);
    try {
      const generated = await generateLabels();
      await mutateLabels(generated, { revalidate: false });
      setLabelMessage(`${generated.length} labels are now available on the topology.`);
    } catch (error) {
      setLabelMessage(error instanceof Error ? error.message : 'Tier labels could not be generated.');
    } finally {
      setIsGeneratingLabels(false);
    }
  }

  async function handleGenerateSummary() {
    setIsGeneratingSummary(true);
    try {
      const nextSummary = await generateArchitectureSummary(summaryLanguage);
      setSummary(nextSummary);
    } catch (error) {
      setSummary({
        summary: error instanceof Error ? error.message : 'Architecture summary generation failed.',
        language: summaryLanguage,
        generatedAt: new Date().toISOString(),
      });
    } finally {
      setIsGeneratingSummary(false);
    }
  }

  function openInTopology(arn: string) {
    navigate(`/?focus=${encodeURIComponent(arn)}`);
  }

  function applyExample(example: string) {
    setQueryInput(example);
    void handleRunQuery(example);
  }

  const labelCoverage = labels?.length ?? 0;

  return (
    <div className="query-page">
      <section className="query-hero">
        <div>
          <p className="query-eyebrow">Phase 3 Query Layer</p>
          <h2>Ask the graph in operator language</h2>
          <p className="query-copy">
            Natural-language questions run through schema-aware Cypher validation first, then return a concise answer and subgraph.
          </p>
        </div>
        <div className="query-hero-actions">
          <button type="button" className="ghost-button" onClick={() => void handleGenerateLabels()} disabled={isGeneratingLabels}>
            {isGeneratingLabels ? <LoaderCircle size={14} className="spin" /> : <BrainCircuit size={14} />}
            {isGeneratingLabels ? 'Generating tiers…' : `Generate tiers (${labelCoverage})`}
          </button>
          <button type="button" className="ghost-button primary-button" onClick={() => void handleGenerateSummary()} disabled={isGeneratingSummary}>
            {isGeneratingSummary ? <LoaderCircle size={14} className="spin" /> : <Sparkles size={14} />}
            {isGeneratingSummary ? 'Summarizing…' : 'Generate summary'}
          </button>
        </div>
      </section>

      <section className="query-input-card">
        <div className="query-input-shell">
          <Search size={16} />
          <input
            value={queryInput}
            onChange={(event) => setQueryInput(event.target.value)}
            placeholder="예: 0.0.0.0/0으로 열린 SG 중 위험한 거?"
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                void handleRunQuery();
              }
            }}
          />
        </div>
        <button type="button" className="query-run-button" onClick={() => void handleRunQuery()} disabled={isRunning}>
          {isRunning ? <LoaderCircle size={15} className="spin" /> : <Bot size={15} />}
          <span>{isRunning ? 'Running…' : 'Run query'}</span>
        </button>
      </section>

      <section className="query-example-list">
        {(examples ?? []).map((example) => (
          <button key={example} type="button" className="query-example-chip" onClick={() => applyExample(example)}>
            {example}
          </button>
        ))}
      </section>

      {labelMessage ? (
        <div className="query-banner" data-tone="info">
          <ShieldCheck size={16} />
          <span>{labelMessage}</span>
        </div>
      ) : null}

      {queryError ? (
        <div className="query-banner" data-tone="bad">
          <TriangleAlert size={16} />
          <span>{queryError}</span>
        </div>
      ) : null}

      {queryResponse?.error ? (
        <div className="query-banner" data-tone="bad">
          <TriangleAlert size={16} />
          <span>{queryResponse.error}</span>
        </div>
      ) : null}

      <div className="query-layout">
        <section className="query-results-panel">
          <div className="query-panel-header">
            <div>
              <p className="query-panel-title">Result summary</p>
              <p className="query-panel-copy">
                {queryResponse?.explanation ?? 'Run one of the example queries or ask a question in Korean or English.'}
              </p>
            </div>
            {queryResponse?.truncated ? (
              <span className="query-summary-pill">top {queryResponse.totalEstimate}</span>
            ) : null}
          </div>

          {queryResponse?.generatedCypher ? (
            <div className="query-cypher-card">
              <span className="query-card-label">Generated Cypher</span>
              <pre>{queryResponse.generatedCypher}</pre>
            </div>
          ) : null}

          {queryResponse?.clarificationNeeded ? (
            <div className="query-clarification-card">
              <span className="query-card-label">Clarification</span>
              <div className="query-clarification-list">
                {queryResponse.clarificationOptions.map((option) => (
                  <button
                    key={option.arn}
                    type="button"
                    className="query-clarification-option"
                    onClick={() => openInTopology(option.arn)}
                  >
                    <strong>{option.name}</strong>
                    <span>{[option.resourceType, option.environment].filter(Boolean).join(' · ')}</span>
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          <div className="query-result-list">
            {(queryResponse?.results ?? []).length === 0 ? (
              <div className="query-empty-state">
                <Bot size={18} />
                <p>No query results yet.</p>
                <span>Try “prod vs staging 차이가 뭐야?” or “dongne-prod-db를 쓰는 서비스 보여줘”.</span>
              </div>
            ) : (
              queryResponse?.results.map((row, index) => {
                const arn = resourceArnFromRow(row);
                return (
                  <button
                    key={`${arn ?? resultTitle(row)}-${index}`}
                    type="button"
                    className="query-result-card"
                    data-selected={arn != null && arn === selectedArn}
                    onClick={() => setSelectedArn(arn)}
                  >
                    <div className="query-result-copy">
                      <p className="query-result-title">{resultTitle(row)}</p>
                      <p className="query-result-caption">{resultCaption(row) || 'graph result'}</p>
                      {resultDetail(row) ? <p className="query-result-detail">{resultDetail(row)}</p> : null}
                    </div>
                    {arn ? (
                      <span
                        className="query-result-link"
                        onClick={(event) => {
                          event.stopPropagation();
                          openInTopology(arn);
                        }}
                      >
                        Open
                      </span>
                    ) : null}
                  </button>
                );
              })
            )}
          </div>
        </section>

        <aside className="query-side-panel">
          <section className="query-summary-card">
            <div className="query-panel-header">
              <div>
                <p className="query-panel-title">Architecture summary</p>
                <p className="query-panel-copy">Generate a Korean or English overview from the current graph.</p>
              </div>
              <select value={summaryLanguage} onChange={(event) => setSummaryLanguage(event.target.value as 'ko' | 'en')}>
                <option value="ko">한국어</option>
                <option value="en">English</option>
              </select>
            </div>
            <p>{summary?.summary ?? 'No summary generated yet. Use the button above when you want a one-shot overview.'}</p>
          </section>

          <section className="query-summary-card">
            <div className="query-panel-header">
              <div>
                <p className="query-panel-title">Tier labels</p>
                <p className="query-panel-copy">Generated labels appear on topology cards and tier filters.</p>
              </div>
            </div>
            <div className="query-tier-list">
              {(labels ?? []).length === 0 ? (
                <p className="query-side-copy">No tier labels yet.</p>
              ) : (
                labels?.slice(0, 8).map((label) => (
                  <div key={label.arn} className="query-tier-row">
                    <span className="query-tier-pill" data-tier={label.tier}>{label.tier}</span>
                    <span>{label.source} · {label.confidenceScore.toFixed(2)}</span>
                  </div>
                ))
              )}
            </div>
          </section>
        </aside>
      </div>

      <section className="query-subgraph-panel">
        <div className="query-panel-header">
          <div>
            <p className="query-panel-title">Subgraph</p>
            <p className="query-panel-copy">Query matches are narrowed to a one-hop subgraph for quick visual confirmation.</p>
          </div>
        </div>
        <QuerySubgraph response={queryResponse} selectedArn={selectedArn} />
      </section>
    </div>
  );
}
