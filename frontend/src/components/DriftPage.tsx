import { useMemo, useState } from 'react';
import { ArrowRight, FileJson2, GitCompareArrows, LoaderCircle, TriangleAlert } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import useSWR from 'swr';

import { fetchLatestTerraformDrift, runTerraformDrift } from '../lib/api';
import type { DriftItemResponse } from '../lib/types';

function DriftBucket({
  title,
  items,
  onOpenTopology,
}: {
  title: string;
  items: DriftItemResponse[];
  onOpenTopology: (arn: string) => void;
}) {
  return (
    <section className="drift-bucket">
      <div className="drift-bucket-header">
        <div>
          <p className="drift-bucket-title">{title}</p>
          <p className="drift-bucket-copy">{items.length}개 항목</p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="drift-empty-card">이 범주에는 항목이 없습니다.</div>
      ) : (
        <div className="drift-item-list">
          {items.map((item) => (
            <article key={`${title}-${item.terraformAddress ?? item.arn ?? item.name}`} className="drift-item-card" data-status={item.status.toLowerCase()}>
              <div className="drift-item-head">
                <div>
                  <p className="drift-item-title">{item.name ?? item.terraformAddress ?? item.resourceId ?? '이름 없는 항목'}</p>
                  <p className="drift-item-meta">{item.resourceType ?? '알 수 없음'} · {item.status}</p>
                </div>
                {item.arn ? (
                  <button type="button" className="timeline-link-button" onClick={() => onOpenTopology(item.arn!)}>
                    <span>인프라 맵</span>
                    <ArrowRight size={14} />
                  </button>
                ) : null}
              </div>
              <p className="drift-item-copy">{item.summary}</p>
              {Object.keys(item.propertyChanges).length > 0 ? (
                <div className="timeline-property-list compact">
                  {Object.entries(item.propertyChanges).slice(0, 6).map(([key, change]) => (
                    <div key={key} className="timeline-property-row">
                      <span>{key}</span>
                      <code>{String(change.beforeValue ?? '∅')}</code>
                      <ArrowRight size={12} />
                      <code>{String(change.afterValue ?? '∅')}</code>
                    </div>
                  ))}
                </div>
              ) : null}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

export function DriftPage() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<'path' | 'inline'>('path');
  const [statePath, setStatePath] = useState('');
  const [rawStateJson, setRawStateJson] = useState('');
  const [runError, setRunError] = useState<string | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const { data: latestDrift, mutate } = useSWR('terraform-drift-latest', fetchLatestTerraformDrift, {
    shouldRetryOnError: false,
  });

  const grouped = useMemo(() => {
    const items = latestDrift?.items ?? [];
    return {
      missing: items.filter((item) => item.status === 'MISSING'),
      modified: items.filter((item) => item.status === 'MODIFIED'),
      unmanaged: items.filter((item) => item.status === 'UNMANAGED'),
    };
  }, [latestDrift]);

  async function handleRun() {
    setIsRunning(true);
    setRunError(null);
    try {
      const report = await runTerraformDrift(
        mode === 'path'
          ? { path: statePath.trim() || undefined }
          : { rawStateJson: rawStateJson.trim() || undefined },
      );
      await mutate(report, { revalidate: false });
    } catch (error) {
      setRunError(error instanceof Error ? error.message : 'Terraform 드리프트 탐지에 실패했습니다.');
    } finally {
      setIsRunning(false);
    }
  }

  function openTopology(arn: string) {
    navigate(`/?focus=${encodeURIComponent(arn)}`);
  }

  return (
    <div className="drift-page">
      <section className="drift-hero">
        <div>
          <p className="timeline-eyebrow">Terraform 드리프트</p>
          <h2>Terraform 상태와 실제 그래프 비교</h2>
          <p className="timeline-copy">
            상태 파일 경로나 raw state JSON을 입력하면 최신 그래프 스냅샷과 나란히 비교합니다.
          </p>
        </div>
        <button type="button" className="audit-run-button" onClick={handleRun} disabled={isRunning}>
          {isRunning ? <LoaderCircle size={16} className="spin" /> : <GitCompareArrows size={16} />}
          <span>{isRunning ? '탐지 중…' : '드리프트 탐지 실행'}</span>
        </button>
      </section>

      {runError ? (
        <div className="audit-banner bad">
          <TriangleAlert size={16} />
          <span>{runError}</span>
        </div>
      ) : null}

      <section className="drift-config-panel">
        <div className="drift-mode-tabs">
          <button type="button" className={mode === 'path' ? 'active' : ''} onClick={() => setMode('path')}>
            로컬 경로
          </button>
          <button type="button" className={mode === 'inline' ? 'active' : ''} onClick={() => setMode('inline')}>
            JSON 직접 입력
          </button>
        </div>

        {mode === 'path' ? (
          <label className="drift-input-group">
            <span>상태 파일 경로</span>
            <input
              type="text"
              value={statePath}
              onChange={(event) => setStatePath(event.target.value)}
              placeholder="/absolute/path/to/terraform.tfstate"
            />
          </label>
        ) : (
          <label className="drift-input-group">
            <span>Terraform state JSON</span>
            <textarea
              value={rawStateJson}
              onChange={(event) => setRawStateJson(event.target.value)}
              placeholder='{"resources":[...]}'
              rows={10}
            />
          </label>
        )}
      </section>

      <section className="timeline-summary-grid">
        <div className="timeline-summary-card">
          <span>전체 항목</span>
          <strong>{latestDrift?.totalItems ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>AWS에 없음</span>
          <strong>{latestDrift?.missingCount ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>AWS에서 변경됨</span>
          <strong>{latestDrift?.modifiedCount ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>미관리 리소스</span>
          <strong>{latestDrift?.unmanagedCount ?? 0}</strong>
        </div>
      </section>

      {latestDrift ? (
        <div className="drift-meta-card">
          <FileJson2 size={16} />
          <span>
            마지막 실행 <strong>{latestDrift.sourceName}</strong> · {new Date(latestDrift.generatedAt).toLocaleString()}
          </span>
        </div>
      ) : (
        <div className="drift-empty-card">아직 드리프트 리포트가 없습니다.</div>
      )}

      <div className="drift-layout">
        <DriftBucket title="AWS에 없는 리소스" items={grouped.missing} onOpenTopology={openTopology} />
        <DriftBucket title="AWS에서 변경된 리소스" items={grouped.modified} onOpenTopology={openTopology} />
        <DriftBucket title="코드에 없는 리소스" items={grouped.unmanaged} onOpenTopology={openTopology} />
      </div>
    </div>
  );
}
