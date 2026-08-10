import type { DocumentVisibility, GraphMode } from "./types";

export interface SearchHistoryState {
  version: 1;
  ownerUserId: string;
  query: string;
  visibility: "" | DocumentVisibility;
  graphMode: GraphMode;
  page: number;
  selectedChunkId: string | null;
}

const GRAPH_MODES = new Set<GraphMode>([
  "AUTO",
  "HYBRID",
  "LOCAL_GRAPH",
  "GLOBAL_GRAPH",
]);
const VISIBILITIES = new Set<DocumentVisibility>(["ALL_USERS", "RESTRICTED"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function readSearchHistoryState(
  value: unknown,
  ownerUserId: string | undefined,
): SearchHistoryState | null {
  if (!ownerUserId || !isRecord(value) || value.version !== 1 || value.ownerUserId !== ownerUserId) {
    return null;
  }

  const query = typeof value.query === "string" ? value.query.trim() : "";
  const visibility = value.visibility;
  const graphMode = value.graphMode;
  const page = value.page;
  const selectedChunkId = value.selectedChunkId;

  if (
    !query
    || query.length > 500
    || (visibility !== "" && !VISIBILITIES.has(visibility as DocumentVisibility))
    || typeof graphMode !== "string"
    || !GRAPH_MODES.has(graphMode as GraphMode)
    || !Number.isSafeInteger(page)
    || (page as number) < 0
    || (selectedChunkId !== null && (typeof selectedChunkId !== "string" || !selectedChunkId))
  ) {
    return null;
  }

  return {
    version: 1,
    ownerUserId,
    query,
    visibility: visibility as "" | DocumentVisibility,
    graphMode: graphMode as GraphMode,
    page: page as number,
    selectedChunkId: selectedChunkId as string | null,
  };
}
