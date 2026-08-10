import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  Bm25DebugResponse,
  IndexGenerationsResponse,
  ModelServicesHealth,
  RetrievalConfiguration,
  SearchPage,
} from "./types";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const child = {
  chunkId: "30000000-0000-0000-0000-000000000001",
  documentId: "20000000-0000-0000-0000-000000000001",
  documentTitle: "Phase 6C 设计",
  revisionId: "21000000-0000-0000-0000-000000000001",
  revisionNumber: 1,
  headingPath: ["检索", "Evidence"],
  startPage: 3,
  endPage: 3,
  snippet: "Child Chunk 是引用锚点。",
  evidence: {
    rank: 1,
    retrievalScore: 0.031746,
    rerankScore: 0.9821,
    childText: "Child Chunk 是引用锚点。",
    childTokenCount: 42,
    parent: {
      chunkId: "30000000-0000-0000-0000-000000000002",
      text: "Parent 提供受预算约束的上下文。",
      headingPath: ["检索"],
      startPage: 2,
      endPage: 4,
      tokenCount: 1100,
      contributedTokens: 800,
      truncated: true,
    },
    graphPaths: [],
  },
};

const searchResult: SearchPage = {
  items: [child],
  page: 0,
  size: 10,
  totalElements: 1,
  totalPages: 1,
  tookMs: 812,
  profileVersion: "phase6c-hybrid-rerank-v1",
  indexGeneration: 2,
  modeRequested: "HYBRID",
  modeUsed: "HYBRID",
  degraded: false,
  degradationCode: null,
  totalRelation: "EXACT",
  graphProfileVersion: "phase9-local-v1",
  graphGeneration: 1,
  graphModeRequested: "AUTO",
  graphModeUsed: "LOCAL_GRAPH",
  graphDegraded: false,
  graphDegradationCode: null,
};

const configuration: RetrievalConfiguration = {
  currentPublication: {
    profileVersion: "phase6c-hybrid-rerank-v1",
    publicationEventId: "60000000-0000-0000-0000-000000000001",
    publishedAt: "2026-07-24T10:00:00Z",
  },
  activeManifest: {
    indexGeneration: 2,
    indexName: "rag-child-chunks-hybrid-2",
    indexConfigVersion: "phase6-hybrid-qwen3-v1",
    status: "ACTIVE",
  },
  profiles: [{
    version: "phase6c-hybrid-rerank-v1",
    mode: "HYBRID",
    defaultPageSize: 20,
    maxPageSize: 50,
    bm25TopK: 50,
    vectorTopK: 50,
    rrfRankConstant: 60,
    rerankTopK: 30,
    evidenceTopK: 8,
    parentTokenBudget: 6000,
    createdAt: "2026-07-24T10:00:00Z",
  }],
  indexConfigs: [{
    version: "phase6-hybrid-qwen3-v1",
    schemaVersion: "phase5-bm25-v1",
    analyzer: "cjk+english-multifield",
    embeddingProviderKey: "openai-compatible",
    embeddingModel: "Qwen/Qwen3-Embedding-0.6B",
    embeddingRevision: "embedding-revision",
    vectorDimensions: 1024,
    embeddingInputFormatVersion: "raw-text-v1",
    embeddingNormalizationVersion: "none-v1",
    distance: "COSINE",
    hnswM: 16,
    hnswEfConstruction: 128,
    createdAt: "2026-07-24T10:00:00Z",
  }],
  goldenBaseline: {
    datasetVersion: "retrieval-golden-v2",
    caseCount: 48,
    status: "NOT_RUN",
    generatedAt: null,
    reportAvailable: false,
    slices: [],
  },
};

const generations: IndexGenerationsResponse = {
  activeGeneration: 2,
  generations: [],
};
const embeddingCacheStats = {
  query: { entries: 0, maxEntries: 2048, hits: 0, misses: 0, evictions: 0, coalesced: 0, modelCalls: 0, savedModelCalls: 0 },
  artifacts: { entries: 0, bytes: 0, maxBytes: 2147483648, hits: 0, misses: 0, evictions: 0, corruptions: 0, modelCalls: 0, savedModelCalls: 0 },
  models: [],
  checkedAt: "2026-07-24T10:00:00Z",
};
const modelHealth: ModelServicesHealth = { services: [] };

