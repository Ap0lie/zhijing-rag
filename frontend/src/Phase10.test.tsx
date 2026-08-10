import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  ChatSessionDetail,
  GlobalClaimEvidence,
  GlobalCommunityReportDetail,
  GlobalGraphOverview,
  GraphOverview,
  GraphRetrievalConfiguration,
  SearchPage,
} from "./types";

const user = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "user",
  role: "USER",
} as const;

const admin = { ...user, username: "admin", role: "ADMIN" } as const;

const claim: GlobalClaimEvidence = {
  reportId: "20000000-0000-0000-0000-000000000001",
  reportTitle: "平台趋势",
  communityKey: 7,
  claimId: "30000000-0000-0000-0000-000000000001",
  claimText: "混合检索与图谱检索互为补充。",
  supportingChunkId: "40000000-0000-0000-0000-000000000001",
  sourceSpanId: "50000000-0000-0000-0000-000000000001",
  documentId: "60000000-0000-0000-0000-000000000001",
  documentTitle: "RAG 报告",
  startPage: 4,
  endPage: 4,
  evidenceText: "混合检索负责基础召回，图谱检索补充关系证据。",
  contributedTokens: 28,
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function streamResponse(events: { type: string; data: unknown }[]) {
  const encoder = new TextEncoder();
  const payload = events
    .map((event) => `event: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`)
    .join("");
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(encoder.encode(payload));
      controller.close();
    },
  }), {
    headers: {
      "Content-Type": "text/event-stream",
      "X-Chat-Run-Id": "70000000-0000-0000-0000-000000000001",
    },
  });
}

function renderPath(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  );
}

