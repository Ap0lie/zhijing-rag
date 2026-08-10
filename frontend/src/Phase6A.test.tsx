import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  IndexGenerationsResponse,
  ModelServicesHealth,
  RetrievalConfiguration,
  RetrievalProfile,
} from "./types";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const bm25Profile: RetrievalProfile = {
  version: "phase5-bm25-v1",
  mode: "BM25",
  defaultPageSize: 20,
  maxPageSize: 50,
  bm25TopK: 50,
  vectorTopK: 0,
  rrfRankConstant: 60,
  rerankTopK: 0,
  evidenceTopK: 8,
  parentTokenBudget: 0,
  createdAt: "2026-07-23T10:00:00Z",
};

const configuration: RetrievalConfiguration = {
  currentPublication: {
    profileVersion: bm25Profile.version,
    publicationEventId: "60000000-0000-0000-0000-000000000001",
    publishedAt: "2026-07-23T10:00:00Z",
  },
  activeManifest: {
    indexGeneration: 1,
    indexName: "rag-child-chunks-phase5-bm25-v1",
    indexConfigVersion: "phase5-bm25-index-v1",
    status: "ACTIVE",
  },
  profiles: [bm25Profile],
  indexConfigs: [{
    version: "phase5-bm25-index-v1",
    schemaVersion: "phase5-bm25-v1",
    analyzer: "cjk+english",
    embeddingProviderKey: "openai-compatible",
    embeddingModel: null,
    embeddingRevision: null,
    vectorDimensions: null,
    embeddingInputFormatVersion: "raw-text-v1",
    embeddingNormalizationVersion: "none-v1",
    distance: null,
    hnswM: null,
    hnswEfConstruction: null,
    createdAt: "2026-07-23T10:00:00Z",
  }],
  goldenBaseline: {
    datasetVersion: "phase5-golden-v1",
    caseCount: 9,
    status: "NOT_RUN",
    generatedAt: null,
    reportAvailable: false,
    slices: [
      { name: "zh", caseCount: 3, candidateHitAt50: null },
      { name: "en", caseCount: 3, candidateHitAt50: null },
      { name: "mixed", caseCount: 3, candidateHitAt50: null },
    ],
  },
};

const modelHealth: ModelServicesHealth = {
  services: [{
    type: "EMBEDDING",
    status: "UP",
    model: "Qwen/Qwen3-Embedding-0.6B",
    revision: "embedding-revision",
    dimensions: 1024,
    latencyMs: 48,
    checkedAt: "2026-07-23T10:01:00Z",
    errorCode: null,
  }, {
    type: "RERANK",
    status: "DISABLED",
    model: "Qwen/Qwen3-Reranker-0.6B",
    revision: "reranker-revision",
    dimensions: null,
    latencyMs: null,
    checkedAt: "2026-07-23T10:01:00Z",
    errorCode: null,
  }],
};

