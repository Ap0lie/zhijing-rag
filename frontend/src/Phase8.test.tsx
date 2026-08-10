import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  GraphEntityDetail,
  GraphEntityPage,
  GraphOverview,
  GraphRebuildRequest,
  GraphRetrievalConfiguration,
} from "./types";

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
} as const;

const overview: GraphOverview = {
  activeGeneration: 1,
  extraction: {
    enabled: true,
    model: "qwen-local",
    revision: "revision-1",
    promptVersion: "phase8-graph-prompt-v1",
    schemaVersion: "phase8-graph-schema-v1",
  },
  configs: [{
    version: "phase8-graph-v1",
    extractionModel: "qwen-local",
    extractionRevision: "revision-1",
    promptVersion: "phase8-graph-prompt-v1",
    schemaVersion: "phase8-graph-schema-v1",
    normalizationVersion: "unicode-nfkc-lower-v1",
    resolutionRuleSetVersion: "phase8-baseline-rules-v1",
    communityAlgorithm: "leidenalg",
    communityAlgorithmVersion: "0.10.2",
    communitySeed: 42,
    communityResolution: 1,
    reason: "initial graph",
    runtimeCompatible: true,
    createdAt: "2026-07-26T06:00:00Z",
  }],
  generations: [{
    id: "50000000-0000-0000-0000-000000000001",
    graphGeneration: 1,
    graphConfigVersion: "phase8-graph-v1",
    status: "ACTIVE",
    expectedDocumentCount: 1,
    projectedDocumentCount: 1,
    entityCount: 1,
    mentionCount: 1,
    relationshipCount: 0,
    relationshipEvidenceCount: 0,
    communityCount: 1,
    communityClaimCount: 0,
    cacheHitCount: 0,
    modelCallCount: 1,
    cacheHitRate: 0,
    caughtUp: true,
    buildAttempt: 1,
    failureCode: null,
    failureReason: null,
    buildReason: "initial graph",
    createdAt: "2026-07-26T06:00:00Z",
    startedAt: "2026-07-26T06:00:01Z",
    completedAt: "2026-07-26T06:00:10Z",
    retentionUntil: null,
    updatedAt: "2026-07-26T06:00:10Z",
  }],
};

const graphRetrieval: GraphRetrievalConfiguration = {
  currentPublication: {
    profileVersion: "phase9-local-v1",
    publicationEventId: 1,
    publishedAt: "2026-07-26T06:00:00Z",
  },
  activeGraphGeneration: 1,
  profiles: [{
    version: "phase9-local-v1",
    seedLimit: 5,
    maxHops: 2,
    entityLimit: 20,
    edgeLimit: 40,
    graphChildLimit: 30,
    graphWeight: 1,
    graphContextTokenBudget: 900,
    graphContextPercent: 15,
    statementTimeoutMs: 500,
    reason: "Local Graph baseline",
    createdAt: "2026-07-26T06:00:00Z",
  }],
};

const entity = {
  id: "60000000-0000-0000-0000-000000000001",
  canonicalName: "Parent-Child Chunk",
  entityType: "CONCEPT",
  description: "父子块检索策略",
  mentionCount: 1,
  relationshipCount: 0,
  communityKey: 0,
};

const entityPage: GraphEntityPage = {
  graphGeneration: 1,
  page: 0,
  size: 20,
  total: 1,
  items: [entity],
};

const entityDetail: GraphEntityDetail = {
  entity,
  aliases: ["Parent-Child Chunk", "父子块检索"],
  mentions: [{
    id: "70000000-0000-0000-0000-000000000001",
    documentId: "20000000-0000-0000-0000-000000000001",
    documentTitle: "RAG 架构",
    revisionId: "30000000-0000-0000-0000-000000000001",
    revisionNumber: 2,
    childChunkId: "40000000-0000-0000-0000-000000000001",
    sourceSpanId: "80000000-0000-0000-0000-000000000001",
    surfaceText: "Parent-Child Chunk",
    startPage: 3,
    endPage: 3,
  }],
  relationships: [],
};

const rebuildRequests = ([
  "REQUESTED",
  "GRAPH_BUILDING",
  "GRAPH_READY",
  "GLOBAL_BUILDING",
  "FULFILLED",
] as const).map((state, index): GraphRebuildRequest => ({
  id: `90000000-0000-0000-0000-00000000000${index}`,
  documentId: `20000000-0000-0000-0000-00000000000${index}`,
  documentTitle: `${state} 文档`,
  targetRevisionId: `30000000-0000-0000-0000-00000000000${index}`,
  targetRevisionNumber: index + 2,
  targetAclVersion: index + 1,
  reason: index % 2 ? "ACL_CHANGED" : "REVISION_PUBLISHED",
  state,
  sourceGraphGeneration: 1,
  sourceGlobalGeneration: 1,
  globalRebuildRequired: true,
  candidateGraphGeneration: state === "REQUESTED" ? null : 2,
  candidateGlobalGeneration:
    state === "GLOBAL_BUILDING" || state === "FULFILLED" ? 2 : null,
  requestedAt: "2026-07-29T01:00:00Z",
  graphReadyAt:
    state === "GRAPH_READY" || state === "GLOBAL_BUILDING" || state === "FULFILLED"
      ? "2026-07-29T01:02:00Z"
      : null,
  globalReadyAt: state === "FULFILLED" ? "2026-07-29T01:04:00Z" : null,
  completedAt: state === "FULFILLED" ? "2026-07-29T01:04:01Z" : null,
}));

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/admin/graph"]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  );
}