const debugResult: Bm25DebugResponse = {
  query: "引用锚点",
  retrievalProfile: "phase6c-hybrid-rerank-v1",
  indexName: "rag-child-chunks-hybrid-2",
  indexGeneration: 2,
  modeRequested: "HYBRID",
  modeUsed: "HYBRID",
  degraded: true,
  degradationCode: "VECTOR_UNAVAILABLE",
  graphProfileVersion: "phase9-local-v1",
  graphGeneration: 1,
  graphModeRequested: "AUTO",
  graphModeUsed: "LOCAL_GRAPH",
  graphDegraded: false,
  graphDegradationCode: null,
  tookMs: 934,
  stages: [
    { name: "BM25", status: "SUCCESS", inputCount: 0, outputCount: 50, tookMs: 40, code: null },
    { name: "VECTOR", status: "DEGRADED", inputCount: 0, outputCount: 0, tookMs: 120, code: "VECTOR_UNAVAILABLE" },
    { name: "RRF", status: "SUCCESS", inputCount: 50, outputCount: 30, tookMs: 1, code: null },
    { name: "ACL_REVISION", status: "SUCCESS", inputCount: 30, outputCount: 28, tookMs: 5, code: null },
    { name: "RERANK", status: "SUCCESS", inputCount: 28, outputCount: 28, tookMs: 620, code: null },
    { name: "EVIDENCE", status: "SUCCESS", inputCount: 28, outputCount: 8, tookMs: 0, code: null },
    { name: "PARENT", status: "SUCCESS", inputCount: 8, outputCount: 8, tookMs: 4, code: null },
    { name: "ACL_FINAL", status: "SUCCESS", inputCount: 8, outputCount: 8, tookMs: 4, code: null },
  ],
  contextBudget: {
    limitTokens: 6000,
    childTokens: 480,
    parentTokens: 2400,
    totalTokens: 2880,
    parentCount: 3,
    graphTokens: 0,
    graphPathCount: 0,
    trimReasons: ["PARENT_TOKEN_LIMIT"],
  },
  candidates: [{
    rank: 1,
    score: 0.9821,
    bm25Rank: 1,
    vectorRank: null,
    rrfScore: 0.016393,
    rerankRank: 1,
    rerankScore: 0.9821,
    evidenceRank: 1,
    matchedFields: ["text.zh"],
    accepted: true,
    rejectionReason: null,
    result: child,
  }],
  result: { ...searchResult, degraded: true, degradationCode: "VECTOR_UNAVAILABLE" },
};

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  );
}

describe("Phase 6C retrieval UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("shows the Child citation anchor and its budgeted Parent context", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase6c-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/search")) return jsonResponse(searchResult);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage("/search");
    await user.type(await screen.findByPlaceholderText("输入中文、English 或混合查询"), "引用锚点");
    await user.click(screen.getByRole("button", { name: "搜索" }));

    expect(await screen.findByText("Evidence #1")).toBeInTheDocument();
    expect(screen.getByText("Rerank 0.9821")).toBeInTheDocument();
    const parent = screen.getByText(/Parent 上下文.*800 tokens/);
    await user.click(parent);
    expect(screen.getByText("Parent 提供受预算约束的上下文。")).toBeVisible();
    expect(screen.getByText("已按单 Parent 800 Token 上限确定性裁剪")).toBeInTheDocument();
  });

  it("renders every secure retrieval stage, degradation, and context budget", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase6c-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/retrieval/configuration")) return jsonResponse(configuration);
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/admin/search/debug") && init?.method === "POST") {
        return jsonResponse(debugResult);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage("/admin/retrieval");
    await user.type(await screen.findByPlaceholderText("输入中文、English 或混合查询"), "引用锚点");
    await user.click(screen.getByRole("button", { name: "执行调试" }));

    expect(await screen.findByText("安全降级：VECTOR_UNAVAILABLE")).toBeInTheDocument();
    const stages = screen.getByRole("list", { name: "检索阶段" });
    for (const name of ["BM25", "VECTOR", "RRF", "ACL_REVISION", "RERANK", "EVIDENCE", "PARENT", "ACL_FINAL"]) {
      expect(within(stages).getByText(name)).toBeInTheDocument();
    }
    expect(screen.getByText("Child 480 + Parent 2400 + Graph 0 = 2880 / 6000 tokens")).toBeInTheDocument();
    expect(screen.getByText("PARENT_TOKEN_LIMIT")).toBeInTheDocument();
    expect(screen.getByText("#1 · 0.9821")).toBeInTheDocument();
    expect(screen.getByText("Parent · 第 2–4 页 · 800 tokens · 已裁剪")).toBeInTheDocument();
  });
});
