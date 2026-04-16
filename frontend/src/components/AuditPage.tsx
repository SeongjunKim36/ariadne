import { useMemo, useState } from 'react';
import { formatDistanceToNow } from 'date-fns';
import { ArrowRight, LoaderCircle, ShieldAlert, Sparkles, TriangleAlert } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import useSWR from 'swr';

import {
  fetchAuditExplanation,
  fetchAuditFindings,
  fetchAuditRules,
  fetchLatestAudit,
  runAudit,
} from '../lib/api';
import type { AuditFinding, RiskLevel } from '../lib/types';

const CATEGORY_OPTIONS = [
  { id: 'all', label: 'All' },
  { id: 'security-group', label: 'SG' },
  { id: 'iam', label: 'IAM' },
  { id: 'network', label: 'Network' },
  { id: 's3', label: 'S3' },
  { id: 'encryption', label: 'Encryption' },
];

function riskTone(level: RiskLevel) {
  switch (level) {
    case 'HIGH':
      return 'high';
    case 'MEDIUM':
      return 'medium';
    case 'LOW':
      return 'low';
    default:
      return 'low';
  }
}

export function AuditPage() {
  const navigate = useNavigate();
  const [selectedCategory, setSelectedCategory] = useState('all');
  const [selectedLevel, setSelectedLevel] = useState<RiskLevel | 'ALL'>('ALL');
  const [selectedFindingId, setSelectedFindingId] = useState<number | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  const { data: latestAudit, mutate: mutateLatestAudit } = useSWR('audit-latest', fetchLatestAudit, {
    shouldRetryOnError: false,
  });
  const { data: findings, mutate: mutateFindings } = useSWR(
    ['audit-findings', selectedLevel, selectedCategory],
    ([, level, category]) => fetchAuditFindings(level === 'ALL' ? undefined : level, category === 'all' ? undefined : category),
    { shouldRetryOnError: false },
  );
  const { data: rules } = useSWR('audit-rules', fetchAuditRules, { shouldRetryOnError: false });
  const { data: explanation, mutate: mutateExplanation } = useSWR(
    latestAudit ? 'audit-explanation' : null,
    fetchAuditExplanation,
    { shouldRetryOnError: false },
  );

  const selectedFinding = useMemo(
    () => (findings ?? []).find((finding) => finding.id === selectedFindingId) ?? (findings ?? [])[0] ?? null,
    [findings, selectedFindingId],
  );

  async function handleRunAudit() {
    setIsRunning(true);
    setRunError(null);
    try {
      const report = await runAudit();
      await mutateLatestAudit(report, { revalidate: false });
      await mutateFindings();
      await mutateExplanation();
      setSelectedFindingId(report.findings[0]?.id ?? null);
    } catch (error) {
      setRunError(error instanceof Error ? error.message : 'Audit run failed');
    } finally {
      setIsRunning(false);
    }
  }

  function openInTopology(finding: AuditFinding) {
    const related = finding.secondaryArn ? `&related=${encodeURIComponent(finding.secondaryArn)}` : '';
    navigate(`/?focus=${encodeURIComponent(finding.resourceArn)}${related}`);
  }

  return (
    <div className="audit-page">
      <section className="audit-hero">
        <div>
          <p className="audit-eyebrow">Phase 3 Audit</p>
          <h2>Rule-driven risk dashboard</h2>
          <p className="audit-copy">
            Deterministic checks run on the live graph first, then the explanation layer helps prioritize the fixes.
          </p>
        </div>
        <button type="button" className="audit-run-button" onClick={handleRunAudit} disabled={isRunning}>
          {isRunning ? <LoaderCircle size={16} className="spin" /> : <ShieldAlert size={16} />}
          <span>{isRunning ? 'Running audit…' : 'Run audit'}</span>
        </button>
      </section>

      {runError ? (
        <div className="audit-banner bad">
          <TriangleAlert size={16} />
          <span>{runError}</span>
        </div>
      ) : null}

      {latestAudit ? (
        <p className="audit-meta">
          Last run {formatDistanceToNow(new Date(latestAudit.runAt), { addSuffix: true })} · {latestAudit.totalFindings} findings
        </p>
      ) : (
        <p className="audit-meta">No audit report yet. Run the first audit to populate findings.</p>
      )}

      <section className="audit-summary-grid">
        <div className="audit-summary-card high">
          <span>HIGH</span>
          <strong>{latestAudit?.highCount ?? 0}</strong>
        </div>
        <div className="audit-summary-card medium">
          <span>MEDIUM</span>
          <strong>{latestAudit?.mediumCount ?? 0}</strong>
        </div>
        <div className="audit-summary-card low">
          <span>LOW</span>
          <strong>{latestAudit?.lowCount ?? 0}</strong>
        </div>
        <div className="audit-summary-card neutral">
          <span>Rules</span>
          <strong>{rules?.length ?? 0}</strong>
        </div>
      </section>

      <section className="audit-filter-row">
        <div className="audit-category-tabs">
          {CATEGORY_OPTIONS.map((option) => (
            <button
              key={option.id}
              type="button"
              className={selectedCategory === option.id ? 'active' : ''}
              onClick={() => setSelectedCategory(option.id)}
            >
              {option.label}
            </button>
          ))}
        </div>

        <div className="audit-level-tabs">
          {(['ALL', 'HIGH', 'MEDIUM', 'LOW'] as const).map((level) => (
            <button
              key={level}
              type="button"
              className={selectedLevel === level ? 'active' : ''}
              onClick={() => setSelectedLevel(level)}
            >
              {level}
            </button>
          ))}
        </div>
      </section>

      <div className="audit-layout">
        <section className="audit-findings-panel">
          <div className="audit-panel-header">
            <div>
              <p className="audit-panel-title">Findings</p>
              <p className="audit-panel-copy">Click a finding to inspect the remediation and jump back to topology.</p>
            </div>
          </div>

          <div className="audit-finding-list">
            {(findings ?? []).length === 0 ? (
              <div className="audit-empty-state">
                <ShieldAlert size={18} />
                <p>No findings match the current filter.</p>
              </div>
            ) : (
              (findings ?? []).map((finding) => (
                <button
                  key={finding.id}
                  type="button"
                  className="audit-finding-card"
                  data-selected={selectedFinding?.id === finding.id}
                  data-tone={riskTone(finding.riskLevel)}
                  onClick={() => setSelectedFindingId(finding.id)}
                >
                  <div className="audit-finding-header">
                    <span className="audit-risk-pill" data-tone={riskTone(finding.riskLevel)}>
                      {finding.riskLevel}
                    </span>
                    <span className="audit-rule-pill">{finding.ruleId}</span>
                  </div>
                  <p className="audit-finding-title">{finding.resourceName}</p>
                  <p className="audit-finding-copy">{finding.ruleName}</p>
                  <p className="audit-finding-caption">{finding.category} · {finding.resourceType}</p>
                </button>
              ))
            )}
          </div>
        </section>

        <aside className="audit-detail-panel">
          {selectedFinding ? (
            <>
              <div className="audit-panel-header">
                <div>
                  <p className="audit-panel-title">{selectedFinding.ruleName}</p>
                  <p className="audit-panel-copy">{selectedFinding.resourceName} · {selectedFinding.resourceType}</p>
                </div>
                <button type="button" className="audit-link-button" onClick={() => openInTopology(selectedFinding)}>
                  <span>Open on topology</span>
                  <ArrowRight size={15} />
                </button>
              </div>

              <div className="audit-detail-block">
                <span className="audit-detail-label">Risk</span>
                <div className="audit-inline-pills">
                  <span className="audit-risk-pill" data-tone={riskTone(selectedFinding.riskLevel)}>
                    {selectedFinding.riskLevel}
                  </span>
                  <span className="audit-rule-pill">{selectedFinding.ruleId}</span>
                </div>
              </div>

              {selectedFinding.secondaryName || selectedFinding.detail ? (
                <div className="audit-detail-block">
                  <span className="audit-detail-label">Evidence</span>
                  <p>{[selectedFinding.secondaryName, selectedFinding.detail].filter(Boolean).join(' · ')}</p>
                </div>
              ) : null}

              <div className="audit-detail-block">
                <span className="audit-detail-label">Remediation</span>
                <p>{selectedFinding.remediationHint}</p>
              </div>

              {explanation ? (
                <div className="audit-explanation-card">
                  <div className="audit-explanation-header">
                    <Sparkles size={16} />
                    <span>Audit explanation</span>
                  </div>
                  <p>{explanation.summary}</p>
                  {explanation.priorities.length > 0 ? (
                    <div className="audit-explanation-list">
                      {explanation.priorities.map((priority) => (
                        <span key={priority} className="audit-inline-chip">{priority}</span>
                      ))}
                    </div>
                  ) : null}
                  {explanation.actions.length > 0 ? (
                    <ul className="audit-action-list">
                      {explanation.actions.map((action) => (
                        <li key={action}>{action}</li>
                      ))}
                    </ul>
                  ) : null}
                </div>
              ) : null}
            </>
          ) : (
            <div className="audit-empty-state">
              <ShieldAlert size={18} />
              <p>Select a finding to review the remediation path.</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
