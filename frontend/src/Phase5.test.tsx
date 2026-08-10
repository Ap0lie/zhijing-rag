import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import { clearSearchContinuityCache } from "./searchContinuityCache";
import type { Bm25DebugResponse, ChunkContext, IndexGenerationsResponse, SearchPage } from "./types";

const admin = { id: "10000000-0000-0000-0000-000000000001", username: "admin", role: "ADMIN" } as const;
const reader = { id: "10000000-0000-0000-0000-000000000002", username: "reader", role: "USER" } as const;
const documentId = "20000000-0000-0000-0000-000000000001";
const revisionId = "30000000-0000-0000-0000-000000000001";
const chunkId = "40000000-0000-0000-0000-000000000001";

const searchPage: SearchPage = {
  items: [{
    chunkId,
    documentId,
    documentTitle: "检索平台手册",
    revisionId,
    revisionNumber: 2,
    headingPath: ["检索", "BM25"],
    startPage: 3,
    endPage: 4,
    snippet: "<img src=x onerror=alert(1)> 中文 BM25 片段",
  }],
  page: 0,
  size: 10,
  totalElements: 11,
  totalPages: 2,
  tookMs: 18,
  profileVersion: "phase5-bm25-v1",
  indexGeneration: 1,
  modeRequested: "BM25",
  modeUsed: "BM25",
  degraded: false,
  degradationCode: null,
  totalRelation: "EXACT",
  graphProfileVersion: null,
  graphGeneration: null,
  graphModeRequested: "HYBRID",
  graphModeUsed: "HYBRID",
  graphDegraded: true,
  graphDegradationCode: "GRAPH_REQUIRES_RERANK_PROFILE",
};

const chunkContext: ChunkContext = {
  documentId,
  documentTitle: "检索平台手册",
  revisionId,
  revisionNumber: 2,
  child: {
    id: chunkId,
    type: "CHILD",
    order: 4,
    text: "仅授权用户可见的 Child 正文",
    headingPath: ["检索", "BM25"],
    startPage: 3,
    endPage: 4,
    tokenCount: 18,
  },
  parent: {
    id: "50000000-0000-0000-0000-000000000001",
    type: "PARENT",
    order: 2,
    text: "Parent 上下文正文",
    headingPath: ["检索"],
    startPage: 2,
    endPage: 5,
    tokenCount: 20,
  },
  sourceSpans: [{
    order: 0,
    startPage: 3,
    endPage: 4,
    startOffset: 12,
    endOffset: 42,
    chunkStartOffset: 0,
    chunkEndOffset: 30,
    sourceTextHash: "a".repeat(64),
  }],
};

const generations: IndexGenerationsResponse = {
  activeGeneration: 1,
  generations: [{
    id: "50000000-0000-0000-0000-000000000001",
    indexName: "rag-chunks-v1",
    indexGeneration: 1,
    indexConfigVersion: "phase5-bm25-v1",
    status: "ACTIVE",
    expectedDocumentCount: 3,
    expectedChunkCount: 24,
    indexedChunkCount: 24,
    validVectorCount: 0,
    vectorCoverage: 0,
    readyCheckPassed: true,
    buildAttempt: 1,
    failureCode: null,
    failureReason: null,
    createdAt: "2026-07-23T10:00:00Z",
    startedAt: "2026-07-23T10:00:00Z",
    completedAt: "2026-07-23T10:00:00Z",
    retentionUntil: null,
    updatedAt: "2026-07-23T10:00:00Z",
  }],
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderApp(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>);
}

function ChunkNavigationHarness() {
  const navigate = useNavigate();
  return <><button type="button" onClick={() => navigate("/chunks/forbidden-chunk")}>打开无权 Chunk</button><App /></>;
}

