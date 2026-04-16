import { BrowserRouter, NavLink, Route, Routes } from 'react-router-dom';
import { formatDistanceToNow } from 'date-fns';
import { Activity, Bot, GitCompareArrows, Network, TimerReset, TriangleAlert } from 'lucide-react';
import useSWR from 'swr';

import { fetchLatestScan } from './lib/api';
import { AuditPage } from './components/AuditPage';
import { QueryPage } from './components/QueryPage';
import { TopologyPage } from './components/TopologyPage';
import { TimelinePage } from './components/TimelinePage';
import { DriftPage } from './components/DriftPage';

function Shell() {
  const { data: latestScan } = useSWR('latest-scan', fetchLatestScan, {
    shouldRetryOnError: false,
  });

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="brand-block">
          <p className="brand-eyebrow">AWS Infra Mapper</p>
          <h1 className="brand-title">Ariadne</h1>
          <p className="brand-copy">
            Turn the current account snapshot into an explorable graph instead of a pile of consoles.
          </p>
        </div>

        <nav className="sidebar-nav">
          <NavLink to="/" end className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <Network size={16} />
            <span>Topology</span>
          </NavLink>
          <NavLink to="/audit" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <Activity size={16} />
            <span>Audit</span>
          </NavLink>
          <NavLink to="/timeline" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <TimerReset size={16} />
            <span>Timeline</span>
          </NavLink>
          <NavLink to="/drift" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <GitCompareArrows size={16} />
            <span>Drift</span>
          </NavLink>
          <NavLink to="/query" className={({ isActive }) => `sidebar-link${isActive ? ' active' : ''}`}>
            <Bot size={16} />
            <span>Query</span>
          </NavLink>
        </nav>

        <div className="sidebar-status-card">
          <p className="sidebar-status-label">Latest scan</p>
          {latestScan ? (
            <>
              <p className="sidebar-status-value">{latestScan.status}</p>
              <p className="sidebar-status-copy">
                {latestScan.completedAt
                  ? formatDistanceToNow(new Date(latestScan.completedAt), { addSuffix: true })
                  : 'still running'}
              </p>
              <p className="sidebar-status-copy">
                {latestScan.totalNodes} nodes · {latestScan.totalEdges} edges
              </p>
              {latestScan.errorMessage ? (
                <div className="sidebar-warning">
                  <TriangleAlert size={14} />
                  <span>{latestScan.errorMessage}</span>
                </div>
              ) : null}
              {latestScan.warningMessage ? (
                <div className="sidebar-warning">
                  <TriangleAlert size={14} />
                  <span>{latestScan.warningMessage}</span>
                </div>
              ) : null}
            </>
          ) : (
            <p className="sidebar-status-copy">No completed scan has been discovered yet.</p>
          )}
        </div>
      </aside>

      <main className="app-main">
        <Routes>
          <Route path="/" element={<TopologyPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/timeline" element={<TimelinePage />} />
          <Route path="/drift" element={<DriftPage />} />
          <Route path="/query" element={<QueryPage />} />
        </Routes>
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
