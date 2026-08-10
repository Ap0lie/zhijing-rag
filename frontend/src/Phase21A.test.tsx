import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";

const alphaId = "60000000-0000-0000-0000-000000000001";
const betaId = "60000000-0000-0000-0000-000000000002";

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const config = {
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
  id: "50000000-0000-0000-0000-000000000006",
  graphGeneration: 6,
  graphConfigVersion: config.version,
  status: "ACTIVE",
  expectedDocumentCount: 1,
  projectedDocumentCount: 1,
  entityCount: 2,
  mentionCount: 5,
  relationshipCount: 1,
  relationshipEvidenceCount: 1,
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

const entities = [
  {
    id: alphaId,
    canonicalName: "Alpha",
    entityType: "CONCEPT",
    description: null,
    mentionCount: 3,
    relationshipCount: 1,
    communityKey: 1,
    aliases: ["Alpha Alias"],
    matchSource: "ALIAS",
    matchedAlias: "Alpha Alias",
  },
  {
    id: betaId,
    canonicalName: "Beta",
    entityType: "CONCEPT",
    description: null,
    mentionCount: 2,
    relationshipCount: 1,
    communityKey: 1,
    aliases: ["Beta Alias"],
    matchSource: "CANONICAL_NAME",
    matchedAlias: null,
  },
];

describe("Phase 21A entity resolution workflow", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("selects readable entities, previews impact, and creates only by token", async () => {
    let previewBody: Record<string, unknown> | null = null;
    let createBody: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) {
        return response({
          id: "10000000-0000-0000-0000-000000000001",
          username: "admin",
          role: "ADMIN",
        });
      }
      if (path.endsWith("/auth/csrf")) {
        return response({ token: "phase21a-csrf", headerName: "X-CSRF-TOKEN" });
      }
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
        return response({
          activeGeneration: null,
          runtime: {
            enabled: false,
            model: "",
            revision: "",
            promptVersion: "v1",
            schemaVersion: "v1",
          },
          configs: [],
          generations: [],
        });
      }
      if (path.endsWith("/admin/graph")) {
        return response({
          activeGeneration: 6,
          extraction: {
            enabled: false,
            model: "",
            revision: "",
            promptVersion: "v1",
            schemaVersion: "v1",
          },
          configs: [config],
          generations: [generation],
        });
      }
      if (path.includes("/admin/graph/entities?")) {
        return response({
          graphGeneration: 6,
          page: 0,
          size: 10,
          total: 2,
          nextCursor: null,
          items: entities,
        });
      }
      if (path.includes("/admin/graph/communities?")) {
        return response({
          graphGeneration: 6,
          page: 0,
          size: 20,
          total: 0,
          items: [],
        });
      }
      if (path.endsWith("/admin/graph/resolution-rules/previews")) {
        previewBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return response({
          previewToken: "70000000-0000-0000-0000-000000000001",
          expiresAt: "2026-08-03T00:10:00Z",
          graphGeneration: 6,
          graphStatus: "ACTIVE",
          baseConfigVersion: config.version,
          sourceSetHash: "a".repeat(64),
          action: "MERGE",
          entities,
          impact: {
            mentionCount: 5,
            sourceSpanCount: 4,
            relationshipCount: 1,
            relationshipEvidenceCount: 2,
            communityCount: 1,
            documentCount: 2,
            queryImpactState: "NOT_AVAILABLE",
            queryImpactReason: "离线图无法可靠推算未来查询命中范围",
          },
          blockers: [],
          warnings: [],
        });
      }
      if (path.endsWith("/admin/graph/resolution-rules")) {
        createBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
        return response({ ...config, version: "graph-v7" }, 201);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={["/admin/graph"]}>
        <AuthProvider><App /></AuthProvider>
      </MemoryRouter>,
    );

    await user.type(
      await screen.findByLabelText("名称或 Alias"),
      "Alpha Alias",
    );
    await user.click(screen.getByRole("button", { name: "搜索实体" }));
    await user.click((await screen.findAllByRole("button", { name: "选择" }))[0]);
    await user.click(screen.getByRole("button", { name: "选择" }));

    const target = screen.getByLabelText("目标名称");
    await user.clear(target);
    await user.type(target, "Alpha Beta");
    await user.type(screen.getByLabelText("新配置版本"), "graph-v7");
    await user.type(
      screen.getByLabelText("审计理由（8–500 字）"),
      "核对原文 Evidence 后合并重复实体",
    );
    await user.click(screen.getByRole("button", { name: "预览影响" }));

    expect(await screen.findByText("影响预览")).toBeInTheDocument();
    expect(screen.getByText(/下游查询范围：暂无法预估/))
      .toBeInTheDocument();
    expect(previewBody).toMatchObject({
      graphGeneration: 6,
      baseConfigVersion: "graph-v6",
      action: "MERGE",
      sourceEntityIds: [alphaId, betaId],
      targetCanonicalName: "Alpha Beta",
      targetEntityType: "CONCEPT",
    });

    await user.type(
      screen.getByLabelText("确认输入 APPLY_NEXT_BUILD"),
      "APPLY_NEXT_BUILD",
    );
    await user.click(screen.getByRole("button", { name: "创建下一代配置" }));

    await waitFor(() => expect(createBody).not.toBeNull());
    expect(createBody).toMatchObject({
      previewToken: "70000000-0000-0000-0000-000000000001",
      newConfigVersion: "graph-v7",
      confirmation: "APPLY_NEXT_BUILD",
      reason: "核对原文 Evidence 后合并重复实体",
    });
    expect(createBody).not.toHaveProperty("sourceEntityIds");
    expect(await screen.findByText("规则将在 graph-v7 的下一次构建中生效"))
      .toBeInTheDocument();
  });
});
