import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";

const alphaId = "61000000-0000-0000-0000-000000000001";
const alphaLabsId = "61000000-0000-0000-0000-000000000002";
const candidateId = "62000000-0000-0000-0000-000000000001";

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const entities = [
  {
    id: alphaId,
    canonicalName: "Alpha",
    entityType: "ORGANIZATION",
    aliases: ["Alpha Labs"],
    mentionCount: 3,
    relationshipCount: 2,
    relationshipEvidenceCount: 2,
  },
  {
    id: alphaLabsId,
    canonicalName: "Alpha Labs",
    entityType: "ORGANIZATION",
    aliases: ["Alpha"],
    mentionCount: 2,
    relationshipCount: 1,
    relationshipEvidenceCount: 1,
  },
];

const candidate = {
  id: candidateId,
  candidateType: "SUSPECTED_DUPLICATE",
  suggestedAction: "MERGE",
  status: "ACTIVE",
  version: 1,
  entities,
  suggestedTargetName: "Alpha",
  suggestedTargetType: "ORGANIZATION",
  suggestedAliases: [],
  signals: [
    {
      code: "ALIAS_OVERLAP",
      strength: "HARD",
      explanation: "两个实体共享当前有效 Alias：Alpha",
      numericValue: 1,
    },
    {
      code: "NEIGHBOR_OVERLAP",
      strength: "SUPPORTING",
      explanation: "两个实体具有相同的当前有效邻居",
      numericValue: 0.5,
    },
  ],
  evidenceCount: 3,
  sourceDocumentCount: 2,
  stableRank: 1,
  createdAt: "2026-08-03T00:00:00Z",
  updatedAt: "2026-08-03T00:00:00Z",
};

const graphConfig = {
  version: "graph-v6",
  extractionModel: "local-model",
  extractionRevision: "revision-1",
  promptVersion: "prompt-v1",
  schemaVersion: "schema-v1",
  normalizationVersion: "normalize-v1",
  resolutionRuleSetVersion: "rules-v1",
  communityAlgorithm: "leiden",
  communityAlgorithmVersion: "0.10.2",
  communitySeed: 42,
  communityResolution: 1,
  reason: "fixture",
  runtimeCompatible: true,
  createdAt: "2026-08-03T00:00:00Z",
};

const generation = {
  id: "63000000-0000-0000-0000-000000000001",
  graphGeneration: 6,
  graphConfigVersion: "graph-v6",
  status: "ACTIVE",
  expectedDocumentCount: 2,
  projectedDocumentCount: 2,
  entityCount: 2,
  mentionCount: 5,
  relationshipCount: 1,
  relationshipEvidenceCount: 2,
  communityCount: 1,
  communityClaimCount: 0,
  cacheHitCount: 0,
  modelCallCount: 0,
  cacheHitRate: 0,
  caughtUp: true,
  buildAttempt: 1,
  failureCode: null,
  failureReason: null,
  buildReason: "fixture",
  createdAt: "2026-08-03T00:00:00Z",
  startedAt: null,
  completedAt: "2026-08-03T00:01:00Z",
  retentionUntil: null,
  updatedAt: "2026-08-03T00:01:00Z",
};

const proposal = {
  id: "71000000-0000-0000-0000-000000000001",
  candidateId,
  status: "CONFLICTED",
  version: 2,
  currentRevision: 1,
  baseGraphGeneration: 6,
  baseGraphConfigVersion: "graph-v6",
  materializedConfigVersion: null,
  appliedGraphGeneration: null,
  action: "MERGE",
  entities,
  matchAliases: [],
  targetCanonicalName: "Alpha",
  targetEntityType: "ORGANIZATION",
  impact: {
    mentionCount: 5,
    sourceSpanCount: 3,
    relationshipCount: 2,
    relationshipEvidenceCount: 3,
    communityCount: 1,
    documentCount: 2,
    queryImpactState: "NOT_AVAILABLE",
    queryImpactReason: "离线图无法可靠推算未来查询命中范围",
  },
  blockers: [],
  warnings: [],
  conflicts: [{
    conflictingProposalId: "71000000-0000-0000-0000-000000000002",
    code: "SOURCE_ENTITY_OVERLAP",
    message: "多条待生效规则包含相同来源实体，请保留一个明确方案",
  }],
  createdBy: "admin",
  createdAt: "2026-08-03T00:00:00Z",
  updatedAt: "2026-08-03T00:10:00Z",
  nextStep: "核对冲突并追加修订",
};