const generations: IndexGenerationsResponse = {
  activeGeneration: 1,
  generations: [{
    id: "50000000-0000-0000-0000-000000000001",
    indexGeneration: 1,
    indexName: "rag-child-chunks-phase5-bm25-v1",
    indexConfigVersion: "phase5-bm25-index-v1",
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

const embeddingCacheStats = {
  query: { entries: 0, maxEntries: 2048, hits: 0, misses: 0, evictions: 0, coalesced: 0, modelCalls: 0, savedModelCalls: 0 },
  artifacts: { entries: 0, bytes: 0, maxBytes: 2147483648, hits: 0, misses: 0, evictions: 0, corruptions: 0, modelCalls: 0, savedModelCalls: 0 },
  models: [],
  checkedAt: "2026-07-24T10:00:00Z",
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/admin/retrieval"]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  );
}

describe("Phase 6A retrieval administration UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("loads volatile model health without waiting for stable configuration", async () => {
    let resolveConfiguration!: (response: Response) => void;
    const pendingConfiguration = new Promise<Response>((resolve) => {
      resolveConfiguration = resolve;
    });
    const requested: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      requested.push(path);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/retrieval/configuration")) return pendingConfiguration;
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderPage();

    expect(await screen.findByText("Qwen/Qwen3-Embedding-0.6B")).toBeInTheDocument();
    expect(screen.getByText("正在读取检索配置")).toBeInTheDocument();
    expect(requested).toEqual(expect.arrayContaining([
      "/api/v1/admin/retrieval/configuration",
      "/api/v1/admin/model-services/health",
    ]));

    await act(async () => resolveConfiguration(jsonResponse(configuration)));
    expect(await screen.findByText("RetrievalProfile")).toBeInTheDocument();
    expect(screen.getByText("1024")).toBeInTheDocument();
    expect(screen.getByText("报告未生成")).toBeInTheDocument();
    expect(screen.getAllByText("phase5-bm25-v1").length).toBeGreaterThan(0);
  });

  it("keeps model status visible while configuration fails and retries only that section", async () => {
    let configurationReads = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/retrieval/configuration")) {
        configurationReads += 1;
        return configurationReads === 1
          ? jsonResponse({ code: "CONFIG_UNAVAILABLE", message: "配置暂不可用" }, 503)
          : jsonResponse(configuration);
      }
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();

    expect(await screen.findByText("配置暂不可用")).toBeInTheDocument();
    expect(screen.getByText("Qwen/Qwen3-Embedding-0.6B")).toBeInTheDocument();
    const configurationPanel = screen.getByRole("heading", { name: "检索配置" }).closest("section");
    expect(configurationPanel).not.toBeNull();
    await user.click(within(configurationPanel!).getByRole("button", { name: "重试" }));

    expect(await screen.findByRole("heading", { name: "IndexConfig" })).toBeInTheDocument();
    expect(configurationReads).toBe(2);
  });

  it("creates an immutable profile without changing the current publication", async () => {
    let submitted: Record<string, unknown> | null = null;
    const created: RetrievalProfile = {
      ...bm25Profile,
      version: "phase6-hybrid-v1",
      mode: "HYBRID",
      vectorTopK: 50,
      rerankTopK: 30,
      parentTokenBudget: 6000,
      createdAt: "2026-07-23T10:05:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/retrieval/configuration")) return jsonResponse(configuration);
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase6-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/retrieval/profiles") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("phase6-csrf");
        submitted = JSON.parse(String(init.body)) as Record<string, unknown>;
        return jsonResponse(created);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByText("创建不可变 Profile"));
    await user.type(screen.getByPlaceholderText("例如 phase6-hybrid-v1"), "phase6-hybrid-v1");
    await user.click(screen.getByRole("button", { name: "创建 Profile" }));

    expect(await screen.findByText("已创建 phase6-hybrid-v1；当前发布 Profile 未改变")).toBeInTheDocument();
    expect(submitted).toMatchObject({
      version: "phase6-hybrid-v1",
      mode: "HYBRID",
      bm25TopK: 50,
      vectorTopK: 50,
      rrfRankConstant: 60,
      rerankTopK: 30,
      evidenceTopK: 8,
      parentTokenBudget: 6000,
    });
    expect(screen.getByText("phase6-hybrid-v1", { selector: "td strong" })).toBeInTheDocument();
    expect(screen.getAllByText("phase5-bm25-v1").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: /发布.*phase6-hybrid-v1/ })).not.toBeInTheDocument();
  });

  it("shows a model failure as sanitized status without breaking BM25 controls", async () => {
    const down: ModelServicesHealth = {
      services: modelHealth.services.map((service) => service.type === "RERANK"
        ? { ...service, status: "DOWN", errorCode: "MODEL_UNAVAILABLE", latencyMs: 200 }
        : service),
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/retrieval/configuration")) return jsonResponse(configuration);
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(down);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderPage();

    expect(await screen.findByText("MODEL_UNAVAILABLE")).toBeInTheDocument();
    expect(screen.getByText("不可用")).toBeInTheDocument();
    expect(screen.getByText("第 1 代")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "执行调试" })).toBeEnabled());
  });
});
