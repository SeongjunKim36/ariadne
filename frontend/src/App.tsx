import { lazy, Suspense } from 'react';
import { BrowserRouter, NavLink, Route, Routes } from 'react-router-dom';
import { Activity, Bot, GitCompareArrows, LoaderCircle, Network, TimerReset, TriangleAlert } from 'lucide-react';
import useSWR from 'swr';

import { fetchLatestScan } from './lib/api';
import { formatRelativeTime, formatScanStatusLabel, summarizeWarningMessage } from './lib/uiCopy';

const TopologyPage = lazy(() =>
  import('./components/TopologyPage').then((module) => ({ default: module.TopologyPage })),
);
const AuditPage = lazy(() =>
  import('./components/AuditPage').then((module) => ({ default: module.AuditPage })),
);
const TimelinePage = lazy(() =>
  import('./components/TimelinePage').then((module) => ({ default: module.TimelinePage })),
);
const DriftPage = lazy(() =>
  import('./components/DriftPage').then((module) => ({ default: module.DriftPage })),
);
const QueryPage = lazy(() =>
  import('./components/QueryPage').then((module) => ({ default: module.QueryPage })),
);

function RouteSkeleton() {
  return (
    <div className="route-skeleton">
      <LoaderCircle size={18} className="spin" />
      <span>화면을 불러오는 중입니다…</span>
    </div>
  );
}

function Shell() {
  const { data: latestScan } = useSWR('latest-scan', fetchLatestScan, {
    shouldRetryOnError: false,
  });

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand-block">
          <p className="brand-eyebrow">AWS 인프라 가시화</p>
          <h1 className="brand-title">Ariadne</h1>
          <p className="brand-copy">
            현재 계정 상태를 콘솔 목록이 아니라 탐색 가능한 인프라 그래프로 정리합니다.
          </p>
        </div>

        <nav className="sidebar-nav">
          <NavLink to="/" end className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <Network size={16} />
            <span>인프라 맵</span>
          </NavLink>
          <NavLink to="/audit" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <Activity size={16} />
            <span>보안 점검</span>
          </NavLink>
          <NavLink to="/timeline" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <TimerReset size={16} />
            <span>변경 이력</span>
          </NavLink>
          <NavLink to="/drift" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <GitCompareArrows size={16} />
            <span>드리프트</span>
          </NavLink>
          <NavLink to="/query" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <Bot size={16} />
            <span>질의</span>
          </NavLink>
        </nav>

        <div className="sidebar-status-card">
          <p className="sidebar-status-label">최근 스캔</p>
          {latestScan ? (
            <>
              <p className="sidebar-status-value">{formatScanStatusLabel(latestScan.status)}</p>
              <p className="sidebar-status-copy">
                {latestScan.completedAt
                  ? formatRelativeTime(latestScan.completedAt)
                  : '아직 실행 중입니다'}
              </p>
              <p className="sidebar-status-copy">
                {latestScan.totalNodes}개 노드 · {latestScan.totalEdges}개 연결
              </p>
              {latestScan.errorMessage ? (
                <div className="sidebar-warning">
                  <TriangleAlert size={14} />
                  <span>{latestScan.errorMessage}</span>
                </div>
              ) : null}
              {summarizeWarningMessage(latestScan.warningMessage) ? (
                <div className="sidebar-warning">
                  <TriangleAlert size={14} />
                  <span>{summarizeWarningMessage(latestScan.warningMessage)}</span>
                </div>
              ) : null}
            </>
          ) : (
            <p className="sidebar-status-copy">아직 완료된 스캔이 없습니다.</p>
          )}
        </div>
      </aside>

      <main className="app-main">
        <Suspense fallback={<RouteSkeleton />}>
          <Routes>
            <Route path="/" element={<TopologyPage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/timeline" element={<TimelinePage />} />
            <Route path="/drift" element={<DriftPage />} />
            <Route path="/query" element={<QueryPage />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <Shell />
    </BrowserRouter>
  );
}

export default App;
