import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";

import App from "./App";
import { AuthProvider } from "./auth";

const admin = { id: "10000000-0000-0000-0000-000000000001", username: "admin", role: "ADMIN" } as const;
const targetId = "20000000-0000-0000-0000-000000000002";
const documentId = "30000000-0000-0000-0000-000000000003";

const managedUser = {
  id: targetId,
  username: "reader",
  role: "USER",
  enabled: true,
  createdAt: "2026-08-02T10:00:00Z",
  securityVersion: 3,
  accessSummary: { platformAccess: false, publicDocuments: 2, ownedDocuments: 1, explicitGrants: 0, totalDocuments: 3 },
};

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("previews impact before applying an explicit document grant", async () => {
  let granted = false;
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith("/auth/me")) return json(admin);
    if (path.endsWith("/auth/csrf")) return json({ token: "csrf", headerName: "X-CSRF-TOKEN" });
    if (path.endsWith(`/admin/users/${targetId}/access`)) return json({ user: managedUser, access: managedUser.accessSummary });
    if (path.includes(`/admin/users/${targetId}/document-grants?`)) return json({
      userId: targetId, page: 0, size: 20, total: 1,
      items: [{
        documentId, title: "Restricted handbook", visibility: "RESTRICTED",
        ownerUserId: admin.id, ownerUsername: "admin", aclVersion: granted ? 2 : 1,
        accessSource: granted ? "EXPLICIT" : "NONE", granted, editable: true,
      }],
    });
    if (path.includes("/admin/audit-events?")) return json({ items: [], nextCursor: null });
    if (path.endsWith("/admin/operation-impact/preflight")) return json({
      operation: "DOCUMENT_GRANT_BATCH", objectType: "USER", objectId: targetId,
      confirmation: "UPDATE_DOCUMENT_GRANTS", factVersion: 3,
      immediateEffects: ["下一次访问立即生效"], asynchronousEffects: ["图谱进入 stale"],
      notAffected: ["公共文档不变"], blockers: [],
      affectedCounts: { requestedChanges: 1, currentExplicitGrants: 0 }, rollback: "提交反向差量",
    });
    if (path.endsWith(`/admin/users/${targetId}/document-grants`) && init?.method === "POST") {
      const body = JSON.parse(String(init.body));
      expect(body).toMatchObject({
        confirmation: "UPDATE_DOCUMENT_GRANTS",
        expectedUserSecurityVersion: 3,
        changes: [{ documentId, granted: true, expectedAclVersion: 1 }],
      });
      granted = true;
      return json({ user: { user: managedUser, access: managedUser.accessSummary }, changedDocumentIds: [documentId], replayed: false });
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  const actor = userEvent.setup();

  render(<MemoryRouter initialEntries={[`/admin/users/${targetId}`]}><AuthProvider><App /></AuthProvider></MemoryRouter>);

  expect(await screen.findByRole("heading", { name: "有效访问范围" })).toBeInTheDocument();
  expect(screen.getByText("公共", { selector: "dt" })).toBeInTheDocument();
  await actor.click(screen.getByRole("checkbox"));
  await actor.type(screen.getByLabelText("审计理由"), "批准项目资料访问权限");
  await actor.click(screen.getByRole("button", { name: "查看 1 项变化的影响" }));
  expect(await screen.findByRole("heading", { name: "请确认实际影响" })).toBeInTheDocument();
  expect(screen.getByText("下一次访问立即生效")).toBeInTheDocument();
  await actor.click(screen.getByRole("button", { name: "确认并应用" }));
  expect(await screen.findByText("已应用 1 项明确授权变化")).toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalled();
});

it("filters the normalized audit timeline without exposing content", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith("/auth/me")) return json(admin);
    if (path.includes("/admin/audit-events?")) return json({
      items: [{
        sourceEvent: "GOVERNANCE:1", module: "ACCESS", action: "USER_SECURITY_CHANGED",
        actorId: admin.id, actorSnapshot: "admin", objectType: "USER", objectId: targetId,
        objectLabel: "reader", before: { enabled: true }, after: { enabled: false },
        reason: "停用离职用户账号", occurredAt: "2026-08-02T12:00:00Z",
      }],
      nextCursor: null,
    });
    throw new Error(`Unexpected request: ${path}`);
  }));

  render(<MemoryRouter initialEntries={["/admin/audit?module=ACCESS"]}><AuthProvider><App /></AuthProvider></MemoryRouter>);

  expect(await screen.findByText("修改用户安全设置")).toBeInTheDocument();
  expect(screen.getByText("停用离职用户账号")).toBeInTheDocument();
  expect(screen.queryByText(/password|token|prompt|evidence/i)).not.toBeInTheDocument();
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
