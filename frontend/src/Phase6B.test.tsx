import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  IndexGeneration,
  IndexGenerationsResponse,
  ModelServicesHealth,
  RetrievalConfiguration,
} from "./types";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const active: IndexGeneration = {
  id: "50000000-0000-0000-0000-000000000001",
  indexGeneration: 1,
  indexName: "rag-child-chunks-bm25",
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
  completedAt: "2026-07-23T10:01:00Z",
  retentionUntil: null,
  updatedAt: "2026-07-23T10:01:00Z",
};

const ready: IndexGeneration = {
  ...active,
  id: "50000000-0000-0000-0000-000000000002",
  indexGeneration: 2,
  indexName: "rag-child-chunks-hybrid",
  indexConfigVersion: "phase15a-hybrid-qwen3-source-locator-v1",
  status: "READY",
  validVectorCount: 24,
  vectorCoverage: 1,
  updatedAt: "2026-07-23T10:05:00Z",
};

const generations: IndexGenerationsResponse = {
  activeGeneration: 1,
  generations: [ready, active],
};

const embeddingCacheStats = {
  query: { entries: 0, maxEntries: 2048, hits: 0, misses: 0, evictions: 0, coalesced: 0, modelCalls: 0, savedModelCalls: 0 },
  artifacts: { entries: 0, bytes: 0, maxBytes: 2147483648, hits: 0, misses: 0, evictions: 0, corruptions: 0, modelCalls: 0, savedModelCalls: 0 },
  models: [],
  checkedAt: "2026-07-24T10:00:00Z",
};

const configuration: RetrievalConfiguration = {
  currentPublication: {
    profileVersion: "phase5-bm25-v1",
    publicationEventId: "60000000-0000-0000-0000-000000000001",
    publishedAt: "2026-07-23T10:00:00Z",
  },
  activeManifest: {
    indexGeneration: 1,
    indexName: active.indexName,
    indexConfigVersion: active.indexConfigVersion,
    status: "ACTIVE",
  },
  profiles: [{
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
  }, {
    version: "phase6-hybrid-rrf-v1",
    mode: "HYBRID",
    defaultPageSize: 20,
    maxPageSize: 50,
    bm25TopK: 50,
    vectorTopK: 50,
    rrfRankConstant: 60,
    rerankTopK: 0,
    evidenceTopK: 8,
    parentTokenBudget: 6000,
    createdAt: "2026-07-23T10:02:00Z",
  }],
  indexConfigs: [{
    version: "phase5-bm25-v1",
    schemaVersion: "phase5-bm25-v1",
    analyzer: "cjk+english-multifield",
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
  }, {
    version: "phase15a-hybrid-qwen3-source-locator-v1",
    schemaVersion: "source-locator-v1",
    analyzer: "cjk+english-multifield",
    embeddingProviderKey: "openai-compatible",
    embeddingModel: "Qwen/Qwen3-Embedding-0.6B",
    embeddingRevision: "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3",
    vectorDimensions: 1024,
    embeddingInputFormatVersion: "raw-text-v1",
    embeddingNormalizationVersion: "none-v1",
    distance: "COSINE",
    hnswM: 16,
    hnswEfConstruction: 128,
    createdAt: "2026-07-23T10:02:00Z",
  }],
  goldenBaseline: {
    datasetVersion: "retrieval-golden-v2",
    caseCount: 48,
    status: "PASSED",
    generatedAt: "2026-07-23T10:03:00Z",
    reportAvailable: true,
    slices: [{ name: "zh", caseCount: 22, candidateHitAt50: 0.9 }],
  },
};

const modelHealth: ModelServicesHealth = { services: [] };

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

describe("Phase 6B Generation administration UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("starts a vector Generation with an explicit confirmation and audit reason", async () => {
    let buildRequest: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase6b-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/index-builds") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("phase6b-csrf");
        buildRequest = JSON.parse(String(init.body)) as Record<string, unknown>;
        return jsonResponse({ ...ready, status: "BUILDING" }, 202);
      }
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/admin/retrieval/configuration")) return jsonResponse(configuration);
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();
    const panel = (await screen.findByRole("heading", { name: "索引版本" })).closest("section");
    expect(panel).not.toBeNull();
    await within(panel!).findByText("第 1 代");
    await user.type(within(panel!).getByPlaceholderText("记录本次构建目的"), "升级语义索引");
    await user.type(within(panel!).getByPlaceholderText("输入 BUILD"), "BUILD");
    await user.click(within(panel!).getByRole("button", { name: "构建索引版本" }));

    expect(await within(panel!).findByText("Generation 构建已进入队列；READY 后不会自动发布。"))
      .toBeInTheDocument();
    expect(buildRequest).toEqual({
      indexConfigVersion: "phase15a-hybrid-qwen3-source-locator-v1",
      reason: "升级语义索引",
      confirmation: "BUILD",
    });
  });

  it("publishes only a READY Generation with PUBLISH confirmation and an audit reason", async () => {
    let publishRequest: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase6b-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/retrieval/releases") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("phase6b-csrf");
        publishRequest = JSON.parse(String(init.body)) as Record<string, unknown>;
        return jsonResponse({ ...ready, status: "ACTIVE" });
      }
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/admin/retrieval/configuration")) return jsonResponse(configuration);
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(embeddingCacheStats);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();
    const panel = (await screen.findByRole("heading", { name: "索引版本" })).closest("section");
    expect(panel).not.toBeNull();
    await within(panel!).findByText("第 1 代");
    await user.click(within(panel!).getByRole("button", { name: "发布" }));
    expect(await within(panel!).findByText("请输入审计理由，并在确认字段输入 PUBLISH"))
      .toBeInTheDocument();
    await user.selectOptions(within(panel!).getByLabelText("发布 Profile"), "phase6-hybrid-rrf-v1");
    await user.type(within(panel!).getByPlaceholderText("发布或回滚原因"), "评测门禁通过");
    await user.type(within(panel!).getByPlaceholderText("PUBLISH 或 ROLLBACK"), "PUBLISH");
    await user.click(within(panel!).getByRole("button", { name: "发布" }));

    expect(await within(panel!).findByText("Generation 2 已发布")).toBeInTheDocument();
    expect(publishRequest).toEqual({
      indexGeneration: 2,
      profileVersion: "phase6-hybrid-rrf-v1",
      reason: "评测门禁通过",
      confirmation: "PUBLISH",
    });
  });
});
