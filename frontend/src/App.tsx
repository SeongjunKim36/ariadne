import { BrowserRouter, NavLink, Route, Routes } from 'react-router-dom';
import { formatDistanceToNow } from 'date-fns';
import { Activity, Bot, Network, ScrollText, TimerReset, TriangleAlert } from 'lucide-react';
import useSWR from 'swr';

import { fetchLatestScan } from './lib/api';
import { TopologyPage } from './components/TopologyPage';

function PlaceholderPage({
  icon: Icon,
  title,
  copy,
}: {
  icon: typeof ScrollText;
  title: string;
  copy: string;
}) {
  return (
    <div className="placeholder-shell">
      <div className="placeholder-card">
        <span className="placeholder-icon">
          <Icon size={18} />
        </span>
        <h2>{title}</h2>
        <p>{copy}</p>
      </div>
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
          <Route
            path="/audit"
            element={
              <PlaceholderPage
                icon={Activity}
                title="Audit rules arrive in Phase 3"
                copy="The graph is ready for risk overlays once SG and IAM rule engines land."
              />
            }
          />
          <Route
            path="/timeline"
            element={
              <PlaceholderPage
                icon={TimerReset}
                title="Timeline is queued for drift work"
                copy="Phase 4 will layer history and diffs over the same resource graph."
              />
            }
          />
          <Route
            path="/query"
            element={
              <PlaceholderPage
                icon={Bot}
                title="Natural language query is staged next"
                copy="Phase 3 will translate operator questions into graph-aware answers."
              />
            }
          />
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