describe("Phase 10 Global Graph UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("requests GLOBAL_GRAPH and renders only Child-backed global claims", async () => {
    const result: SearchPage = {
      items: [{
        chunkId: claim.supportingChunkId,
        documentId: claim.documentId,
        documentTitle: claim.documentTitle,
        revisionId: "80000000-0000-0000-0000-000000000001",
        revisionNumber: 1,
        headingPath: ["检索", "趋势"],
        startPage: 4,
        endPage: 4,
        snippet: claim.evidenceText,
        evidence: {
          rank: 1,
          retrievalScore: 0.92,
          rerankScore: 0.97,
          childText: claim.evidenceText,
          childTokenCount: 28,
          parent: null,
          graphPaths: [],
          globalClaims: [claim],
        },
      }],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      tookMs: 320,
      profileVersion: "phase6c-hybrid-rerank-v1",
      indexGeneration: 4,
      modeRequested: "HYBRID",
      modeUsed: "HYBRID",
      degraded: false,
      degradationCode: null,
      totalRelation: "EXACT",
      graphProfileVersion: "phase9-local-v1",
      graphGeneration: 2,
      graphModeRequested: "GLOBAL_GRAPH",
      graphModeUsed: "LOCAL_GRAPH",
      graphDegraded: true,
      graphDegradationCode: "GLOBAL_REPORT_UNAVAILABLE",
      globalExecution: {
        configVersion: "phase10-global-v1",
        globalGeneration: 3,
        reportCount: 1,
        reportLimit: 8,
        modelCallLimit: 9,
        hardTimeoutMs: 30000,
        shadow: false,
      },
    };
    let requestBody: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase10-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/search")) {
        requestBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return jsonResponse(result);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPath("/search");
    await actor.selectOptions(
      await screen.findByLabelText("GraphRAG 检索模式"),
      "GLOBAL_GRAPH",
    );
    await actor.type(
      screen.getByPlaceholderText("输入中文、English 或混合查询"),
      "平台整体趋势",
    );
    await actor.click(screen.getByRole("button", { name: "搜索" }));

    expect(requestBody).toMatchObject({ graphModeRequested: "GLOBAL_GRAPH" });
    expect((await screen.findAllByText(/Graph GLOBAL_GRAPH → LOCAL_GRAPH/)).length)
      .toBeGreaterThan(0);
    expect(screen.getByText(/GLOBAL_REPORT_UNAVAILABLE/)).toBeInTheDocument();
    expect(screen.getByText(claim.claimText)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看 Evidence Child" }))
      .toHaveAttribute("href", `/chunks/${claim.supportingChunkId}`);
  });

  it("sends DEEP_GLOBAL only with GLOBAL_GRAPH and shows its terminal budget", async () => {
    const session = {
      id: "90000000-0000-0000-0000-000000000001",
      title: "全局分析",
      status: "ACTIVE",
      createdAt: "2026-07-26T10:00:00Z",
      updatedAt: "2026-07-26T10:00:00Z",
    } as const;
    const detail: ChatSessionDetail = { ...session, messages: [], runs: [] };
    let requestBody: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase10-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/chat/sessions") && (!init?.method || init.method === "GET")) {
        return jsonResponse({ items: [session] });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && (!init?.method || init.method === "GET")) {
        return jsonResponse(detail);
      }
      if (path.endsWith(`/chat/sessions/${session.id}/runs`) && init?.method === "POST") {
        requestBody = JSON.parse(String(init.body)) as Record<string, unknown>;
        return streamResponse([
          {
            type: "answer_delta",
            data: {
              runId: "70000000-0000-0000-0000-000000000001",
              text: "全局趋势已汇总。",
            },
          },
          {
            type: "completed",
            data: {
              runId: "70000000-0000-0000-0000-000000000001",
              messageId: "71000000-0000-0000-0000-000000000001",
              status: "COMPLETED",
              refusalCode: null,
              graphProfileVersion: "phase9-local-v1",
              graphGeneration: 2,
              graphModeRequested: "GLOBAL_GRAPH",
              graphModeUsed: "GLOBAL_GRAPH",
              graphDegraded: false,
              graphDegradationCode: null,
              globalConfigVersion: "phase10-global-v1",
              globalGeneration: 3,
              answerStrategyRequested: "DEEP_GLOBAL",
              answerStrategyUsed: "DEEP_GLOBAL",
              mapCallCount: 2,
              reduceCallCount: 1,
            },
          },
        ]);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPath("/chat");
    await actor.selectOptions(await screen.findByLabelText("检索模式"), "GLOBAL_GRAPH");
    await actor.selectOptions(screen.getByLabelText("回答策略"), "DEEP_GLOBAL");
    expect(screen.getByText(/会进行多轮资料归纳，最长等待 30 秒/)).toBeInTheDocument();
    await actor.type(screen.getByLabelText("问题"), "总结整体趋势");
    await actor.click(screen.getByRole("button", { name: "发送问题" }));

    expect(requestBody).toEqual({
      question: "总结整体趋势",
      graphModeRequested: "GLOBAL_GRAPH",
      answerStrategyRequested: "DEEP_GLOBAL",
    });
    expect(await screen.findByText("全局趋势已汇总。")).toBeInTheDocument();
    expect(screen.getByText("本次深度全局分析完成 2 次资料归纳和 1 次综合生成。"))
      .toBeInTheDocument();
  });

  it("explains that standard Global analysis fuses public reports with authorized documents", async () => {
    const session = {
      id: "90000000-0000-0000-0000-000000000009",
      title: "全局分析证据融合",
      status: "ACTIVE",
      createdAt: "2026-08-04T07:48:00Z",
      updatedAt: "2026-08-04T07:49:00Z",
    } as const;
    const runId = "70000000-0000-0000-0000-000000000009";
    const detail: ChatSessionDetail = {
      ...session,
      messages: [{
        id: "71000000-0000-0000-0000-000000000009",
        role: "ASSISTANT",
        status: "COMPLETED",
        content: "概括结果。[1]",
        language: "zh",
        runId,
        hidden: false,
        createdAt: "2026-08-04T07:49:00Z",
        citations: [],
      }],
      runs: [{
        id: runId,
        status: "COMPLETED",
        errorCode: null,
        graphProfileVersion: null,
        graphGeneration: null,
        graphModeRequested: "AUTO",
        graphModeUsed: "GLOBAL_GRAPH",
        graphDegraded: false,
        graphDegradationCode: null,
        globalConfigVersion: "phase10-global-v1",
        globalGeneration: 3,
        answerStrategyRequested: "STANDARD",
        answerStrategyUsed: "STANDARD",
        mapCallCount: 0,
        reduceCallCount: 0,
        createdAt: "2026-08-04T07:48:00Z",
        completedAt: "2026-08-04T07:49:00Z",
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/chat/sessions")) return jsonResponse({ items: [session] });
      if (path.endsWith(`/chat/sessions/${session.id}`)) return jsonResponse(detail);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPath("/chat");
    await actor.click(await screen.findByText(/运行详情 · 智能选择 → 全局分析/));
    expect(screen.getByText(/公共报告定位主题，再与当前权限范围内的文档候选融合并复核/))
      .toBeInTheDocument();
    expect(screen.queryByText("本次全局分析使用了已发布的公共报告。"))
      .not.toBeInTheDocument();
  });

  it("browses the independent ALL_USERS report and its exact evidence", async () => {
    const localOverview: GraphOverview = {
      activeGeneration: null,
      extraction: {
        enabled: false,
        model: "",
        revision: "",
        promptVersion: "phase8-graph-prompt-v1",
        schemaVersion: "phase8-graph-schema-v1",
      },
      configs: [],
      generations: [],
    };
    const retrieval: GraphRetrievalConfiguration = {
      currentPublication: {
        profileVersion: "phase9-local-v1",
        publicationEventId: 1,
        publishedAt: "2026-07-26T10:00:00Z",
      },
      activeGraphGeneration: null,
      profiles: [],
    };
    const globalOverview: GlobalGraphOverview = {
      activeGeneration: 3,
      runtime: {
        enabled: true,
        model: "deepseek-chat",
        revision: "2026-07",
        promptVersion: "phase10-global-prompt-v1",
        schemaVersion: "phase10-global-schema-v1",
      },
      configs: [{
        version: "phase10-global-v1",
        reportModel: "deepseek-chat",
        reportRevision: "2026-07",
        promptVersion: "phase10-global-prompt-v1",
        schemaVersion: "phase10-global-schema-v1",
        communityAlgorithm: "leidenalg",
        communityAlgorithmVersion: "0.10.2",
        communitySeed: 42,
        communityResolution: 1,
        indexConfigVersion: "phase6-hybrid-qwen3-v1",
        bm25TopK: 50,
        vectorTopK: 50,
        rrfRankConstant: 60,
        reportLimit: 8,
        contextTokenBudget: 2400,
        mapCallLimit: 8,
        modelCallLimit: 9,
        hardTimeoutMs: 30000,
        statementTimeoutMs: 500,
        reason: "public reports",
        runtimeCompatible: true,
        createdAt: "2026-07-26T10:00:00Z",
      }],
      generations: [{
        id: "a0000000-0000-0000-0000-000000000001",
        globalGeneration: 3,
        globalConfigVersion: "phase10-global-v1",
        sourceGraphGeneration: 2,
        indexName: "rag-global-3",
        status: "ACTIVE",
        expectedSourceCount: 1,
        reportCount: 1,
        claimCount: 1,
        evidenceCount: 1,
        indexedReportCount: 1,
        validVectorCount: 1,
        modelCallCount: 1,
        caughtUp: true,
        buildAttempt: 1,
        failureCode: null,
        failureReason: null,
        buildReason: "public reports",
        createdAt: "2026-07-26T10:00:00Z",
        startedAt: "2026-07-26T10:00:01Z",
        completedAt: "2026-07-26T10:00:05Z",
        retentionUntil: null,
        updatedAt: "2026-07-26T10:00:05Z",
      }],
    };
    const detail: GlobalCommunityReportDetail = {
      report: {
        id: claim.reportId,
        globalGeneration: 3,
        communityKey: 7,
        title: claim.reportTitle,
        summary: "公共报告摘要",
        tokenCount: 128,
        claimCount: 1,
        evidenceCount: 1,
      },
      claims: [{
        id: claim.claimId,
        order: 0,
        claimText: claim.claimText,
        evidence: [{
          id: "b0000000-0000-0000-0000-000000000001",
          documentId: claim.documentId,
          documentTitle: claim.documentTitle,
          revisionId: "80000000-0000-0000-0000-000000000001",
          revisionNumber: 1,
          childChunkId: claim.supportingChunkId,
          sourceSpanId: claim.sourceSpanId,
          evidenceText: claim.evidenceText,
          startPage: 4,
          endPage: 4,
        }],
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/graph/retrieval")) return jsonResponse(retrieval);
      if (path.endsWith("/admin/graph/global")) return jsonResponse(globalOverview);
      if (path.includes(`/admin/graph/global/reports/${claim.reportId}?`)) {
        return jsonResponse(detail);
      }
      if (path.includes("/admin/graph/global/reports?")) {
        return jsonResponse({
          globalGeneration: 3,
          page: 0,
          size: 20,
          total: 1,
          items: [detail.report],
        });
      }
      if (path.endsWith("/admin/graph")) return jsonResponse(localOverview);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPath("/admin/graph");
    await actor.click(await screen.findByRole("button", { name: /平台趋势/ }));

    expect(await screen.findByText("公共报告摘要")).toBeInTheDocument();
    expect(screen.getByText(claim.claimText)).toBeInTheDocument();
    expect(screen.getByText(claim.evidenceText)).toBeInTheDocument();
    expect(screen.getByText(/SourceSpan 50000000…/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Evidence Child" }))
      .toHaveAttribute("href", `/chunks/${claim.supportingChunkId}`);
  });
});
