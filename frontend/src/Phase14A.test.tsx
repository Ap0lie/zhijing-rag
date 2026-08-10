import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type { MemoryItem } from "./types";

const currentUser = {
  id: "10000000-0000-0000-0000-000000000014",
  username: "memory-user",
  role: "USER",
} as const;

const candidate: MemoryItem = {
  id: "14000000-0000-0000-0000-000000000001",
  memoryType: "USER_PREFERENCE",
  memoryKey: "回答语言",
  content: "默认使用简体中文",
  status: "CANDIDATE",
  versionNumber: 1,
  origin: "USER",
  supersedesMemoryId: null,
  sourceCount: 0,
  expiresAt: null,
  createdAt: "2026-07-29T00:00:00Z",
  updatedAt: "2026-07-29T00:00:00Z",
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderMemory() {
  return render(
    <MemoryRouter initialEntries={["/memory"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  resetCsrfToken();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("creates a candidate and only projects it after explicit confirmation", async () => {
  let items: MemoryItem[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const method = init?.method ?? "GET";
    if (path.endsWith("/auth/me")) return jsonResponse(currentUser);
    if (path.endsWith("/auth/csrf")) {
      return jsonResponse({ token: "memory-csrf", headerName: "X-CSRF-TOKEN" });
    }
    if (path.endsWith("/memories/settings")) {
      return jsonResponse({
        enabled: false,
        suggestionEnabled: false,
        version: 0,
        updatedAt: "2026-07-29T00:00:00Z",
      });
    }
    if (path.endsWith("/memories/profile")) {
      const active = items.find((item) => item.status === "ACTIVE");
      return jsonResponse({
        memoryEnabled: false,
        preferences: active ? [{
          memoryId: active.id,
          key: active.memoryKey,
          value: active.content,
          versionNumber: active.versionNumber,
        }] : [],
      });
    }
    if (path.endsWith(`/memories/${candidate.id}/sources`)) {
      return jsonResponse([]);
    }
    if (path.endsWith(`/memories/${candidate.id}/events`)) {
      return jsonResponse(items[0]?.status === "ACTIVE"
        ? [
          { id: 2, eventType: "CONFIRMED", relatedMemoryId: null, reason: null, createdAt: "2026-07-29T00:01:00Z" },
          { id: 1, eventType: "CREATED", relatedMemoryId: null, reason: null, createdAt: "2026-07-29T00:00:00Z" },
        ]
        : [{ id: 1, eventType: "CREATED", relatedMemoryId: null, reason: null, createdAt: "2026-07-29T00:00:00Z" }]);
    }
    if (path.endsWith(`/memories/${candidate.id}/confirm`) && method === "POST") {
      expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("memory-csrf");
      items = [{ ...candidate, status: "ACTIVE", updatedAt: "2026-07-29T00:01:00Z" }];
      return jsonResponse(items[0]);
    }
    if (path.endsWith("/memories") && method === "POST") {
      expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("memory-csrf");
      expect(new Headers(init?.headers).get("Idempotency-Key")).toMatch(/^memory-create:/);
      items = [candidate];
      return jsonResponse(candidate, 201);
    }
    if (path.endsWith("/memories") && method === "GET") {
      return jsonResponse(items);
    }
    throw new Error(`Unexpected request: ${method} ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  const user = userEvent.setup();

  renderMemory();
  expect(await screen.findByRole("heading", { name: "可控长期记忆" })).toBeInTheDocument();
  expect(screen.getByText("没有 ACTIVE 用户偏好。这里不是第二个可编辑的画像事实源。")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "新增记忆" }));
  await user.type(screen.getByLabelText("记忆名称"), "回答语言");
  await user.type(screen.getByLabelText(/^简短事实/), "默认使用简体中文");
  await user.click(screen.getByRole("checkbox", { name: /先保存为候选/ }));
  await user.click(screen.getByRole("button", { name: "保存候选" }));

  expect(await screen.findByText("候选记忆已保存，确认前不会生效。")).toBeInTheDocument();
  expect(screen.getAllByText("待确认").length).toBeGreaterThan(0);
  expect(screen.queryByText("v1", { selector: ".memory-profile-list small" })).not.toBeInTheDocument();

  await user.click(await screen.findByRole("button", { name: "确认" }));
  expect(await screen.findByText("回答语言：生效中。")).toBeInTheDocument();
  await waitFor(() => {
    expect(screen.getByText("默认使用简体中文", { selector: ".memory-profile-list dd" })).toBeInTheDocument();
  });
});

it("never binds a delayed memory detail response to another replacement", async () => {
  const first: MemoryItem = {
    ...candidate,
    id: "14000000-0000-0000-0000-000000000011",
    memoryKey: "记忆 A",
    content: "A 内容",
    status: "ACTIVE",
    sourceCount: 1,
  };
  const second: MemoryItem = {
    ...candidate,
    id: "14000000-0000-0000-0000-000000000012",
    memoryKey: "记忆 B",
    content: "B 内容",
    status: "ACTIVE",
    sourceCount: 1,
  };
  let resolveFirstSources!: (response: Response) => void;
  let resolveFirstEvents!: (response: Response) => void;
  const firstSources = new Promise<Response>((resolve) => {
    resolveFirstSources = resolve;
  });
  const firstEvents = new Promise<Response>((resolve) => {
    resolveFirstEvents = resolve;
  });
  const fetchMock = vi.fn(async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ) => {
    const path = String(input);
    const method = init?.method ?? "GET";
    if (path.endsWith("/auth/me")) return jsonResponse(currentUser);
    if (path.endsWith("/memories/settings")) {
      return jsonResponse({
        enabled: true,
        suggestionEnabled: false,
        version: 1,
        updatedAt: "2026-07-30T00:00:00Z",
      });
    }
    if (path.endsWith("/memories/profile")) {
      return jsonResponse({ memoryEnabled: true, preferences: [] });
    }
    if (path.endsWith("/memories") && method === "GET") {
      return jsonResponse([first, second]);
    }
    if (path.endsWith(`/memories/${first.id}/sources`)) return firstSources;
    if (path.endsWith(`/memories/${first.id}/events`)) return firstEvents;
    if (path.endsWith(`/memories/${second.id}/sources`)) {
      return jsonResponse([{
        id: 12,
        sourceType: "CHAT_SESSION",
        chatSessionId: "22000000-0000-0000-0000-000000000012",
        chatMessageId: null,
        documentId: null,
        revisionId: null,
        childChunkId: null,
        sourceSpanId: null,
        sourceDeletedAt: null,
        createdAt: "2026-07-30T00:00:00Z",
      }]);
    }
    if (path.endsWith(`/memories/${second.id}/events`)) {
      return jsonResponse([]);
    }
    throw new Error(`Unexpected request: ${method} ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  const actor = userEvent.setup();

  renderMemory();
  await actor.click(await screen.findByRole("button", { name: /记忆 A/ }));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining(`/memories/${first.id}/sources`),
    expect.anything(),
  ));
  await actor.click(screen.getByRole("button", { name: /记忆 B/ }));
  expect(await screen.findByText(
    /22000000-0000-0000-0000-000000000012/,
  )).toBeInTheDocument();

  resolveFirstSources(jsonResponse([{
    id: 11,
    sourceType: "CHAT_SESSION",
    chatSessionId: "22000000-0000-0000-0000-000000000011",
    chatMessageId: null,
    documentId: null,
    revisionId: null,
    childChunkId: null,
    sourceSpanId: null,
    sourceDeletedAt: null,
    createdAt: "2026-07-30T00:00:00Z",
  }]));
  resolveFirstEvents(jsonResponse([]));
  await waitFor(() => expect(screen.queryByText(
    /22000000-0000-0000-0000-000000000011/,
  )).not.toBeInTheDocument());

  await actor.click(screen.getByRole("button", {
    name: "创建替换版本",
  }));
  expect(screen.getByText(
    /22000000-0000-0000-0000-000000000012/,
    { selector: ".memory-source-chips span" },
  )).toBeInTheDocument();
});
