import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import {
  GraphTopologyPanel,
  toCytoscapeElements,
} from "./components/GraphTopologyPanel";
import type { GraphSubgraph } from "./types";

const cytoscapeMock = vi.hoisted(() => {
  const destroy = vi.fn();
  const element = { select: vi.fn() };
  const core = {
    on: vi.fn(),
    destroy,
    fit: vi.fn(),
    reset: vi.fn(),
    animate: vi.fn(),
    $id: vi.fn(() => element),
    elements: vi.fn(() => ({ unselect: vi.fn() })),
  };
  return { core, create: vi.fn(() => core), destroy };
});

vi.mock("cytoscape", () => ({ default: cytoscapeMock.create }));

const admin = {
  id: "10000000-0000-0000-0000-000000000001",
  username: "admin",
  role: "ADMIN",
};

const entityId = "60000000-0000-0000-0000-000000000001";
const targetId = "60000000-0000-0000-0000-000000000002";
const relationshipId = "70000000-0000-0000-0000-000000000001";

const graph: GraphSubgraph = {
  generation: 6,
  rootType: "ENTITY",
  rootId: entityId,
  rootLabel: "Parent-Child Chunk",
  hops: 1,
  truncated: false,
  nodes: [
    {
      id: entityId,
      name: "Parent-Child Chunk",
      entityType: "CONCEPT",
      communityKey: 1,
      depth: 0,
      mentionCount: 3,
      relationshipCount: 1,
      root: true,
    },
    {
      id: targetId,
      name: "Evidence Pack",
      entityType: "CONCEPT",
      communityKey: 1,
      depth: 1,
      mentionCount: 2,
      relationshipCount: 1,
      root: false,
    },
  ],
  edges: [{
    id: relationshipId,
    sourceEntityId: entityId,
    targetEntityId: targetId,
    relationshipType: "SUPPORTS",
    description: "Parent context supports evidence",
    evidenceCount: 1,
  }],
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function LocationProbe() {
  const location = useLocation();
  return <output aria-label="location">{location.pathname}{location.search}</output>;
}

describe("Phase 20 local Graph visualization", () => {
  beforeEach(() => {
    resetCsrfToken();
    cytoscapeMock.create.mockClear();
    cytoscapeMock.destroy.mockClear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("maps evidence-backed nodes and directed edges deterministically", () => {
    const elements = toCytoscapeElements(graph);

    expect(elements).toHaveLength(3);
    expect(elements[0]).toMatchObject({
      group: "nodes",
      data: { id: entityId, depth: 0 },
      classes: expect.stringContaining("root"),
    });
    expect(elements[2]).toMatchObject({
      group: "edges",
      data: {
        id: relationshipId,
        source: entityId,
        target: targetId,
        label: "SUPPORTS",
      },
    });
  });

  it("loads Cytoscape on demand, exposes a keyboard list, and destroys it", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return response(admin);
      if (path.includes("/admin/graph/subgraph?")) return response(graph);
      if (path.includes(`/admin/graph/relationships/${relationshipId}?`)) {
        return response({
          id: relationshipId,
          sourceEntityId: entityId,
          sourceName: "Parent-Child Chunk",
          targetEntityId: targetId,
          targetName: "Evidence Pack",
          relationshipType: "SUPPORTS",
          description: "Parent context supports evidence",
          evidence: [{
            id: "80000000-0000-0000-0000-000000000001",
            documentId: "20000000-0000-0000-0000-000000000001",
            documentTitle: "RAG architecture",
            revisionId: "30000000-0000-0000-0000-000000000001",
            revisionNumber: 1,
            childChunkId: "40000000-0000-0000-0000-000000000001",
            sourceSpanId: "50000000-0000-0000-0000-000000000001",
            evidenceText: "The parent context supports the evidence pack.",
            startPage: 1,
            endPage: 1,
            documentFormat: "PDF",
            sourceLocator: null,
            sourceLabel: "第 1 页",
          }],
        });
      }
      if (path.includes(`/admin/graph/entities/${entityId}?generation=6`)) {
        return response({
          entity: {
            id: entityId,
            canonicalName: "Parent-Child Chunk",
            entityType: "CONCEPT",
            description: "A retrieval structure",
            mentionCount: 3,
            relationshipCount: 1,
            communityKey: 1,
          },
          aliases: ["Parent Child"],
          mentions: [{
            id: "70000000-0000-0000-0000-000000000001",
            documentId: "20000000-0000-0000-0000-000000000001",
            documentTitle: "RAG architecture",
            revisionId: "30000000-0000-0000-0000-000000000001",
            revisionNumber: 1,
            childChunkId: "40000000-0000-0000-0000-000000000001",
            sourceSpanId: "50000000-0000-0000-0000-000000000001",
            surfaceText: "Parent-Child Chunk",
            startPage: 1,
            endPage: 1,
            documentFormat: "PDF",
            sourceLocator: null,
            sourceLabel: "第 1 页",
          }],
          relationships: [],
        });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();
    const rendered = render(
      <MemoryRouter>
        <AuthProvider>
          <GraphTopologyPanel
            generation={6}
            rootType="ENTITY"
            rootId={entityId}
            hops={1}
            onHopsChange={vi.fn()}
            onBack={vi.fn()}
          />
        </AuthProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(cytoscapeMock.create).toHaveBeenCalledOnce());
    expect(screen.getByRole("img", { name: /局部知识关系图/ }))
      .toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: /Parent-Child Chunk，CONCEPT，3 个原文提及/,
    }));
    expect(await screen.findByText("A retrieval structure"))
      .toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: /Parent-Child Chunk，SUPPORTS，Evidence Pack/,
    }));
    expect(await screen.findByText(
      "The parent context supports the evidence pack.",
    )).toBeInTheDocument();

    rendered.unmount();
    expect(cytoscapeMock.destroy).toHaveBeenCalledOnce();
  });

  it("keeps list as default and writes a shareable local-graph URL", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return response(admin);
      if (path.endsWith("/admin/graph/retrieval")) {
        return response({
          currentPublication: {
            profileVersion: "phase9-local-v1",
            publicationEventId: 1,
            publishedAt: "2026-08-02T00:00:00Z",
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
          configs: [],
          generations: [{
            id: "50000000-0000-0000-0000-000000000006",
            graphGeneration: 6,
            graphConfigVersion: "graph-v6",
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
            createdAt: "2026-08-02T00:00:00Z",
            startedAt: null,
            completedAt: null,
            retentionUntil: null,
            updatedAt: "2026-08-02T00:00:00Z",
          }],
        });
      }
      if (path.includes("/admin/graph/entities?")) {
        return response({
          graphGeneration: 6,
          page: 0,
          size: 20,
          total: 1,
          items: [{
            id: entityId,
            canonicalName: "Parent-Child Chunk",
            entityType: "CONCEPT",
            description: null,
            mentionCount: 3,
            relationshipCount: 1,
            communityKey: 1,
          }],
        });
      }
      if (path.includes("/admin/graph/communities?")) {
        return response({ graphGeneration: 6, page: 0, size: 20, total: 0, items: [] });
      }
      if (path.includes("/admin/graph/subgraph?")) return response(graph);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={["/admin/graph"]}>
        <AuthProvider><App /></AuthProvider>
        <LocationProbe />
      </MemoryRouter>,
    );

    expect(await screen.findByRole("button", { name: "列表" }))
      .toHaveAttribute("aria-pressed", "true");
    await user.click(await screen.findByRole("button", {
      name: "在关系图中查看",
    }));

    expect(await screen.findByLabelText("location")).toHaveTextContent(
      `/admin/graph?view=graph&generation=6&rootType=ENTITY&rootId=${entityId}&hops=1`,
    );
    expect(await screen.findByText("2 个实体 · 1 条关系"))
      .toBeInTheDocument();
  });
});
