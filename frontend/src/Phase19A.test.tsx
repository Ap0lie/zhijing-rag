import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";

import App from "./App";
import { AuthProvider } from "./auth";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const overview = {
  schemaVersion: "admin-overview-v1",
  capturedAt: "2026-08-02T10:00:00Z",
  domains: [
    { key: "OVERVIEW", title: "管理总览", description: "只看需要处理的事项。", href: "/admin", links: [] },
    { key: "ACCESS_CONTENT", title: "访问与内容", description: "管理用户、文档和处理任务。", href: "/admin/users", links: [{ title: "用户管理", href: "/admin/users" }] },
    { key: "RETRIEVAL_KNOWLEDGE", title: "检索与知识", description: "管理检索、图谱和查询策略。", href: "/admin/retrieval", links: [{ title: "检索管理", href: "/admin/retrieval" }] },
    { key: "QUALITY_OPERATIONS", title: "质量与运维", description: "评测质量、发布门禁和运行状态。", href: "/admin/evaluations", links: [{ title: "评测中心", href: "/admin/evaluations" }] },
  ],
  attentionItems: [{
    code: "PIPELINE_QUARANTINED",
    title: "隔离任务",
    description: "需要核对隔离原因并决定是否解除。",
    count: 2,
    severity: "WARNING",
    valueState: "BLOCKED",
    reasonCode: "PIPELINE_QUARANTINED",
    updatedAt: "2026-08-02T09:00:00Z",
    href: "/admin/pipeline?status=QUARANTINED",
  }],
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("renders a task-oriented admin overview and four-domain menu", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith("/auth/me")) return new Response(JSON.stringify(admin), { status: 200, headers: { "Content-Type": "application/json" } });
    if (path.endsWith("/admin/overview")) return new Response(JSON.stringify(overview), { status: 200, headers: { "Content-Type": "application/json" } });
    throw new Error(`Unexpected request: ${path}`);
  }));
  const user = userEvent.setup();

  render(<MemoryRouter initialEntries={["/admin"]}><AuthProvider><App /></AuthProvider></MemoryRouter>);

  expect(await screen.findByRole("heading", { name: "需要处理" })).toBeInTheDocument();
  expect(screen.getByText("2 项")).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "访问与内容" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "检索与知识" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "质量与运维" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "查看并处理：隔离任务" })).toHaveClass("admin-attention-action");

  await user.click(screen.getByRole("button", { name: "管理" }));
  const menu = screen.getByRole("menu", { name: "管理工具" });
  expect(within(menu).getAllByRole("menuitem")).toHaveLength(4);
  expect(within(menu).getByText("管理总览").closest("a")).toHaveClass("active");
  await waitFor(() => expect(document.title).toBe("管理总览 | 知境 RAG"));
});
