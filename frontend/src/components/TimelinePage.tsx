import { useEffect, useMemo, useState } from 'react';
import { subHours } from 'date-fns';
import { ArrowRight, GitBranchPlus, GitCommitVertical, GitPullRequestArrow, Layers3, LoaderCircle } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import useSWR from 'swr';

import { fetchEventLogs, fetchLatestSnapshotDiff, fetchSnapshotDiff, fetchSnapshots, fetchTimeline } from '../lib/api';
import type { NodeDiffResponse } from '../lib/types';
import { formatRelativeTime } from '../lib/uiCopy';

const PERIOD_OPTIONS = [
  { id: '24h', label: '24h' },
  { id: '7d', label: '7d' },
  { id: '30d', label: '30d' },
  { id: 'custom', label: '직접 선택' },
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
          <p className="timeline-section-copy">{items.length}개 리소스</p>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="timeline-empty-card">이 구간에는 변경 사항이 없습니다.</div>
      ) : (
        <div className="timeline-change-list">
          {items.map((item) => (
            <article key={`${title}-${item.arn ?? item.name ?? Math.random()}`} className="timeline-change-card" data-tone={tone}>
              <div className="timeline-change-card-head">
                <div>
                  <p className="timeline-change-title">{item.name ?? item.arn ?? '이름 없는 리소스'}</p>
                  <p className="timeline-change-meta">{item.resourceType ?? '알 수 없음'} · {item.arn ?? 'ARN 없음'}</p>
                </div>
                {item.arn ? (
                  <button type="button" className="timeline-link-button" onClick={() => onOpenTopology(item.arn!)}>
                    <span>인프라 맵</span>
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
                    ? '최신 스냅샷에 새 리소스가 나타났습니다.'
                    : '최신 스냅샷에서 리소스가 사라졌습니다.'}
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
          <p className="timeline-eyebrow">변경 이력</p>
          <h2>스냅샷 이력과 그래프 차이</h2>
          <p className="timeline-copy">
            시간대별 스냅샷을 비교하고, 실제로 어떤 리소스가 바뀌었는지 이벤트 흐름과 함께 확인합니다.
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
            <label htmlFor="timeline-custom-from">시작</label>
            <input
              id="timeline-custom-from"
              type="datetime-local"
              value={customFrom}
              onChange={(event) => setCustomFrom(event.target.value)}
            />
          </div>
          <div className="timeline-selector-group">
            <label htmlFor="timeline-custom-to">끝</label>
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
          <span>스냅샷 수</span>
          <strong>{snapshots?.length ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>최신 변경 수</span>
          <strong>{activeDiff?.totalChanges ?? 0}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>마지막 비교</span>
          <strong>{activeDiff ? formatRelativeTime(activeDiff.diffedAt) : '아직 없음'}</strong>
        </div>
        <div className="timeline-summary-card">
          <span>이벤트 수</span>
          <strong>{eventLogs?.length ?? 0}</strong>
        </div>
      </section>

      <section className="timeline-selector-panel">
        <div className="timeline-selector-group">
          <label htmlFor="snapshot-from">기준 스냅샷</label>
          <select id="snapshot-from" value={selectedFrom ?? ''} onChange={(event) => setSelectedFrom(Number(event.target.value))}>
            <option value="" disabled>기준 스냅샷 선택</option>
            {(snapshots ?? []).map((snapshot) => (
              <option key={snapshot.id} value={snapshot.id}>
                #{snapshot.id} · {new Date(snapshot.capturedAt).toLocaleString()}
              </option>
            ))}
          </select>
        </div>
        <div className="timeline-selector-group">
          <label htmlFor="snapshot-to">비교 스냅샷</label>
          <select id="snapshot-to" value={selectedTo ?? ''} onChange={(event) => setSelectedTo(Number(event.target.value))}>
            <option value="" disabled>비교 스냅샷 선택</option>
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
            <span>차이 정보를 불러오는 중…</span>
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
              <span className="timeline-point-meta">{entry.totalChanges}건 변경 · {entry.triggerSource}</span>
            </button>
          ))}
        </div>
      </section>

      <div className="timeline-layout">
        <section className="timeline-main-panel">
          <div className="timeline-panel-header">
            <div>
              <p className="timeline-panel-title">차이 요약</p>
              <p className="timeline-panel-copy">
                {activeDiff
                  ? `기준 #${activeDiff.baseSnapshot.id} → 비교 #${activeDiff.targetSnapshot.id}`
                  : '스캔을 몇 번 더 실행하면 변경 이력을 쌓아갈 수 있습니다.'}
              </p>
            </div>
          </div>

          {activeDiff ? (
            <>
              <div className="timeline-diff-metrics">
                <div className="timeline-metric-card added">
                  <GitBranchPlus size={16} />
                  <span>추가</span>
                  <strong>{activeDiff.addedCount}</strong>
                </div>
                <div className="timeline-metric-card removed">
                  <GitPullRequestArrow size={16} />
                  <span>삭제</span>
                  <strong>{activeDiff.removedCount}</strong>
                </div>
                <div className="timeline-metric-card modified">
                  <GitCommitVertical size={16} />
                  <span>변경</span>
                  <strong>{activeDiff.modifiedCount}</strong>
                </div>
                <div className="timeline-metric-card neutral">
                  <Layers3 size={16} />
                  <span>전체</span>
                  <strong>{activeDiff.totalChanges}</strong>
                </div>
              </div>

              <ChangeListSection title="추가된 리소스" items={changeSlices.added} tone="added" onOpenTopology={openTopology} />
              <ChangeListSection title="삭제된 리소스" items={changeSlices.removed} tone="removed" onOpenTopology={openTopology} />
              <ChangeListSection title="변경된 리소스" items={changeSlices.modified} tone="modified" onOpenTopology={openTopology} />
            </>
          ) : (
            <div className="timeline-empty-card">아직 비교 가능한 차이 정보가 없습니다.</div>
          )}
        </section>

        <aside className="timeline-side-panel">
          <div className="timeline-panel-header">
            <div>
              <p className="timeline-panel-title">최근 이벤트</p>
              <p className="timeline-panel-copy">EventBridge 연동은 선택 사항이지만, 이벤트 피드는 바로 확인할 수 있습니다.</p>
            </div>
          </div>
          <div className="timeline-event-list">
            {(eventLogs ?? []).length === 0 ? (
              <div className="timeline-empty-card">아직 수집된 이벤트 로그가 없습니다.</div>
            ) : (
              (eventLogs ?? []).slice(0, 20).map((event) => (
                <article key={event.id} className="timeline-event-card" data-status={event.status.toLowerCase()}>
                  <div className="timeline-event-head">
                    <span className="timeline-event-status">{event.status}</span>
                    <span className="timeline-event-age">
                      {formatRelativeTime(event.receivedAt)}
                    </span>
                  </div>
                  <p className="timeline-event-title">{event.action ?? event.detailType}</p>
                  <p className="timeline-event-copy">{event.source} · {event.resourceType ?? '알 수 없는 리소스'}</p>
                  {event.resourceArn ? (
                    <button type="button" className="timeline-link-button subtle" onClick={() => openTopology(event.resourceArn!)}>
                      <span>리소스 열기</span>
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
