import { BrowserRouter, Routes, Route } from 'react-router-dom';

function App() {
  return (
    <BrowserRouter>
      <div className="flex h-screen bg-gray-50">
        {/* Sidebar */}
        <aside className="w-56 bg-white border-r border-gray-200 p-4 flex flex-col">
          <h1 className="text-xl font-bold text-gray-800 mb-6">Ariadne</h1>
          <nav className="flex flex-col gap-1 text-sm">
            <a href="/" className="px-3 py-2 rounded-md bg-blue-50 text-blue-700 font-medium">
              Topology
            </a>
            <a href="/audit" className="px-3 py-2 rounded-md text-gray-600 hover:bg-gray-100">
              Audit
            </a>
            <a href="/timeline" className="px-3 py-2 rounded-md text-gray-600 hover:bg-gray-100">
              Timeline
            </a>
            <a href="/query" className="px-3 py-2 rounded-md text-gray-600 hover:bg-gray-100">
              Query
            </a>
          </nav>
          <div className="mt-auto text-xs text-gray-400">
            <p>Last scan: -</p>
            <p>Nodes: 0</p>
          </div>
        </aside>

        {/* Main */}
        <main className="flex-1 flex flex-col">
          <header className="h-14 bg-white border-b border-gray-200 px-6 flex items-center">
            <input
              type="text"
              placeholder="Ask about your infrastructure..."
              className="flex-1 px-4 py-2 text-sm border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </header>
          <div className="flex-1 flex items-center justify-center text-gray-400">
            <Routes>
              <Route path="/" element={<p>Topology view — Phase 1에서 React Flow 연동 예정</p>} />
              <Route path="/audit" element={<p>Audit dashboard — Phase 3에서 구현 예정</p>} />
              <Route path="/timeline" element={<p>Timeline — Phase 4에서 구현 예정</p>} />
              <Route path="/query" element={<p>NL Query — Phase 3에서 구현 예정</p>} />
            </Routes>
          </div>
        </main>
      </div>
    </BrowserRouter>
  );
}

export default App;
