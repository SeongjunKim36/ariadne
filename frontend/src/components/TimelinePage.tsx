import { useEffect, useMemo, useState } from 'react';
import { formatDistanceToNow, subHours } from 'date-fns';
import { ArrowRight, GitBranchPlus, GitCommitVertical, GitPullRequestArrow, Layers3, LoaderCircle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import useSWR from 'swr';

import { fetchEventLogs, fetchLatestSnapshotDiff, fetchSnapshotDiff, fetchSnapshots, fetchTimeline } from '../lib/api';
import type { NodeDiffResponse } from '../lib/types';

const PERIOD_OPTIONS = [
  { id: '24h', label: '24h' },
  { id: '7d', label: '7d' },
  { id: '30d', label: '30d' },
  { id: 'custom', label: 'Custom' },
];

function toLocalInputValue(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function ChangeListSection({
  title,
  items,
  tone,
  onOpenTopology,
}: {
  title: string;
  items: NodeDiffResponse[];
  tone: 'added' | 'removed' | 'modified';
  onOpenTopology: (arn: string) => void;
}) {
  return (
    <section className="timeline-change-section">
      <div className="timeline-section-header">
        <div>
          <p className="timeline-section-title">{title}</p>
          <p className="timeline-section-copy">{items.length} resources</p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="timeline-empty-card">No changes in this bucket.</div>
      ) : (
        <div className="timeline-change-list">
          {items.map((item) => (
            <article key={`${title}-${item.arn ?? item.name ?? Math.random()}`} className="timeline-change-card" data-tone={tone}>
              <div className="timeline-change-card-head">
                <div>
                  <p className="timeline-change-title">{item.name ?? item.arn ?? 'Unnamed resource'}</p>
                  <p className="timeline-change-meta">{item.resourceType ?? 'UNKNOWN'} · {item.arn ?? 'no-arn'}</p>
                </div>
                {item.arn ? (
                  <button type="button" className="timeline-link-button" onClick={() => onOpenTopology(item.arn!)}>
                    <span>Topology</span>
                    <ArrowRight size={14} />
                  </button>
                ) : null}
              </div>
              {Object.keys(item.propertyChanges).length > 0 ? (
                <div className="timeline-property-list">
                  {Object.entries(item.propertyChanges).slice(0, 4).map(([key, change]) => (
                    <div key={key} className="timeline-property-row">
                      <span>{key}</span>
                      <code>{String(change.beforeValue ?? '∅')}</code>
                      <ArrowRight size={12} />
                      <code>{String(change.afterValue ?? '∅')}</code>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="timeline-change-copy">
                  {item.changeType === 'ADDED'
                    ? 'New resource appeared in the latest snapshot.'
                    : 'Resource disappeared from the latest snapshot.'}
                </p>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

export function TimelinePage() {
  const navigate = useNavigate();
  const [period, setPeriod] = useState('24h');
  const [customFrom, setCustomFrom] = useState(() => toLocalInputValue(subHours(new Date(), 24)));
  const [customTo, setCustomTo] = useState(() => toLocalInputValue(new Date()));

  useEffect(() => {
    if (period !== 'custom') {
      return;
    }
    if (customFrom && customTo) {
      return;
    }
    setCustomFrom(toLocalInputValue(subHours(new Date(), 24)));
    setCustomTo(toLocalInputValue(new Date()));
  }, [period, customFrom, customTo]);

  const range = useMemo(() => {
    if (period !== 'custom' || !customFrom || !customTo) {
      return undefined;
    }
    const fromDate = new Date(customFrom);
    const toDate = new Date(customTo);
    if (Number.isNaN(fromDate.getTime()) || Number.isNaN(toDate.getTime())) {
      return undefined;
    }
    return {
      from: fromDate.toISOString(),
      to: toDate.toISOString(),
    };
  }, [period, customFrom, customTo]);

  const { data: snapshots } = useSWR(['snapshots', period, range?.from ?? '', range?.to ?? ''], ([, currentPeriod, from, to]) => fetchSnapshots(currentPeriod, {
    from: from || undefined,
    to: to || undefined,
  }), {
    shouldRetryOnError: false,
  });
  const { data: timelineEntries } = useSWR(['timeline', period, range?.from ?? '', range?.to ?? ''], ([, currentPeriod, from, to]) => fetchTimeline(currentPeriod, {
    from: from || undefined,
    to: to || undefined,
  }), {
    shouldRetryOnError: false,
  });
  const { data: latestDiff } = useSWR(['snapshot-diff-latest'], () => fetchLatestSnapshotDiff(), {
    shouldRetryOnError: false,
  });
  const { data: eventLogs } = useSWR(['event-logs', range?.from ?? '', range?.to ?? ''], ([, from, to]) => fetchEventLogs({
    from: from || undefined,
    to: to || undefined,
  }), { shouldRetryOnError: false });

  const [selectedFrom, setSelectedFrom] = useState<number | null>(null);
  const [selectedTo, setSelectedTo] = useState<number | null>(null);

  useEffect(() => {
    if (!snapshots || snapshots.length < 2) {
      return;
    }
    const snapshotIds = new Set(snapshots.map((snapshot) => snapshot.id));
    if (
      selectedFrom != null &&
      selectedTo != null &&
      snapshotIds.has(selectedFrom) &&
      snapshotIds.has(selectedTo)
    ) {
      return;
    }
    setSelectedTo(snapshots[0]?.id ?? null);
    setSelectedFrom(snapshots[1]?.id ?? null);
  }, [snapshots, selectedFrom, selectedTo]);

  const { data: selectedDiff, isLoading: isDiffLoading } = useSWR(
    selectedFrom && selectedTo ? ['snapshot-diff', selectedFrom, selectedTo] : null,
    ([, from, to]) => fetchSnapshotDiff(from as number, to as number),
    { shouldRetryOnError: false },
  );

  const activeDiff = period === 'custom'
    ? selectedDiff ?? null
    : selectedDiff ?? latestDiff;
  const changeSlices = useMemo(() => ({
    added: activeDiff?.addedNodes ?? [],
    removed: activeDiff?.removedNodes ?? [],
    modified: activeDiff?.modifiedNodes ?? [],
  }), [activeDiff]);

  function openTopology(arn: string) {
    navigate(`/?focus=${encodeURIComponent(arn)}`);
  }

  return (
    <div className="timeline-page">
      <section className="timeline-hero">
        <div>
          <p className="timeline-eyebrow">Phase 4 Timeline</p>
          <h2>Snapshot history and graph diffs</h2>
          <p className="timeline-copy">
            Compare snapshots over time, inspect the exact resource changes, and keep the event stream beside the graph.
          </p>
        </div>
        <div className="timeline-period-tabs">
          {PERIOD_OPTIONS.map((option) => (
            <button
              key={option.id}
              type="button"
              className={period === option.id ? 'active' : ''}
              onClick={() => setPeriod(option.id)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </section>

      {period === 'custom' ? (
        <section className="timeline-custom-range">
          <div className="timeline-selector-group">
            <label htmlFor="timeline-custom-from">From</label>
            <input
              id="timeline-custom-from"
              type="datetime-local"
              value={customFrom}
              onChange={(event) => setCustomFrom(event.target.value)}
            />
          </div>
          <div className="timeline-selector-group">
            <label htmlFor="timeline-custom-to">To</label>
            <input
              id="timeline-custom-to"
              type="datetime-local"
              value={customTo}
              onChange={(event) => setCustomTo(event.target.value)}
            />
          </div>
        </section>
      ) : null}

      <section className="timeline-summary-grid">
        <div className="timeline-summary-card">
          <span>Snapshots</span>
          <strong>{snapshots?.length ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>Latest changes</span>
          <strong>{activeDiff?.totalChanges ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>Last diff</span>
          <strong>{activeDiff ? formatDistanceToNow(new Date(activeDiff.diffedAt), { addSuffix: true }) : 'none yet'}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>Event feed</span>
          <strong>{eventLogs?.length ?? 0}</strong>
        </div>
      </section>

      <section className="timeline-selector-panel">
        <div className="timeline-selector-group">
          <label htmlFor="snapshot-from">From</label>
          <select id="snapshot-from" value={selectedFrom ?? ''} onChange={(event) => setSelectedFrom(Number(event.target.value))}>
            <option value="" disabled>Select base snapshot</option>
            {(snapshots ?? []).map((snapshot) => (
              <option key={snapshot.id} value={snapshot.id}>
                #{snapshot.id} · {new Date(snapshot.capturedAt).toLocaleString()}
              </option>
            ))}
          </select>
        </div>
        <div className="timeline-selector-group">
          <label htmlFor="snapshot-to">To</label>
          <select id="snapshot-to" value={selectedTo ?? ''} onChange={(event) => setSelectedTo(Number(event.target.value))}>
            <option value="" disabled>Select target snapshot</option>
            {(snapshots ?? []).map((snapshot) => (
              <option key={snapshot.id} value={snapshot.id}>
                #{snapshot.id} · {new Date(snapshot.capturedAt).toLocaleString()}
              </option>
            ))}
          </select>
        </div>
        {isDiffLoading ? (
          <div className="timeline-loading-pill">
            <LoaderCircle size={15} className="spin" />
            <span>Loading diff…</span>
          </div>
        ) : null}
      </section>

      <section className="timeline-bar-shell">
        <div className="timeline-bar">
          {(timelineEntries ?? []).map((entry) => (
            <button
              key={entry.snapshotId}
              type="button"
              className="timeline-bar-point"
              data-active={activeDiff?.targetSnapshot.id === entry.snapshotId}
              onClick={() => {
                if (entry.baseSnapshotId) {
                  setSelectedFrom(entry.baseSnapshotId);
                  setSelectedTo(entry.snapshotId);
                }
              }}
            >
              <span className="timeline-point-dot" />
              <span className="timeline-point-time">{new Date(entry.capturedAt).toLocaleString()}</span>
              <span className="timeline-point-meta">{entry.totalChanges} changes · {entry.triggerSource}</span>
            </button>
          ))}
        </div>
      </section>

      <div className="timeline-layout">
        <section className="timeline-main-panel">
          <div className="timeline-panel-header">
            <div>
              <p className="timeline-panel-title">Diff summary</p>
              <p className="timeline-panel-copy">
                {activeDiff
                  ? `Base #${activeDiff.baseSnapshot.id} → Target #${activeDiff.targetSnapshot.id}`
                  : 'Run a couple of scans to start accumulating diffs.'}
              </p>
            </div>
          </div>

          {activeDiff ? (
            <>
              <div className="timeline-diff-metrics">
                <div className="timeline-metric-card added">
                  <GitBranchPlus size={16} />
                  <span>Added</span>
                  <strong>{activeDiff.addedCount}</strong>
                </div>
                <div className="timeline-metric-card removed">
                  <GitPullRequestArrow size={16} />
                  <span>Removed</span>
                  <strong>{activeDiff.removedCount}</strong>
                </div>
                <div className="timeline-metric-card modified">
                  <GitCommitVertical size={16} />
                  <span>Modified</span>
                  <strong>{activeDiff.modifiedCount}</strong>
                </div>
                <div className="timeline-metric-card neutral">
                  <Layers3 size={16} />
                  <span>Total</span>
                  <strong>{activeDiff.totalChanges}</strong>
                </div>
              </div>

              <ChangeListSection title="Added resources" items={changeSlices.added} tone="added" onOpenTopology={openTopology} />
              <ChangeListSection title="Removed resources" items={changeSlices.removed} tone="removed" onOpenTopology={openTopology} />
              <ChangeListSection title="Modified resources" items={changeSlices.modified} tone="modified" onOpenTopology={openTopology} />
            </>
          ) : (
            <div className="timeline-empty-card">No diff is available yet.</div>
          )}
        </section>

        <aside className="timeline-side-panel">
          <div className="timeline-panel-header">
            <div>
              <p className="timeline-panel-title">Recent events</p>
              <p className="timeline-panel-copy">EventBridge ingestion stays optional, but the feed is ready.</p>
            </div>
          </div>
          <div className="timeline-event-list">
            {(eventLogs ?? []).length === 0 ? (
              <div className="timeline-empty-card">No event logs yet.</div>
            ) : (
              (eventLogs ?? []).slice(0, 20).map((event) => (
                <article key={event.id} className="timeline-event-card" data-status={event.status.toLowerCase()}>
                  <div className="timeline-event-head">
                    <span className="timeline-event-status">{event.status}</span>
                    <span className="timeline-event-age">
                      {formatDistanceToNow(new Date(event.receivedAt), { addSuffix: true })}
                    </span>
                  </div>
                  <p className="timeline-event-title">{event.action ?? event.detailType}</p>
                  <p className="timeline-event-copy">{event.source} · {event.resourceType ?? 'unknown resource'}</p>
                  {event.resourceArn ? (
                    <button type="button" className="timeline-link-button subtle" onClick={() => openTopology(event.resourceArn!)}>
                      <span>Open resource</span>
                      <ArrowRight size={14} />
                    </button>
                  ) : null}
                  {event.message ? <p className="timeline-event-message">{event.message}</p> : null}
                </article>
              ))
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}