describe("Phase 8 Graph administration UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("shows only evidence-backed entity detail with Child and document links", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return response(admin);
      if (path.endsWith("/admin/graph/retrieval")) return response(graphRetrieval);
      if (path.endsWith("/admin/graph")) return response(overview);
      if (path.includes("/admin/graph/entities?")) return response(entityPage);
      if (path.includes("/admin/graph/communities?")) {
        return response({ graphGeneration: 1, page: 0, size: 20, total: 0, items: [] });
      }
      if (path.includes(`/admin/graph/entities/${entity.id}?`)) {
        return response(entityDetail);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();
    await user.click(await screen.findByRole("button", {
      name: /Parent-Child Chunk/,
    }));

    const detail = await screen.findByText("父子块检索策略");
    const panel = detail.closest("article");
    expect(panel).not.toBeNull();
    expect(within(panel!).getByText((_, element) =>
      element?.tagName === "P"
      && element.textContent === "RAG 架构 · Revision 2 · 第 3 页"))
      .toBeInTheDocument();
    expect(within(panel!).getByRole("link", { name: "查看 Child" }))
      .toHaveAttribute(
        "href",
        "/chunks/40000000-0000-0000-0000-000000000001",
      );
  });

  it("starts an offline build only with CSRF and explicit BUILD confirmation", async () => {
    let buildBody: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return response(admin);
      if (path.endsWith("/auth/csrf")) {
        return response({ token: "phase8-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/admin/graph/retrieval")) return response(graphRetrieval);
      if (path.endsWith("/admin/graph/generations") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("phase8-csrf");
        buildBody = JSON.parse(String(init.body)) as Record<string, unknown>;
        return response({ ...overview.generations[0], graphGeneration: 2, status: "BUILDING" }, 202);
      }
      if (path.endsWith("/admin/graph")) return response(overview);
      if (path.includes("/admin/graph/entities?")) return response(entityPage);
      if (path.includes("/admin/graph/communities?")) {
        return response({ graphGeneration: 1, page: 0, size: 20, total: 0, items: [] });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderPage();
    const panel = (await screen.findByRole("heading", { name: "知识图谱版本" }))
      .closest("section");
    expect(panel).not.toBeNull();
    await user.type(within(panel!).getByPlaceholderText("记录本次图谱构建目的"), "验证抽取缓存");
    await user.type(within(panel!).getByPlaceholderText("输入 BUILD"), "BUILD");
    await user.click(within(panel!).getByRole("button", { name: "构建 Generation" }));

    expect(await screen.findByText("Graph Generation 2 已进入离线构建队列"))
      .toBeInTheDocument();
    expect(buildBody).toEqual({
      graphConfigVersion: "phase8-graph-v1",
      reason: "验证抽取缓存",
      confirmation: "BUILD",
    });
  });

  it("shows the complete Graph to Global rebuild lifecycle without hiding active work", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return response(admin);
      if (path.endsWith("/admin/graph/retrieval")) return response(graphRetrieval);
      if (path.endsWith("/admin/graph/rebuild-requests")) {
        return response(rebuildRequests);
      }
      if (path.endsWith("/admin/graph")) return response(overview);
      if (path.includes("/admin/graph/entities?")) {
        return response({ graphGeneration: 1, page: 0, size: 20, total: 0, items: [] });
      }
      if (path.includes("/admin/graph/communities?")) {
        return response({ graphGeneration: 1, page: 0, size: 20, total: 0, items: [] });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderPage();

    const heading = await screen.findByRole("heading", {
      name: "Revision / ACL 重建申请",
    });
    const panel = heading.closest("section");
    expect(panel).not.toBeNull();
    expect(within(panel!).getByText("4 进行中")).toBeInTheDocument();
    expect(within(panel!).queryByText("读取失败")).not.toBeInTheDocument();

    const graphReadyRow = within(panel!)
      .getByRole("link", { name: "GRAPH_READY 文档" })
      .closest("tr");
    expect(graphReadyRow).not.toBeNull();
    expect(within(graphReadyRow!).getByText("等待构建")).toBeInTheDocument();
    expect(within(graphReadyRow!).getByText(/^READY ·/)).toBeInTheDocument();

    const globalBuildingRow = within(panel!)
      .getByRole("link", { name: "GLOBAL_BUILDING 文档" })
      .closest("tr");
    expect(globalBuildingRow).not.toBeNull();
    expect(within(globalBuildingRow!).getAllByText("G1 → G2")).toHaveLength(2);
    expect(within(globalBuildingRow!).getByText("构建中")).toBeInTheDocument();

    const fulfilledRow = within(panel!)
      .getByRole("link", { name: "FULFILLED 文档" })
      .closest("tr");
    expect(fulfilledRow).not.toBeNull();
    expect(within(fulfilledRow!).getAllByText(/^READY ·/)).toHaveLength(2);
    expect(within(fulfilledRow!).getByText(/^完成 /)).toBeInTheDocument();
  });
});
