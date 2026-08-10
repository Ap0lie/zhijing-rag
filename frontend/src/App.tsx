import { lazy, Suspense, type ReactNode } from "react";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";

import { AppShell } from "./components/AppShell";
import { useAuth } from "./auth";
import { ForbiddenPage } from "./pages/ForbiddenPage";
import { HomePage } from "./pages/HomePage";
import { LoginPage } from "./pages/LoginPage";
import { SearchPage } from "./pages/SearchPage";

const ChatPage = lazy(() => import("./pages/ChatPage").then((module) => ({ default: module.ChatPage })));
const MemoryPage = lazy(() => import("./pages/MemoryPage").then((module) => ({ default: module.MemoryPage })));
const ChunkDetailPage = lazy(() =>
  import("./pages/ChunkDetailPage").then((module) => ({ default: module.ChunkDetailPage })),
);
const DocumentDetailPage = lazy(() =>
  import("./pages/DocumentDetailPage").then((module) => ({ default: module.DocumentDetailPage })),
);
const UsersPage = lazy(() => import("./pages/UsersPage").then((module) => ({ default: module.UsersPage })));
const PipelineJobsPage = lazy(() =>
  import("./pages/PipelineJobsPage").then((module) => ({ default: module.PipelineJobsPage })),
);
const RetrievalDebugPage = lazy(() =>
  import("./pages/RetrievalDebugPage").then((module) => ({ default: module.RetrievalDebugPage })),
);
const GraphAdminPage = lazy(() =>
  import("./pages/GraphAdminPage").then((module) => ({ default: module.GraphAdminPage })),
);
const EvaluationPage = lazy(() =>
  import("./pages/EvaluationPage").then((module) => ({ default: module.EvaluationPage })),
);
const QueryIntelligencePage = lazy(() =>
  import("./pages/QueryIntelligencePage").then((module) => ({ default: module.QueryIntelligencePage })),
);
const AdminOverviewPage = lazy(() =>
  import("./pages/AdminOverviewPage").then((module) => ({ default: module.AdminOverviewPage })),
);
const UserDetailPage = lazy(() =>
  import("./pages/UserDetailPage").then((module) => ({ default: module.UserDetailPage })),
);
const AdminAuditPage = lazy(() =>
  import("./pages/AdminAuditPage").then((module) => ({ default: module.AdminAuditPage })),
);

function RouteLoading() {
  return (
    <div className="screen-state" aria-live="polite" aria-busy="true">
      <span className="spinner" aria-hidden="true" />
      <p>正在加载页面</p>
    </div>
  );
}

function LazyRoute({ children }: { children: ReactNode }) {
  return <Suspense fallback={<RouteLoading />}>{children}</Suspense>;
}

function ProtectedRoute() {
  const { status } = useAuth();

  if (status === "loading") {
    return (
      <main className="screen-state" aria-live="polite">
        <span className="spinner" aria-hidden="true" />
        <p>正在确认登录状态</p>
      </main>
    );
  }

  return status === "authenticated" ? <Outlet /> : <Navigate to="/login" replace />;
}

function AdminRoute() {
  const { user } = useAuth();
  return user?.role === "ADMIN" ? <Outlet /> : <ForbiddenPage />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppShell />}>
          <Route index element={<HomePage />} />
          <Route path="search" element={<SearchPage />} />
          <Route path="chat" element={<LazyRoute><ChatPage /></LazyRoute>} />
          <Route path="memory" element={<LazyRoute><MemoryPage /></LazyRoute>} />
          <Route path="chunks/:chunkId" element={<LazyRoute><ChunkDetailPage /></LazyRoute>} />
          <Route path="documents/:documentId" element={<LazyRoute><DocumentDetailPage /></LazyRoute>} />
          <Route element={<AdminRoute />}>
            <Route path="admin" element={<LazyRoute><AdminOverviewPage /></LazyRoute>} />
            <Route path="admin/users" element={<LazyRoute><UsersPage /></LazyRoute>} />
            <Route path="admin/users/:userId" element={<LazyRoute><UserDetailPage /></LazyRoute>} />
            <Route path="admin/audit" element={<LazyRoute><AdminAuditPage /></LazyRoute>} />
            <Route path="admin/pipeline" element={<LazyRoute><PipelineJobsPage /></LazyRoute>} />
            <Route path="admin/retrieval" element={<LazyRoute><RetrievalDebugPage /></LazyRoute>} />
            <Route path="admin/graph" element={<LazyRoute><GraphAdminPage /></LazyRoute>} />
            <Route path="admin/evaluations" element={<LazyRoute><EvaluationPage /></LazyRoute>} />
            <Route path="admin/query-intelligence" element={<LazyRoute><QueryIntelligencePage /></LazyRoute>} />
          </Route>
          <Route path="forbidden" element={<ForbiddenPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