describe("Phase 21B resolution candidate discovery", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("explains a candidate, shows current evidence, and only prefills Phase 21A", async () => {
    let rulePreviewCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) {
        return response({ id: "admin-id", username: "admin", role: "ADMIN" });
      }
      if (path.endsWith("/admin/graph/retrieval")) {
        return response({
          currentPublication: { profileVersion: "local-v1", publicationEventId: 1, publishedAt: "2026-08-03T00:00:00Z" },
          activeGraphGeneration: 6,
          profiles: [],
        });
      }
      if (path.endsWith("/admin/graph/rebuild-requests")) return response([]);
      if (path.endsWith("/admin/graph/global")) {
        return response({
          activeGeneration: null,
          runtime: { enabled: false, model: "", revision: "", promptVersion: "v1", schemaVersion: "v1" },
          configs: [],
          generations: [],
        });
      }
      if (path.endsWith("/admin/graph")) {
        return response({
          activeGeneration: 6,
          extraction: { enabled: false, model: "", revision: "", promptVersion: "v1", schemaVersion: "v1" },
          configs: [graphConfig],
          generations: [generation],
        });
      }
      if (path.includes("/admin/graph/resolution-candidates?")) {
        return response({
          snapshot: {
            id: "64000000-0000-0000-0000-000000000001",
            graphGeneration: 6,
            graphConfigVersion: "graph-v6",
            sourceSetHash: "a".repeat(64),
            algorithmVersion: "phase21b-deterministic-v1",
            inputHash: "b".repeat(64),
            status: "READY",
            duplicateCandidateCount: 1,
            splitCandidateCount: 0,
            createdAt: "2026-08-03T00:00:00Z",
            staleAt: null,
            staleReason: null,
          },
          nextCursor: null,
          items: [candidate],
        });
      }
      if (path.includes("/admin/graph/resolution-proposals?")) {
        return response({ page: 0, size: 30, total: 0, items: [] });
      }
      if (path.endsWith(`/admin/graph/resolution-candidates/${candidateId}`)) {
        return response({
          candidate,
          evidence: [{
            anchorType: "MENTION",
            anchorId: "65000000-0000-0000-0000-000000000001",
            entityId: alphaId,
            entityName: "Alpha",
            documentId: "66000000-0000-0000-0000-000000000001",
            documentTitle: "Alpha 公开资料",
            revisionId: "67000000-0000-0000-0000-000000000001",
            revisionNumber: 1,
            childChunkId: "68000000-0000-0000-0000-000000000001",
            sourceSpanId: "69000000-0000-0000-0000-000000000001",
            excerpt: "Alpha Labs 发布了新的公开计划。",
            documentFormat: "PDF",
            sourceLocator: { locatorType: "PAGE", startPage: 1, endPage: 1 },
            sourceLabel: "第 1 页",
          }],
          neighbors: [{
            entityId: alphaId,
            entityName: "Alpha",
            neighborId: "70000000-0000-0000-0000-000000000001",
            neighborName: "Project One",
            neighborType: "PROJECT",
            shared: true,
            evidenceCount: 1,
          }],
          events: [],
        });
      }
      if (path.includes("/admin/graph/entities?")) {
        return response({ graphGeneration: 6, page: 0, size: 20, total: 0, nextCursor: null, items: [] });
      }
      if (path.includes("/admin/graph/communities?")) {
        return response({ graphGeneration: 6, page: 0, size: 20, total: 0, items: [] });
      }
      if (path.endsWith("/admin/graph/resolution-rules/previews")) {
        rulePreviewCalls += 1;
        return response({}, 500);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={["/admin/graph"]}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText("Alpha ↔ Alpha Labs")).toBeInTheDocument();
    expect(screen.getAllByText("Alias 重叠").length).toBeGreaterThan(1);
    expect(screen.getAllByText("邻接关系重叠").length).toBeGreaterThan(1);

    await user.click(screen.getByRole("button", { name: "核对证据" }));
    expect(await screen.findByText("Alpha Labs 发布了新的公开计划。")).toBeInTheDocument();
    expect(screen.getByText(/共享邻居/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "发起合并" }));
    expect(await screen.findByText("候选已带入规则草案；请核对实体与 Evidence 后重新执行影响预检"))
      .toBeInTheDocument();
    expect(screen.getAllByText("已选择 2 个来源实体").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("目标名称")).toHaveValue("Alpha");
    expect(rulePreviewCalls).toBe(0);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "预览影响" })).toBeEnabled();
    });
  });

  it("shows proposal conflicts and appends a revision instead of overwriting history", async () => {
    let revisionBody: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) {
        return response({ id: "admin-id", username: "admin", role: "ADMIN" });
      }
      if (path.endsWith("/auth/csrf")) return response({ token: "csrf-token" });
      if (path.endsWith("/admin/graph/retrieval")) {
        return response({
          currentPublication: {
            profileVersion: "local-v1",
            publicationEventId: 1,
            publishedAt: "2026-08-03T00:00:00Z",
          },
          activeGraphGeneration: 6,
          profiles: [],
        });
      }
      if (path.endsWith("/admin/graph/rebuild-requests")) return response([]);
      if (path.endsWith("/admin/graph/global")) {
        return response({ activeGeneration: null, runtime: { enabled: false, model: "", revision: "", promptVersion: "v1", schemaVersion: "v1" }, configs: [], generations: [] });
      }
      if (path.endsWith("/admin/graph")) {
        return response({ activeGeneration: 6, extraction: { enabled: false, model: "", revision: "", promptVersion: "v1", schemaVersion: "v1" }, configs: [graphConfig], generations: [generation] });
      }
      if (path.includes("/admin/graph/resolution-candidates?")) {
        return response({ snapshot: null, nextCursor: null, items: [] });
      }
      if (path.includes("/admin/graph/resolution-proposals?")) {
        return response({ page: 0, size: 30, total: 1, items: [proposal] });
      }
      if (path.endsWith(`/admin/graph/resolution-proposals/${proposal.id}`)) {
        return response({
          proposal,
          revisions: [{
            id: "72000000-0000-0000-0000-000000000001",
            revision: 1,
            supersedesRevision: null,
            action: "MERGE",
            entities,
            matchAliases: [],
            targetCanonicalName: "Alpha",
            targetEntityType: "ORGANIZATION",
            impact: proposal.impact,
            blockers: [],
            warnings: [],
            reason: "Initial reviewed proposal revision",
            createdBy: "admin",
            createdAt: "2026-08-03T00:00:00Z",
          }],
          events: [],
        });
      }
      if (path.endsWith(`/admin/graph/resolution-proposals/${proposal.id}/revisions`)) {
        revisionBody = JSON.parse(String(init?.body));
        return response({ proposal: { ...proposal, status: "READY", version: 3, currentRevision: 2, conflicts: [] }, revisions: [], events: [] });
      }
      if (path.includes("/admin/graph/entities?")) {
        return response({ graphGeneration: 6, page: 0, size: 20, total: 0, nextCursor: null, items: [] });
      }
      if (path.includes("/admin/graph/communities?")) {
        return response({ graphGeneration: 6, page: 0, size: 20, total: 0, items: [] });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/admin/graph"]}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText("待生效规则")).toBeInTheDocument();
    expect(await screen.findByText("多条待生效规则包含相同来源实体，请保留一个明确方案"))
      .toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "修订或预检" }));
    expect(await screen.findByText(/正在修订 Proposal/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("审计理由（8–500 字）"), "Resolve the overlap with a reviewed revision");
    await user.click(screen.getByRole("button", { name: "追加 Proposal Revision" }));
    await waitFor(() => expect(revisionBody).not.toBeNull());
    expect(revisionBody).toMatchObject({
      expectedRevision: 1,
      expectedVersion: 2,
      action: "MERGE",
      sourceEntityIds: [alphaId, alphaLabsId],
    });
    expect(await screen.findByText("已追加不可变 Proposal Revision")).toBeInTheDocument();
  });
});
