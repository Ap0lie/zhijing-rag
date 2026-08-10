import type { SearchPage as SearchPageResponse } from "./types";
import type { SearchHistoryState } from "./searchHistory";

const CACHE_TTL_MS = 5 * 60_000;
const MAX_ENTRIES_PER_USER = 3;
const DEFAULT_ROW_HEIGHT = 190;
const MIN_ROW_HEIGHT = 120;
const MAX_ROW_HEIGHT = 480;

export interface SearchContinuityEntry {
  ownerUserId: string;
  key: string;
  totalElements: number;
  items: Array<{ chunkId: string; height: number }>;
  selectedChunkId: string | null;
  scrollY: number;
  evidenceScrollTop: number;
  validatedAt: number;
}

interface ContinuityView {
  selectedChunkId: string | null;
  scrollY: number;
  evidenceScrollTop: number;
  rowHeights?: number[];
}

const cache = new Map<string, SearchContinuityEntry>();

function cacheKey(state: SearchHistoryState) {
  return JSON.stringify([
    1,
    state.ownerUserId,
    state.query,
    state.visibility,
    state.graphMode,
    state.page,
    10,
  ]);
}

function normalizedHeight(value: number | undefined) {
  if (!Number.isFinite(value)) return DEFAULT_ROW_HEIGHT;
  return Math.min(MAX_ROW_HEIGHT, Math.max(MIN_ROW_HEIGHT, Math.round(value!)));
}

function prune(now: number) {
  for (const [key, entry] of cache) {
    if (now - entry.validatedAt > CACHE_TTL_MS) cache.delete(key);
  }
}

function enforceOwnerLimit(ownerUserId: string) {
  const ownerEntries = [...cache.entries()]
    .filter(([, entry]) => entry.ownerUserId === ownerUserId);
  while (ownerEntries.length > MAX_ENTRIES_PER_USER) {
    const oldest = ownerEntries.shift();
    if (oldest) cache.delete(oldest[0]);
  }
}

export function readSearchContinuity(
  state: SearchHistoryState,
  now = Date.now(),
): SearchContinuityEntry | null {
  prune(now);
  const key = cacheKey(state);
  const entry = cache.get(key);
  if (!entry || entry.ownerUserId !== state.ownerUserId) return null;
  cache.delete(key);
  cache.set(key, entry);
  return {
    ...entry,
    items: entry.items.map((item) => ({ ...item })),
  };
}

export function writeSearchContinuity(
  state: SearchHistoryState,
  response: SearchPageResponse,
  view: ContinuityView,
  now = Date.now(),
) {
  prune(now);
  const key = cacheKey(state);
  const rowHeights = view.rowHeights ?? [];
  const entry: SearchContinuityEntry = {
    ownerUserId: state.ownerUserId,
    key,
    totalElements: response.totalElements,
    items: response.items.map((item, index) => ({
      chunkId: item.chunkId,
      height: normalizedHeight(rowHeights[index]),
    })),
    selectedChunkId: view.selectedChunkId,
    scrollY: Math.max(0, Math.round(view.scrollY)),
    evidenceScrollTop: Math.max(0, Math.round(view.evidenceScrollTop)),
    validatedAt: now,
  };
  cache.delete(key);
  cache.set(key, entry);
  enforceOwnerLimit(state.ownerUserId);
}

export function updateSearchContinuityView(
  state: SearchHistoryState,
  view: ContinuityView,
  now = Date.now(),
) {
  prune(now);
  const key = cacheKey(state);
  const entry = cache.get(key);
  if (!entry || entry.ownerUserId !== state.ownerUserId) return;
  const rowHeights = view.rowHeights ?? entry.items.map((item) => item.height);
  const updated: SearchContinuityEntry = {
    ...entry,
    items: entry.items.map((item, index) => ({
      ...item,
      height: normalizedHeight(rowHeights[index]),
    })),
    selectedChunkId: view.selectedChunkId,
    scrollY: Math.max(0, Math.round(view.scrollY)),
    evidenceScrollTop: Math.max(0, Math.round(view.evidenceScrollTop)),
  };
  cache.delete(key);
  cache.set(key, updated);
}

export function clearSearchContinuityCache() {
  cache.clear();
}
