import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  EmbeddingCacheStatistics,
  IndexGenerationsResponse,
  ModelServicesHealth,
  RetrievalConfiguration,
} from "./types";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const statistics: EmbeddingCacheStatistics = {
  query: {
    entries: 12,
    maxEntries: 2048,
    hits: 41,
    misses: 7,
    evictions: 2,
    coalesced: 99,
    modelCalls: 7,
    savedModelCalls: 140,
  },
  artifacts: {
    entries: 50000,
    bytes: 204800000,
    maxBytes: 2147483648,
    hits: 49950,
    misses: 50,
    evictions: 10,
    corruptions: 1,
    modelCalls: 2,
    savedModelCalls: 49950,
  },
  models: [{
    providerKey: "openai-compatible",
    model: "Qwen/Qwen3-Embedding-0.6B",
    revision: "embedding-revision",
    dimensions: 1024,
    queryEntries: 12,
    artifactEntries: 50000,
    artifactBytes: 204800000,
  }],
  checkedAt: "2026-07-24T10:00:00Z",
};

const configuration: RetrievalConfiguration = {
  currentPublication: null,
  activeManifest: null,
  profiles: [],
  indexConfigs: [],
  goldenBaseline: {
    datasetVersion: "retrieval-golden-v2",
    caseCount: 40,
    status: "NOT_RUN",
    generatedAt: null,
    reportAvailable: false,
    slices: [{ name: "zh", caseCount: 40, candidateHitAt50: null }],
  },
};

const modelHealth: ModelServicesHealth = { services: [] };
const generations: IndexGenerationsResponse = { activeGeneration: null, generations: [] };

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

describe("Phase 7A Embedding cache administration UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("loads cache statistics independently from retrieval configuration", async () => {
    let resolveConfiguration!: (response: Response) => void;
    const pendingConfiguration = new Promise<Response>((resolve) => {
      resolveConfiguration = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/retrieval/configuration")) return pendingConfiguration;
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/admin/embedding-cache/stats")) return jsonResponse(statistics);
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderPage();

    expect(await screen.findByText("12 / 2,048")).toBeInTheDocument();
    expect(screen.getAllByText("50,000").length).toBeGreaterThan(0);
    expect(screen.getByText("正在读取检索配置")).toBeInTheDocument();
    expect(screen.queryByText(/inputHash|Query 原文|向量内容/i)).not.toBeInTheDocument();

    await act(async () => resolveConfiguration(jsonResponse(configuration)));
  });

  it("clears one model namespace with CSRF, confirmation and audit reason", async () => {
    let clearBody: Record<string, unknown> | null = null;
    let statisticsReads = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith("/admin/retrieval/configuration")) return jsonResponse(configuration);
      if (path.endsWith("/admin/model-services/health")) return jsonResponse(modelHealth);
      if (path.endsWith("/admin/index-builds")) return jsonResponse(generations);
      if (path.endsWith("/admin/embedding-cache/stats")) {
        statisticsReads += 1;
        return statisticsReads === 1
          ? jsonResponse(statistics)
          : jsonResponse({ code: "CACHE_STATS_UNAVAILABLE", message: "缓存统计刷新失败" }, 503);
      }
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "phase7a-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/embedding-cache/clear") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("phase7a-csrf");
        clearBody = JSON.parse(String(init.body)) as Record<string, unknown>;
        return jsonResponse({
          deletedArtifacts: 50000,
          invalidatedQueryEntries: 12,
          freedBytes: 204800000,
        });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();
    const cachePanel = (await screen.findByRole("heading", { name: "Embedding 缓存" })).closest("section");
    expect(cachePanel).not.toBeNull();
    await user.click(await within(cachePanel!).findByText("按模型与 Revision 清理缓存"));
    await user.type(within(cachePanel!).getByLabelText("审计理由"), "模型配置重新验证");
    await user.type(within(cachePanel!).getByLabelText("确认字段"), "CLEAR");
    await user.click(within(cachePanel!).getByRole("button", { name: "清理缓存" }));

    expect(await screen.findByText(/已删除 50,000 个 Artifact/)).toBeInTheDocument();
    expect(clearBody).toEqual({
      providerKey: "openai-compatible",
      model: "Qwen/Qwen3-Embedding-0.6B",
      revision: "embedding-revision",
      confirmation: "CLEAR",
      reason: "模型配置重新验证",
    });
    await waitFor(() => expect(statisticsReads).toBe(2));
    expect(await screen.findByText("缓存统计刷新失败")).toBeInTheDocument();
    expect(screen.getByText(/已删除 50,000 个 Artifact/)).toBeInTheDocument();
  });
});
