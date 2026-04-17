import { useMemo, useState } from 'react';
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
import { formatRelativeTime } from '../lib/uiCopy';

const CATEGORY_OPTIONS = [
  { id: 'all', label: '전체' },
  { id: 'security-group', label: '보안 그룹' },
  { id: 'iam', label: 'IAM' },
  { id: 'network', label: '네트워크' },
  { id: 's3', label: 'S3' },
  { id: 'encryption', label: '암호화' },
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

function riskLabel(level: RiskLevel) {
  switch (level) {
    case 'HIGH':
      return '높음';
    case 'MEDIUM':
      return '중간';
    case 'LOW':
      return '낮음';
    default:
      return level;
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
      setRunError(error instanceof Error ? error.message : '보안 점검 실행에 실패했습니다.');
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
          <p className="audit-eyebrow">보안 점검</p>
          <h2>규칙 기반 위험 대시보드</h2>
          <p className="audit-copy">
            현재 그래프에 규칙 기반 점검을 먼저 실행하고, 설명 레이어가 우선 조치 대상을 정리해 줍니다.
          </p>
        </div>
        <button type="button" className="audit-run-button" onClick={handleRunAudit} disabled={isRunning}>
          {isRunning ? <LoaderCircle size={16} className="spin" /> : <ShieldAlert size={16} />}
          <span>{isRunning ? '점검 실행 중…' : '보안 점검 실행'}</span>
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
          마지막 실행 {formatRelativeTime(latestAudit.runAt)} · 총 {latestAudit.totalFindings}건
        </p>
      ) : (
        <p className="audit-meta">아직 점검 리포트가 없습니다. 첫 점검을 실행해 결과를 채워 주세요.</p>
      )}

      <section className="audit-summary-grid">
        <div className="audit-summary-card high">
          <span>높음</span>
          <strong>{latestAudit?.highCount ?? 0}</strong>
        </div>
        <div className="audit-summary-card medium">
          <span>중간</span>
          <strong>{latestAudit?.mediumCount ?? 0}</strong>
        </div>
        <div className="audit-summary-card low">
          <span>낮음</span>
          <strong>{latestAudit?.lowCount ?? 0}</strong>
        </div>
        <div className="audit-summary-card neutral">
          <span>규칙 수</span>
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
              {level === 'ALL' ? '전체' : level === 'HIGH' ? '높음' : level === 'MEDIUM' ? '중간' : '낮음'}
            </button>
          ))}
        </div>
      </section>

      <div className="audit-layout">
        <section className="audit-findings-panel">
          <div className="audit-panel-header">
            <div>
              <p className="audit-panel-title">점검 결과</p>
              <p className="audit-panel-copy">항목을 클릭하면 근거와 대응 방향을 보고 인프라 맵으로 이동할 수 있습니다.</p>
            </div>
          </div>

          <div className="audit-finding-list">
            {(findings ?? []).length === 0 ? (
              <div className="audit-empty-state">
                <ShieldAlert size={18} />
                <p>현재 필터에 맞는 점검 결과가 없습니다.</p>
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
                      {riskLabel(finding.riskLevel)}
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
                  <span>인프라 맵에서 보기</span>
                  <ArrowRight size={15} />
                </button>
              </div>

              <div className="audit-detail-block">
                <span className="audit-detail-label">위험도</span>
                <div className="audit-inline-pills">
                  <span className="audit-risk-pill" data-tone={riskTone(selectedFinding.riskLevel)}>
                    {riskLabel(selectedFinding.riskLevel)}
                  </span>
                  <span className="audit-rule-pill">{selectedFinding.ruleId}</span>
                </div>
              </div>

              {selectedFinding.secondaryName || selectedFinding.detail ? (
                <div className="audit-detail-block">
                  <span className="audit-detail-label">근거</span>
                  <p>{[selectedFinding.secondaryName, selectedFinding.detail].filter(Boolean).join(' · ')}</p>
                </div>
              ) : null}

              <div className="audit-detail-block">
                <span className="audit-detail-label">대응 가이드</span>
                <p>{selectedFinding.remediationHint}</p>
              </div>

              {explanation ? (
                <div className="audit-explanation-card">
                  <div className="audit-explanation-header">
                    <Sparkles size={16} />
                    <span>설명 요약</span>
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
              <p>점검 결과를 선택하면 대응 경로를 자세히 볼 수 있습니다.</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}
