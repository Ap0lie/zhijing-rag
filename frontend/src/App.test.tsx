import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import { resetDocumentFormatsCache } from "./documentFiles";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const regularUser = {
  id: "10000000-0000-0000-0000-000000000002",
  username: "reader",
  role: "USER",
} as const;

const emptyPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
const documentFormats = {
  schemaVersion: "document-formats-v1",
  formats: [{
    format: "PDF",
    enabled: true,
    displayName: "PDF",
    extensions: [".pdf"],
    mediaTypes: ["application/pdf"],
    maxFileSizeBytes: 50 * 1024 * 1024,
    locatorKinds: ["PAGE"],
    parserProviders: [
      { provider: "PDFBOX", available: true, reasonCode: null },
      { provider: "MINERU", available: false, reasonCode: "MINERU_DISABLED" },
    ],
    parserOverrideAllowed: true,
  }],
} as const;

const documentDetail = {
  document: {
    id: "20000000-0000-0000-0000-000000000001",
    title: "平台手册",
    visibility: "RESTRICTED",
    ownerUsername: "admin",
    aclVersion: 1,
    effectiveRevisionId: "30000000-0000-0000-0000-000000000001",
    latestRevisionNumber: 1,
    latestRevisionStatus: "UPLOADED",
    createdAt: "2026-07-22T10:00:00Z",
    updatedAt: "2026-07-22T10:00:00Z",
  },
  currentRevisionId: null,
  grantedUsers: [],
  revisions: [{
    id: "30000000-0000-0000-0000-000000000001",
    revisionNumber: 1,
    status: "UPLOADED",
    originalFilename: "guide.pdf",
    fileSizeBytes: 128,
    contentHash: "a".repeat(64),
    createdAt: "2026-07-22T10:00:00Z",
    current: false,
    effective: true,
  }],
} as const;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("Phase 2 and 3 application flows", () => {
  beforeEach(() => {
    resetCsrfToken();
    resetDocumentFormatsCache();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("logs in with an in-memory CSRF token and renders the protected workbench", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse({ code: "UNAUTHENTICATED" }, 401);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "csrf-value", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/auth/login")) {
        expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("csrf-value");
        return jsonResponse(admin);
      }
      if (path.includes("/documents?")) return jsonResponse(emptyPage);
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    const user = userEvent.setup();

    renderApp("/login");
    await user.type(await screen.findByLabelText("用户名"), "admin");
    await user.type(screen.getByLabelText("密码"), "local-pass-123");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("heading", { name: "文档中心" })).toBeInTheDocument();
    expect(await screen.findByText("暂无可访问文档")).toBeInTheDocument();
  });

  it("hides admin navigation and renders a 403 page for a direct USER route", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/auth/me")) return jsonResponse(regularUser);
      throw new Error(`Unexpected request: ${String(input)}`);
    }));

    renderApp("/admin/users");

    expect(await screen.findByText("没有访问权限")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "用户管理" })).not.toBeInTheDocument();
  });

  it("creates a user from the admin drawer and sends CSRF on the write", async () => {
    const existingAdmin = { ...admin, enabled: true, createdAt: "2026-07-21T10:00:00Z" };
    const createdUser = {
      id: "10000000-0000-0000-0000-000000000003",
      username: "new-user",
      role: "USER",
      enabled: true,
      createdAt: "2026-07-21T10:10:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/users") && (!init?.method || init.method === "GET")) {
        return jsonResponse([existingAdmin]);
      }
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "admin-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/admin/users") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("admin-csrf");
        expect(JSON.parse(String(init.body))).toMatchObject({ username: "new-user", role: "USER" });
        return jsonResponse(createdUser, 201);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/admin/users");
    await user.click(await screen.findByRole("button", { name: "创建用户" }));
    const dialog = screen.getByRole("dialog", { name: "创建用户" });
    expect(within(dialog).getByLabelText(/用户名/)).toHaveFocus();
    await user.type(within(dialog).getByLabelText(/用户名/), "new-user");
    await user.type(within(dialog).getByLabelText(/初始密码/), "new-user-pass");
    await user.click(within(dialog).getByRole("button", { name: "创建" }));

    expect(await screen.findByText("new-user")).toBeInTheDocument();
    expect(screen.getByText("已创建用户 new-user")).toBeInTheDocument();
  });

  it("refreshes a stale CSRF token once before an admin write", async () => {
    const existingAdmin = { ...admin, enabled: true, createdAt: "2026-07-21T10:00:00Z" };
    const createdUser = {
      id: "10000000-0000-0000-0000-000000000004",
      username: "recovered-user",
      role: "USER",
      enabled: true,
      createdAt: "2026-07-21T10:15:00Z",
    };
    let csrfCalls = 0;
    let writeCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/users") && (!init?.method || init.method === "GET")) {
        return jsonResponse([existingAdmin]);
      }
      if (path.endsWith("/auth/csrf")) {
        csrfCalls += 1;
        return jsonResponse({ token: csrfCalls === 1 ? "stale" : "fresh", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/users") && init?.method === "POST") {
        writeCalls += 1;
        const token = new Headers(init.headers).get("X-CSRF-TOKEN");
        if (writeCalls === 1) {
          expect(token).toBe("stale");
          return jsonResponse({ code: "FORBIDDEN" }, 403);
        }
        expect(token).toBe("fresh");
        return jsonResponse(createdUser, 201);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/admin/users");
    await user.click(await screen.findByRole("button", { name: "创建用户" }));
    const dialog = screen.getByRole("dialog", { name: "创建用户" });
    await user.type(within(dialog).getByLabelText(/用户名/), "recovered-user");
    await user.type(within(dialog).getByLabelText(/初始密码/), "recovered-pass");
    await user.click(within(dialog).getByRole("button", { name: "创建" }));

    expect(await screen.findByText("recovered-user")).toBeInTheDocument();
    expect(writeCalls).toBe(2);
  });

  it("clears local authentication when logout follows a stale session", async () => {
    let csrfCalls = 0;
    let logoutCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/documents?")) return jsonResponse(emptyPage);
      if (path.endsWith("/auth/csrf")) {
        csrfCalls += 1;
        return jsonResponse({ token: csrfCalls === 1 ? "stale" : "fresh", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/auth/logout") && init?.method === "POST") {
        logoutCalls += 1;
        return logoutCalls === 1 ? jsonResponse({ code: "FORBIDDEN" }, 403) : new Response(null, { status: 204 });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/");
    await user.click(await screen.findByRole("button", { name: "退出" }));

    expect(await screen.findByText("已安全退出")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "登录知识工作台" })).toBeInTheDocument();
    expect(logoutCalls).toBe(2);
  });

  it("returns to login with a clear message when the session expires", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/users")) return jsonResponse({ code: "UNAUTHENTICATED" }, 401);
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp("/admin/users");

    await waitFor(() => expect(screen.getByText("登录状态已失效，请重新登录")).toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "登录知识工作台" })).toBeInTheDocument();
  });

  it("uploads a PDF from the document center with CSRF and an idempotency key", async () => {
    const existingAdmin = { ...admin, enabled: true, createdAt: "2026-07-22T09:00:00Z" };
    const uploadedBodies: FormData[] = [];
    const idempotencyKeys: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/documents?") && (!init?.method || init.method === "GET")) return jsonResponse(emptyPage);
      if (path.endsWith("/document-formats")) return jsonResponse(documentFormats);
      if (path.endsWith("/admin/users")) return jsonResponse([existingAdmin]);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "upload-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/admin/documents") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("upload-csrf");
        idempotencyKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        uploadedBodies.push(init.body as FormData);
        return jsonResponse(documentDetail, 201);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/");
    await user.click(await screen.findByRole("button", { name: "上传文档" }));
    const dialog = screen.getByRole("dialog", { name: "上传 PDF" });
    const file = new File(["%PDF-1.7\ncontent"], "guide.pdf", { type: "application/pdf" });
    await user.upload(within(dialog).getByLabelText("选择文档文件"), file);
    await user.click(within(dialog).getByRole("button", { name: "上传" }));

    expect(await screen.findByText("已上传 平台手册")).toBeInTheDocument();
    expect(idempotencyKeys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(uploadedBodies[0].get("title")).toBe("guide");
    expect(uploadedBodies[0].get("file")).toBe(file);
  });

  it("keeps the document center usable when format capabilities are unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/documents?")) return jsonResponse(emptyPage);
      if (path.endsWith("/document-formats")) {
        return jsonResponse({ message: "能力注册表暂不可用" }, 503);
      }
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/");
    await user.click(await screen.findByRole("button", { name: "上传文档" }));

    expect(await screen.findByText("无法加载上传能力，请稍后重试")).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "上传 PDF" })).not.toBeInTheDocument();
    expect(screen.getByText("暂无可访问文档")).toBeInTheDocument();
  });

  it("disables a document format with confirmation and keeps its audit history visible", async () => {
    const availableFormats = {
      ...documentFormats,
      schemaVersion: "document-formats-v6",
      formats: documentFormats.formats.map((format) => ({
        ...format,
        runtimeStatus: "AVAILABLE",
        policyStatus: "ENABLED",
        policyVersion: 1,
        runningJobs: 0,
        parserProviders: format.parserProviders.map((provider) => ({
          ...provider,
          runtimeStatus: provider.available ? "AVAILABLE" : "UNAVAILABLE",
          policyStatus: "ENABLED",
          policyVersion: 1,
          runningJobs: 0,
        })),
      })),
    } as const;
    const disabledFormats = {
      ...availableFormats,
      formats: availableFormats.formats.map((format) => ({
        ...format,
        enabled: false,
        runtimeStatus: "DISABLED",
        policyStatus: "DISABLED",
        policyVersion: 2,
      })),
    } as const;
    const writes: Array<Record<string, unknown>> = [];
    let historyReads = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/documents?")) return jsonResponse(emptyPage);
      if (path.endsWith("/admin/document-formats") && (!init?.method || init.method === "GET")) {
        return jsonResponse(availableFormats);
      }
      if (path.includes("/admin/document-formats/PDF/events")) {
        historyReads += 1;
        return jsonResponse(historyReads === 1 ? [] : [{
          id: 1,
          policyKey: "FORMAT:PDF",
          scope: "FORMAT",
          documentFormat: "PDF",
          parserProvider: null,
          action: "DISABLE",
          previousStatus: "ENABLED",
          newStatus: "DISABLED",
          policyVersion: 2,
          reason: "暂停 PDF 新写入以验证隔离门禁",
          actorUsername: "admin",
          createdAt: "2026-08-01T02:00:00Z",
        }]);
      }
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "policy-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/admin/document-formats/PDF") && init?.method === "PATCH") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("policy-csrf");
        expect(new Headers(init.headers).get("Content-Type")).toBe("application/json");
        writes.push(JSON.parse(String(init.body)) as Record<string, unknown>);
        return jsonResponse(disabledFormats);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/");
    await user.click(await screen.findByRole("button", { name: "格式运行策略" }));
    expect(await screen.findByRole("heading", { name: "文档格式与解析器" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "禁用此格式" }));
    const editor = screen.getByRole("dialog", { name: "PDF" });
    await user.type(within(editor).getByLabelText("审计理由"), "暂停 PDF 新写入以验证隔离门禁");
    await user.click(within(editor).getByLabelText("我确认此次变更会影响新的上传、重解析和任务领取"));
    await user.click(within(editor).getByRole("button", { name: "确认禁用" }));

    expect(await screen.findByText("PDF已禁用")).toBeInTheDocument();
    expect(writes).toEqual([{
      parserProvider: null,
      action: "DISABLE",
      confirmation: "DISABLE_DOCUMENT_FORMAT",
      reason: "暂停 PDF 新写入以验证隔离门禁",
    }]);
  });

  it("updates ACL and uploads a second immutable revision from document detail", async () => {
    const reader = { ...regularUser, enabled: true, createdAt: "2026-07-22T09:10:00Z" };
    const revisionTwo = {
      ...documentDetail,
      document: { ...documentDetail.document, latestRevisionNumber: 2, effectiveRevisionId: "30000000-0000-0000-0000-000000000002" },
      revisions: [
        { ...documentDetail.revisions[0], id: "30000000-0000-0000-0000-000000000002", revisionNumber: 2, originalFilename: "guide-v2.pdf" },
        { ...documentDetail.revisions[0], effective: false },
      ],
    };
    let aclWrites = 0;
    let revisionWrites = 0;
    const revisionIdempotencyKeys: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`) && (!init?.method || init.method === "GET")) return jsonResponse(documentDetail);
      if (path.endsWith("/document-formats")) return jsonResponse(documentFormats);
      if (path.endsWith("/admin/users") && (!init?.method || init.method === "GET")) return jsonResponse([reader]);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "detail-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith(`/admin/documents/${documentDetail.document.id}/acl`) && init?.method === "PATCH") {
        aclWrites += 1;
        expect(JSON.parse(String(init.body))).toMatchObject({
          title: "平台手册（更新）",
          expectedAclVersion: 1,
        });
        return jsonResponse({ ...documentDetail, document: { ...documentDetail.document, title: "平台手册（更新）", aclVersion: 2 } });
      }
      if (path.endsWith(`/admin/documents/${documentDetail.document.id}/revisions`) && init?.method === "POST") {
        revisionWrites += 1;
        revisionIdempotencyKeys.push(new Headers(init.headers).get("Idempotency-Key") ?? "");
        if (revisionWrites === 1) return jsonResponse({ message: "版本服务暂时不可用" }, 503);
        return jsonResponse(revisionTwo, 201);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);
    const title = await screen.findByLabelText("标题");
    await user.clear(title);
    await user.type(title, "平台手册（更新）");
    await user.click(screen.getByRole("button", { name: "保存权限" }));
    expect(await screen.findByText("文档信息与访问权限已更新")).toBeInTheDocument();

    const revisionFile = new File(["%PDF-1.7\nrevision-two"], "guide-v2.pdf", { type: "application/pdf" });
    const revisionInput = screen.getByLabelText("上传新 Revision");
    await waitFor(() => expect(revisionInput).toBeEnabled());
    await user.upload(revisionInput, revisionFile);
    const uploadRevision = screen.getByRole("button", { name: "上传新版本" });
    await user.click(uploadRevision);
    expect(await screen.findByText("版本服务暂时不可用")).toBeInTheDocument();
    await waitFor(() => expect(uploadRevision).toBeEnabled());
    await user.click(uploadRevision);
    expect(await screen.findByText("新 Revision 已上传，旧版本保持不变")).toBeInTheDocument();
    expect(screen.getByText("R2")).toBeInTheDocument();
    expect(aclWrites).toBe(1);
    expect(revisionWrites).toBe(2);
    expect(revisionIdempotencyKeys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(revisionIdempotencyKeys[1]).toBe(revisionIdempotencyKeys[0]);
  });

  it("requires an audit confirmation before changing a revision format", async () => {
    const formattedDetail = {
      ...documentDetail,
      document: { ...documentDetail.document, documentFormat: "PDF" },
      revisions: [{ ...documentDetail.revisions[0], documentFormat: "PDF" }],
    };
    const formats = {
      ...documentFormats,
      formats: [
        ...documentFormats.formats,
        {
          format: "TXT",
          enabled: true,
          displayName: "TXT",
          extensions: [".txt"],
          mediaTypes: ["text/plain"],
          maxFileSizeBytes: 10 * 1024 * 1024,
          locatorKinds: ["LINE_RANGE", "HEADING_BLOCK"],
          parserProviders: [{ provider: "TEXT", available: true, reasonCode: null }],
          parserOverrideAllowed: false,
        },
      ],
    };
    const uploadedBodies: FormData[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)
        && (!init?.method || init.method === "GET")) return jsonResponse(formattedDetail);
      if (path.endsWith("/document-formats")) return jsonResponse(formats);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "format-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith(`/admin/documents/${documentDetail.document.id}/revisions`)
        && init?.method === "POST") {
        uploadedBodies.push(init.body as FormData);
        return jsonResponse(formattedDetail, 201);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);
    const input = await screen.findByLabelText("上传新 Revision");
    await waitFor(() => expect(input).toBeEnabled());
    await user.upload(input, new File(["plain text"], "guide.txt", { type: "text/plain" }));

    const submit = screen.getByRole("button", { name: "上传新版本" });
    expect(submit).toBeDisabled();
    expect(screen.getByText(/当前为 PDF，新文件为 TXT/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("格式变更审计理由"), "改用可维护的 TXT 原始文档");
    await user.click(screen.getByLabelText("确认更换原始文档格式并创建新 Revision"));
    await user.click(submit);

    await waitFor(() => expect(uploadedBodies).toHaveLength(1));
    expect(uploadedBodies[0].get("formatChangeConfirmation"))
      .toBe("CHANGE_DOCUMENT_FORMAT");
    expect(uploadedBodies[0].get("formatChangeReason"))
      .toBe("改用可维护的 TXT 原始文档");
  });

  it("reloads the latest ACL after an optimistic locking conflict", async () => {
    const latestDetail = {
      ...documentDetail,
      document: { ...documentDetail.document, title: "其他管理员的修改", aclVersion: 2 },
    };
    let detailReads = 0;
    let aclWrites = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) {
        detailReads += 1;
        return jsonResponse(detailReads === 1 ? documentDetail : latestDetail);
      }
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "acl-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith(`/admin/documents/${documentDetail.document.id}/acl`) && init?.method === "PATCH") {
        aclWrites += 1;
        expect(JSON.parse(String(init.body)).expectedAclVersion).toBe(1);
        return jsonResponse({ code: "ACL_VERSION_CONFLICT", message: "ACL 已更新" }, 409);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);
    await user.click(await screen.findByRole("button", { name: "保存权限" }));

    expect(await screen.findByText("权限已被其他操作修改，已加载最新状态，请重新确认")).toBeInTheDocument();
    expect(await screen.findByDisplayValue("其他管理员的修改")).toBeInTheDocument();
    expect(screen.getByText(/ACL v2/)).toBeInTheDocument();
    expect(detailReads).toBe(2);
    expect(aclWrites).toBe(1);
  });

  it("keeps document detail available when the managed-user list fails", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/admin/users")) return jsonResponse({ message: "用户服务不可用" }, 503);
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp(`/documents/${documentDetail.document.id}`);

    expect(await screen.findByRole("heading", { name: "平台手册" })).toBeInTheDocument();
    expect(screen.getByText("授权用户列表加载失败，文档仍可查看，但暂不能修改权限。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存权限" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "下载当前可用版本" })).toBeEnabled();
  });

  it("shows disabled grantees and removes them from the next ACL update", async () => {
    const disabledReader = { ...regularUser, enabled: false, createdAt: "2026-07-22T09:10:00Z" };
    const detailWithDisabledGrant = {
      ...documentDetail,
      grantedUsers: [{ id: disabledReader.id, username: disabledReader.username }],
    };
    let submittedGrantIds: string[] | undefined;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) return jsonResponse(detailWithDisabledGrant);
      if (path.endsWith("/admin/users")) return jsonResponse([disabledReader]);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "acl-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith(`/admin/documents/${documentDetail.document.id}/acl`) && init?.method === "PATCH") {
        submittedGrantIds = JSON.parse(String(init.body)).grantedUserIds;
        return jsonResponse({ ...detailWithDisabledGrant, grantedUsers: [] });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);

    expect(await screen.findByText("reader 已禁用或不再是普通用户，保存后将从授权列表移除。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "保存权限" }));

    expect(await screen.findByText("文档信息与访问权限已更新")).toBeInTheDocument();
    expect(submittedGrantIds).toEqual([]);
  });

  it("matches backend download statuses and keeps detail visible when a download is rejected", async () => {
    const failedUploadRevision = {
      ...documentDetail.revisions[0],
      id: "30000000-0000-0000-0000-000000000002",
      revisionNumber: 6,
      status: "UPLOAD_FAILED",
      originalFilename: "failed.pdf",
      effective: false,
    } as const;
    const phaseFourRevisions = (["PROCESSING", "READY", "FAILED", "QUARANTINED"] as const).map((status, index) => ({
      ...documentDetail.revisions[0],
      id: `30000000-0000-0000-0000-00000000000${index + 3}`,
      revisionNumber: index + 2,
      status,
      originalFilename: `${status.toLowerCase()}.pdf`,
      effective: false,
    }));
    const detailWithFailedLatest = {
      ...documentDetail,
      document: { ...documentDetail.document, latestRevisionNumber: 6, latestRevisionStatus: "UPLOAD_FAILED" },
      revisions: [failedUploadRevision, ...phaseFourRevisions, documentDetail.revisions[0]],
    } as const;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) return jsonResponse(detailWithFailedLatest);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentDetail.document.id}/revisions/${documentDetail.revisions[0].id}/download`)) {
        return jsonResponse({ message: "版本不可下载" }, 404);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);

    const failedUploadItem = (await screen.findByText("R6")).closest("li");
    expect(failedUploadItem).not.toBeNull();
    expect(within(failedUploadItem!).queryByRole("button", { name: "下载此版本" })).not.toBeInTheDocument();
    for (const status of ["PROCESSING", "READY", "FAILED", "QUARANTINED"]) {
      const revisionItem = screen.getByText(status).closest("li");
      expect(revisionItem).not.toBeNull();
      expect(within(revisionItem!).getByRole("button", { name: "下载此版本" })).toBeEnabled();
    }
    expect(screen.getAllByRole("button", { name: "下载此版本" })).toHaveLength(5);

    const uploadedItem = screen.getByText("UPLOADED").closest("li");
    expect(uploadedItem).not.toBeNull();
    await user.click(within(uploadedItem!).getByRole("button", { name: "下载此版本" }));

    expect(await screen.findByText("版本不可下载")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "平台手册" })).toBeInTheDocument();
  });

  it("rejects an invalid PDF before uploading a new revision", async () => {
    let revisionWrites = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/document-formats")) return jsonResponse(documentFormats);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.includes("/revisions") && init?.method === "POST") revisionWrites += 1;
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup({ applyAccept: false });

    renderApp(`/documents/${documentDetail.document.id}`);
    const input = await screen.findByLabelText("上传新 Revision");
    await waitFor(() => expect(input).toBeEnabled());
    await user.upload(input, new File(["plain text"], "notes.txt", { type: "text/plain" }));

    expect(screen.getByText("请选择支持的文件：.pdf")).toBeInTheDocument();
    const oversizedPdf = new File(["%PDF-1.7"], "oversized.pdf", { type: "application/pdf" });
    Object.defineProperty(oversizedPdf, "size", { value: 50 * 1024 * 1024 + 1 });
    await user.upload(input, oversizedPdf);
    expect(screen.getByText("PDF 必须非空且不超过 50 MiB")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上传新版本" })).toBeDisabled();
    expect(revisionWrites).toBe(0);
  });

  it("downloads a revision through the authenticated backend endpoint", async () => {
    const createObjectUrl = vi.fn(() => "blob:document");
    const revokeObjectUrl = vi.fn();
    const NativeUrl = URL;
    class DownloadUrl extends NativeUrl {
      static createObjectURL = createObjectUrl;
      static revokeObjectURL = revokeObjectUrl;
    }
    vi.stubGlobal("URL", DownloadUrl);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentDetail.document.id}/revisions/${documentDetail.revisions[0].id}/download`)) {
        return new Response("%PDF-1.7", {
          headers: {
            "Content-Disposition": "attachment; filename*=UTF-8''guide.pdf",
            "Content-Type": "application/pdf",
          },
        });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);
    await user.click(await screen.findByRole("button", { name: "下载当前可用版本" }));

    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe("guide.pdf");
    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:document");
  });

  it("confirms and tombstones a document before returning to the list", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    let deleteCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/admin/documents/${documentDetail.document.id}`) && init?.method === "DELETE") {
        deleteCalls += 1;
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("delete-csrf");
        return new Response(null, { status: 204 });
      }
      if (path.endsWith(`/documents/${documentDetail.document.id}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "delete-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.includes("/documents?")) return jsonResponse(emptyPage);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentDetail.document.id}`);
    await user.click(await screen.findByRole("button", { name: "删除文档" }));

    expect(confirm).toHaveBeenCalledOnce();
    expect(await screen.findByText("暂无可访问文档")).toBeInTheDocument();
    expect(deleteCalls).toBe(1);
  });

  it("does not let an older document-list response overwrite the latest filter", async () => {
    let resolveFirst: ((response: Response) => void) | undefined;
    let listReads = 0;
    const firstResponse = new Promise<Response>((resolve) => { resolveFirst = resolve; });
    const oldDocument = { ...documentDetail.document, id: "old-document", title: "旧结果" };
    const newDocument = { ...documentDetail.document, id: "new-document", title: "新结果" };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/documents?")) {
        listReads += 1;
        if (listReads === 1) return firstResponse;
        return jsonResponse({ ...emptyPage, items: [newDocument], totalElements: 1, totalPages: 1 });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/");
    await user.type(await screen.findByPlaceholderText("搜索文档标题"), "new");
    await user.click(screen.getByRole("button", { name: "筛选" }));
    expect(await screen.findByText("新结果")).toBeInTheDocument();

    await act(async () => resolveFirst?.(jsonResponse({ ...emptyPage, items: [oldDocument], totalElements: 1, totalPages: 1 })));
    await waitFor(() => expect(screen.queryByText("旧结果")).not.toBeInTheDocument());
    expect(screen.getByText("新结果")).toBeInTheDocument();
  });

  it("never exposes document write controls to a regular user", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(regularUser);
      if (path.includes("/documents?")) return jsonResponse({ ...emptyPage, items: [documentDetail.document], totalElements: 1 });
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp("/");
    expect(await screen.findByText("平台手册")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "上传文档" })).not.toBeInTheDocument();
  });

  it("shows evaluation provenance without an extra document request", async () => {
    const evaluationDocument = {
      ...documentDetail.document,
      title: "[EVAL][PUBLIC] XRAG · Euro 2024",
      visibility: "ALL_USERS",
      effectiveEvaluationProvenance: {
        suiteVersion: "graph-global-golden-v1",
        evidenceKey: "a".repeat(64),
        sourceDataset: "xrag",
        sourceTitle: "Euro 2024 final",
        sourceUrl: "https://example.com/euro-2024",
        sourceLicense: "CC-BY-NC-4.0",
        sourceRevision: "ead86612ac265e578ffee6f838c2180fec6428d9",
        sourceContentHash: "b".repeat(64),
      },
    } as const;
    let listReads = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(regularUser);
      if (path.includes("/documents?")) {
        listReads += 1;
        return jsonResponse({
          ...emptyPage,
          items: [evaluationDocument],
          totalElements: 1,
          totalPages: 1,
        });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp("/");

    expect(await screen.findByText(/CC-BY-NC-4\.0/)).toBeInTheDocument();
    expect(screen.getByText("[EVAL][PUBLIC]", { selector: ".eval-public-badge" }))
      .toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Euro 2024 final" }))
      .toHaveAttribute("href", "https://example.com/euro-2024");
    expect(listReads).toBe(1);
  });
});
