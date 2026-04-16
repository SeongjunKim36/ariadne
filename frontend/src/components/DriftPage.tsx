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
          <p className="drift-bucket-copy">{items.length} items</p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="drift-empty-card">No items in this category.</div>
      ) : (
        <div className="drift-item-list">
          {items.map((item) => (
            <article key={`${title}-${item.terraformAddress ?? item.arn ?? item.name}`} className="drift-item-card" data-status={item.status.toLowerCase()}>
              <div className="drift-item-head">
                <div>
                  <p className="drift-item-title">{item.name ?? item.terraformAddress ?? item.resourceId ?? 'Unnamed item'}</p>
                  <p className="drift-item-meta">{item.resourceType ?? 'UNKNOWN'} · {item.status}</p>
                </div>
                {item.arn ? (
                  <button type="button" className="timeline-link-button" onClick={() => onOpenTopology(item.arn!)}>
                    <span>Topology</span>
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
      setRunError(error instanceof Error ? error.message : 'Failed to run Terraform drift detection');
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
          <p className="timeline-eyebrow">Phase 4 Drift</p>
          <h2>Terraform state versus the live graph</h2>
          <p className="timeline-copy">
            Feed in a state file path or raw state JSON and Ariadne will compare it with the latest graph snapshot.
          </p>
        </div>
        <button type="button" className="audit-run-button" onClick={handleRun} disabled={isRunning}>
          {isRunning ? <LoaderCircle size={16} className="spin" /> : <GitCompareArrows size={16} />}
          <span>{isRunning ? 'Detecting drift…' : 'Run drift detection'}</span>
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
            Local path
          </button>
          <button type="button" className={mode === 'inline' ? 'active' : ''} onClick={() => setMode('inline')}>
            Inline JSON
          </button>
        </div>

        {mode === 'path' ? (
          <label className="drift-input-group">
            <span>State file path</span>
            <input
              type="text"
              value={statePath}
              onChange={(event) => setStatePath(event.target.value)}
              placeholder="/absolute/path/to/terraform.tfstate"
            />
          </label>
        ) : (
          <label className="drift-input-group">
            <span>Raw Terraform state JSON</span>
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
          <span>Total items</span>
          <strong>{latestDrift?.totalItems ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>Missing</span>
          <strong>{latestDrift?.missingCount ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>Modified</span>
          <strong>{latestDrift?.modifiedCount ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>Unmanaged</span>
          <strong>{latestDrift?.unmanagedCount ?? 0}</strong>
        </div>
      </section>

      {latestDrift ? (
        <div className="drift-meta-card">
          <FileJson2 size={16} />
          <span>
            Last run from <strong>{latestDrift.sourceName}</strong> · {new Date(latestDrift.generatedAt).toLocaleString()}
          </span>
        </div>
      ) : (
        <div className="drift-empty-card">No drift report yet.</div>
      )}

      <div className="drift-layout">
        <DriftBucket title="Missing from AWS" items={grouped.missing} onOpenTopology={openTopology} />
        <DriftBucket title="Modified in AWS" items={grouped.modified} onOpenTopology={openTopology} />
        <DriftBucket title="Unmanaged resources" items={grouped.unmanaged} onOpenTopology={openTopology} />
      </div>
    </div>
  );
}