describe("Phase 5 retrieval UI", () => {
  beforeEach(() => {
    resetCsrfToken();
    clearSearchContinuityCache();
  });
  afterEach(() => {
    clearSearchContinuityCache();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("submits an ACL-neutral multilingual search and renders snippets as text", async () => {
    let submittedBody: Record<string, unknown> | undefined;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(reader);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "search-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/api/v1/search") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("search-csrf");
        submittedBody = JSON.parse(String(init.body)) as Record<string, unknown>;
        return jsonResponse(searchPage);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/search");
    await user.type(await screen.findByPlaceholderText("输入中文、English 或混合查询"), "中文 BM25 retrieval");
    await user.selectOptions(screen.getByLabelText("筛选文档可见性"), "RESTRICTED");
    await user.click(screen.getByRole("button", { name: "搜索" }));

    expect(await screen.findByText("<img src=x onerror=alert(1)> 中文 BM25 片段")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看 Chunk" })).toHaveAttribute("href", `/chunks/${chunkId}`);
    expect(submittedBody).toEqual({
      query: "中文 BM25 retrieval",
      visibility: "RESTRICTED",
      graphModeRequested: "HYBRID",
      page: 0,
      size: 10,
    });
    expect(submittedBody).not.toHaveProperty("userId");
    expect(submittedBody).not.toHaveProperty("aclVersion");
    expect(submittedBody).not.toHaveProperty("revisionId");
    expect(screen.queryByText(/12\.3456/)).not.toBeInTheDocument();
  });

  it("restores committed search state after inspecting a Chunk", async () => {
    const submittedBodies: Array<Record<string, unknown>> = [];
    let resolveRevalidation: ((response: Response) => void) | undefined;
    const revalidation = new Promise<Response>((resolve) => {
      resolveRevalidation = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(reader);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "return-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/api/v1/search") && init?.method === "POST") {
        submittedBodies.push(JSON.parse(String(init.body)) as Record<string, unknown>);
        if (submittedBodies.length > 1) return revalidation;
        return jsonResponse(searchPage);
      }
      if (path.endsWith(`/chunks/${chunkId}`)) return jsonResponse(chunkContext);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/search");
    await user.type(await screen.findByPlaceholderText("输入中文、English 或混合查询"), "中文 retrieval");
    await user.selectOptions(screen.getByLabelText("筛选文档可见性"), "RESTRICTED");
    await user.selectOptions(screen.getByLabelText("常用检索方式"), "AUTO");
    await user.click(screen.getByRole("button", { name: "搜索" }));

    await user.click(await screen.findByRole("link", { name: "查看 Chunk" }));
    expect(await screen.findByText("仅授权用户可见的 Child 正文")).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "← 返回知识检索" }));

    expect(await screen.findByRole("searchbox", { name: "搜索知识库" }))
      .toHaveValue("中文 retrieval");
    expect(screen.getByLabelText("筛选文档可见性")).toHaveValue("RESTRICTED");
    expect(screen.getByLabelText("常用检索方式")).toHaveValue("AUTO");
    expect(await screen.findByText("已恢复上次浏览位置，正在确认最新权限与版本。"))
      .toBeInTheDocument();
    expect(screen.getAllByTestId("search-continuity-row")).toHaveLength(1);
    expect(screen.getByTestId("search-continuity-row")).toHaveAttribute("data-selected", "true");
    expect(screen.queryByText("<img src=x onerror=alert(1)> 中文 BM25 片段"))
      .not.toBeInTheDocument();
    expect(screen.queryByText("Parent 上下文正文")).not.toBeInTheDocument();

    resolveRevalidation?.(jsonResponse({
      ...searchPage,
      items: [{
        ...searchPage.items[0],
        snippet: "重新验证后的最新片段",
      }],
    }));
    expect(await screen.findByText("重新验证后的最新片段")).toBeInTheDocument();
    expect(screen.queryByTestId("search-continuity-row")).not.toBeInTheDocument();
    expect(screen.queryByText("<img src=x onerror=alert(1)> 中文 BM25 片段"))
      .not.toBeInTheDocument();
    expect(submittedBodies).toEqual([
      {
        query: "中文 retrieval",
        visibility: "RESTRICTED",
        graphModeRequested: "AUTO",
        page: 0,
        size: 10,
      },
      {
        query: "中文 retrieval",
        visibility: "RESTRICTED",
        graphModeRequested: "AUTO",
        page: 0,
        size: 10,
      },
    ]);
  });

  it("keeps cached result bodies hidden when background revalidation fails", async () => {
    let searchCount = 0;
    let rejectRevalidation: ((reason?: unknown) => void) | undefined;
    const revalidation = new Promise<Response>((_resolve, reject) => {
      rejectRevalidation = reject;
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(reader);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "failed-return-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/api/v1/search") && init?.method === "POST") {
        searchCount += 1;
        return searchCount === 1 ? jsonResponse(searchPage) : revalidation;
      }
      if (path.endsWith(`/chunks/${chunkId}`)) return jsonResponse(chunkContext);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/search");
    await user.type(await screen.findByPlaceholderText("输入中文、English 或混合查询"), "安全恢复");
    await user.click(screen.getByRole("button", { name: "搜索" }));
    await user.click(await screen.findByRole("link", { name: "查看 Chunk" }));
    await screen.findByText("仅授权用户可见的 Child 正文");
    await user.click(screen.getByRole("link", { name: "← 返回知识检索" }));

    expect(await screen.findByText("已恢复上次浏览位置，正在确认最新权限与版本。"))
      .toBeInTheDocument();
    expect(screen.queryByText("<img src=x onerror=alert(1)> 中文 BM25 片段"))
      .not.toBeInTheDocument();
    rejectRevalidation?.(new TypeError("network unavailable"));

    expect(await screen.findByText("刷新失败，已保留浏览位置；旧正文仍处于隐藏状态。"))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
    expect(screen.queryByText("<img src=x onerror=alert(1)> 中文 BM25 片段"))
      .not.toBeInTheDocument();
    expect(screen.queryByText("Parent 上下文正文")).not.toBeInTheDocument();
  });

  it("does not restore search history that belongs to another user", async () => {
    const searchRequest = vi.fn();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(reader);
      if (path.endsWith("/api/v1/search")) {
        searchRequest();
        return jsonResponse(searchPage);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    render(
      <MemoryRouter
        initialEntries={[{
          pathname: "/search",
          state: {
            search: {
              version: 1,
              ownerUserId: admin.id,
              query: "管理员查询",
              visibility: "RESTRICTED",
              graphMode: "LOCAL_GRAPH",
              page: 3,
              selectedChunkId: chunkId,
            },
          },
        }]}
      >
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("searchbox", { name: "搜索知识库" })).toHaveValue("");
    expect(screen.getByText("从一个清晰的问题开始")).toBeInTheDocument();
    expect(searchRequest).not.toHaveBeenCalled();
  });

  it("requests the next result page and shows a clear no-result state", async () => {
    const requestedPages: number[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(reader);
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "page-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/api/v1/search") && init?.method === "POST") {
        const body = JSON.parse(String(init.body)) as { page: number };
        requestedPages.push(body.page);
        if (body.page === 1) return jsonResponse({ ...searchPage, items: [], page: 1, totalElements: 0, totalPages: 0 });
        return jsonResponse(searchPage);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/search");
    await user.type(await screen.findByPlaceholderText("输入中文、English 或混合查询"), "retrieval");
    await user.click(screen.getByRole("button", { name: "搜索" }));
    await user.click(await screen.findByRole("button", { name: "下一页" }));

    expect(await screen.findByText("没有找到相关内容")).toBeInTheDocument();
    expect(requestedPages).toEqual([0, 1]);
  });

  it("removes old Chunk text immediately when the next Chunk is forbidden", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(reader);
      if (path.endsWith(`/chunks/${chunkId}`)) return jsonResponse(chunkContext);
      if (path.endsWith("/chunks/forbidden-chunk")) return jsonResponse({ code: "FORBIDDEN", message: "无权限" }, 403);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={[`/chunks/${chunkId}`]}>
        <AuthProvider><ChunkNavigationHarness /></AuthProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText("仅授权用户可见的 Child 正文")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "打开无权 Chunk" }));

    expect(screen.queryByText("仅授权用户可见的 Child 正文")).not.toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Chunk 不可访问" })).toBeInTheDocument();
    expect(screen.queryByText("Parent 上下文正文")).not.toBeInTheDocument();
  });

  it("keeps the retrieval administration route unavailable to a regular user", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/auth/me")) return jsonResponse(reader);
      throw new Error(`Unexpected request: ${String(input)}`);
    }));

    renderApp("/admin/retrieval");

    expect(await screen.findByText("没有访问权限")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "检索管理" })).not.toBeInTheDocument();
  });

  it("shows retrieval-stage candidates for an administrator", async () => {
    const debugResponse: Bm25DebugResponse = {
      query: "BM25",
      retrievalProfile: "bm25-v1",
      indexName: "rag-chunks-v1",
      indexGeneration: 1,
      modeRequested: "BM25",
      modeUsed: "BM25",
      degraded: false,
      degradationCode: null,
      graphProfileVersion: null,
      graphGeneration: null,
      graphModeRequested: "HYBRID",
      graphModeUsed: "HYBRID",
      graphDegraded: true,
      graphDegradationCode: "GRAPH_REQUIRES_RERANK_PROFILE",
      tookMs: 12,
      candidates: [{
        rank: 1,
        score: 12.3456,
        bm25Rank: 1,
        vectorRank: null,
        rrfScore: null,
        matchedFields: ["text_zh", "title"],
        accepted: true,
        rejectionReason: null,
        result: searchPage.items[0],
      }, {
        rank: 2,
        score: 4.2,
        bm25Rank: 2,
        vectorRank: null,
        rrfScore: null,
        matchedFields: ["text_zh"],
        accepted: false,
        rejectionReason: "ACL_REVOKED",
        result: null,
      }],
      result: searchPage,
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "debug-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/admin/search/debug") && init?.method === "POST") return jsonResponse(debugResponse);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/admin/retrieval");
    expect(await screen.findByText("rag-chunks-v1")).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText("输入中文、English 或混合查询"), "BM25");
    await user.click(screen.getByRole("button", { name: "执行调试" }));

    expect(await screen.findByText("12.3456")).toBeInTheDocument();
    expect(screen.getByText("bm25-v1")).toBeInTheDocument();
    expect(screen.getAllByText("#1").length).toBeGreaterThan(0);
    expect(screen.getByText("最终结果", { selector: ".debug-decision" })).toBeInTheDocument();
    const rejectedDecision = screen.getByText("ACL_REVOKED", { selector: ".debug-decision" });
    expect(rejectedDecision).toBeInTheDocument();
    expect(screen.getByText("不可展示")).toBeInTheDocument();
    const rejectedRow = rejectedDecision.closest("tr");
    expect(rejectedRow).not.toBeNull();
    expect(within(rejectedRow!).queryByRole("link")).not.toBeInTheDocument();
    expect(within(rejectedRow!).queryByText(searchPage.items[0].snippet)).not.toBeInTheDocument();
  });
});
