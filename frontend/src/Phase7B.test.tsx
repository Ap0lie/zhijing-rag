import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type {
  ChatCitationDetail,
  ChatSessionDetail,
  ChatSessionSummary,
} from "./types";

const user = {
  id: "10000000-0000-0000-0000-000000000002",
  username: "reader",
  role: "USER",
} as const;

const session: ChatSessionSummary = {
  id: "20000000-0000-0000-0000-000000000001",
  title: "交付清单",
  status: "ACTIVE",
  createdAt: "2026-07-24T09:00:00Z",
  updatedAt: "2026-07-24T09:00:00Z",
};

const citation = {
  id: "30000000-0000-0000-0000-000000000001",
  documentId: "40000000-0000-0000-0000-000000000001",
  documentTitle: "项目交付规范",
  revisionId: "50000000-0000-0000-0000-000000000001",
  revisionNumber: 2,
  chunkId: "60000000-0000-0000-0000-000000000001",
  startPage: 3,
  endPage: 3,
  label: "[1]",
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: status === 204 ? undefined : { "Content-Type": "application/json" },
  });
}

function eventStream(events: Array<{ type: string; data: unknown }>) {
  const content = events
    .map((event) => `event: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`)
    .join("");
  return new Response(content, {
    headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
  });
}

function emptyDetail(): ChatSessionDetail {
  return { ...session, messages: [], runs: [] };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/chat"]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  );
}

describe("Phase 7B trusted chat UI", () => {
  beforeEach(resetCsrfToken);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("creates, selects and deletes a chat session", async () => {
    let created = false;
    let deleted = false;
    let sessionTitle = session.title;
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "chat-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/chat/sessions") && (!init?.method || init.method === "GET")) {
        return jsonResponse({ items: created && !deleted ? [{ ...session, title: sessionTitle }] : [] });
      }
      if (path.endsWith("/chat/sessions") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("chat-csrf");
        expect(init.body).toBe("{}");
        created = true;
        return jsonResponse({ ...session, title: sessionTitle });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && init?.method === "PATCH") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("chat-csrf");
        sessionTitle = JSON.parse(String(init.body)).title;
        return jsonResponse({ ...session, title: sessionTitle });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && (!init?.method || init.method === "GET")) {
        return jsonResponse(emptyDetail());
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && init?.method === "DELETE") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("chat-csrf");
        deleted = true;
        return jsonResponse(null, 204);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPage();
    await actor.click(await screen.findByRole("button", { name: "新建第一个会话" }));
    expect(await screen.findByText("交付清单")).toBeInTheDocument();

    await actor.click(screen.getByRole("button", { name: "重命名会话 交付清单" }));
    const titleInput = screen.getByLabelText("会话标题");
    await actor.clear(titleInput);
    await actor.type(titleInput, "发布材料核对");
    await actor.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByText("发布材料核对")).toBeInTheDocument();

    await actor.click(screen.getByRole("button", { name: "删除会话 发布材料核对" }));
    await waitFor(() => expect(screen.queryByText("发布材料核对")).not.toBeInTheDocument());
    expect(deleted).toBe(true);
  });

  it("renders validated SSE answer and opens an authorized citation detail", async () => {
    let completed = false;
    const persistedDetail: ChatSessionDetail = {
      ...session,
      messages: [{
        id: "70000000-0000-0000-0000-000000000001",
        role: "USER",
        status: "COMPLETED",
        content: "交付需要哪些材料？",
        language: "zh",
        runId: "80000000-0000-0000-0000-000000000001",
        hidden: false,
        createdAt: "2026-07-24T10:00:00Z",
        memorySuggestionStatus: "SUCCEEDED",
        memorySuggestionCount: 1,
        memorySuggestionErrorCode: null,
      }, {
        id: "70000000-0000-0000-0000-000000000002",
        role: "ASSISTANT",
        status: "COMPLETED",
        content: "需要验收报告和部署清单。",
        language: "zh",
        runId: "80000000-0000-0000-0000-000000000001",
        hidden: false,
        createdAt: "2026-07-24T10:00:01Z",
        citations: [citation],
      }],
      runs: [{
        id: "80000000-0000-0000-0000-000000000001",
        status: "COMPLETED",
        errorCode: null,
        graphProfileVersion: null,
        graphGeneration: null,
        graphModeRequested: "AUTO",
        graphModeUsed: "HYBRID",
        graphDegraded: true,
        graphDegradationCode: "GRAPH_NO_ACTIVE_GENERATION",
        memoryUsedCount: 1,
        memoryTokenCount: 42,
        memoryDegradationCode: null,
        createdAt: "2026-07-24T10:00:00Z",
        completedAt: "2026-07-24T10:00:01Z",
      }],
    };
    const detail: ChatCitationDetail = {
      ...citation,
      childText: "交付材料包括验收报告和部署清单。",
      headingPath: ["项目交付", "材料"],
      sourceSpan: {
        id: "90000000-0000-0000-0000-000000000001",
        order: 1,
        startPage: 3,
        endPage: 3,
        startOffset: 20,
        endOffset: 39,
        chunkStartOffset: 0,
        chunkEndOffset: 19,
        sourceTextHash: "sha256",
      },
      parentText: "项目完成后，应提交完整交付材料。",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "chat-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/chat/sessions") && (!init?.method || init.method === "GET")) {
        return jsonResponse({ items: [session] });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && (!init?.method || init.method === "GET")) {
        return jsonResponse(completed ? persistedDetail : emptyDetail());
      }
      if (path.endsWith(`/chat/sessions/${session.id}/runs`) && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("chat-csrf");
        expect(JSON.parse(String(init.body))).toEqual({
          question: "交付需要哪些材料？",
          graphModeRequested: "AUTO",
        });
        completed = true;
        return eventStream([
          {
            type: "memory_used",
            data: {
              runId: "80000000-0000-0000-0000-000000000001",
              memories: [{
                memoryId: "91000000-0000-0000-0000-000000000001",
                memoryType: "USER_PREFERENCE",
                usageStatus: "USED",
              }],
            },
          },
          {
            type: "answer_delta",
            data: {
              runId: "80000000-0000-0000-0000-000000000001",
              messageId: "70000000-0000-0000-0000-000000000002",
              text: "需要验收报告和部署清单。",
            },
          },
          {
            type: "citation",
            data: {
              runId: "80000000-0000-0000-0000-000000000001",
              citation,
            },
          },
          {
            type: "completed",
            data: {
              runId: "80000000-0000-0000-0000-000000000001",
              messageId: "70000000-0000-0000-0000-000000000002",
              status: "COMPLETED",
              graphProfileVersion: "phase9-local-v1",
              graphGeneration: 1,
              graphModeRequested: "AUTO",
              graphModeUsed: "LOCAL_GRAPH",
              graphDegraded: false,
              graphDegradationCode: null,
            },
          },
        ]);
      }
      if (path.endsWith(`/chat/citations/${citation.id}`)) return jsonResponse(detail);
      if (path.endsWith("/chat/runs/80000000-0000-0000-0000-000000000001/memories")) {
        return jsonResponse([{
          runId: "80000000-0000-0000-0000-000000000001",
          memoryId: "91000000-0000-0000-0000-000000000001",
          memoryType: "USER_PREFERENCE",
          usageStatus: "USED",
          relevanceScore: 0.9,
          tokenCount: 42,
          sourceTypes: [],
          available: true,
          memoryKey: "回答风格",
          content: "先给结论",
          trimReason: null,
          createdAt: "2026-07-24T10:00:01Z",
        }]);
      }
      if (path.endsWith("/api/v1/memories") && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("chat-csrf");
        expect(new Headers(init.headers).get("Idempotency-Key"))
          .toMatch(/^chat-remember:/);
        expect(JSON.parse(String(init.body))).toMatchObject({
          memoryType: "USER_FACT",
          memoryKey: "项目职责",
          content: "交付需要哪些材料？",
          candidate: false,
          sources: [{
            sourceType: "CHAT_MESSAGE",
            chatSessionId: session.id,
            chatMessageId: "70000000-0000-0000-0000-000000000001",
          }],
        });
        return jsonResponse({
          id: "92000000-0000-0000-0000-000000000001",
          memoryType: "USER_FACT",
          memoryKey: "项目职责",
          content: "交付需要哪些材料？",
          status: "ACTIVE",
          versionNumber: 1,
          origin: "USER",
          supersedesMemoryId: null,
          sourceCount: 1,
          expiresAt: null,
          createdAt: "2026-07-24T10:00:02Z",
          updatedAt: "2026-07-24T10:00:02Z",
        });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPage();
    const composer = await screen.findByLabelText("问题");
    expect(composer).toHaveAttribute("maxlength", "500");
    await actor.type(composer, "交付需要哪些材料？");
    await actor.click(screen.getByRole("button", { name: "发送问题" }));

    expect(await screen.findByText("需要验收报告和部署清单。")).toBeInTheDocument();
    expect(await screen.findByText(
      "运行详情 · 智能选择 → 标准检索 · 1 条引用",
    )).toBeInTheDocument();
    await actor.click(await screen.findByRole("button", { name: "[1]" }));
    expect(await screen.findByText("项目交付规范")).toBeInTheDocument();
    expect(screen.getByText("交付材料包括验收报告和部署清单。")).toBeInTheDocument();
    expect(screen.getByText("项目交付 / 材料")).toBeInTheDocument();
    await actor.click(screen.getByRole("button", { name: "关闭引用" }));
    await actor.click(await screen.findByRole("button", { name: "本次使用 1 条记忆" }));
    expect(await screen.findByText("先给结论")).toBeInTheDocument();
    expect(screen.getByText("实际使用 1 条 · 独立于 Citation")).toBeInTheDocument();
    await actor.click(screen.getByRole("button", { name: "关闭记忆" }));
    expect(screen.getByRole("link", { name: "1 条候选待确认" })).toHaveAttribute("href", "/memory");
    await actor.click(screen.getByRole("button", { name: "记住这条" }));
    await actor.type(screen.getByLabelText("记忆名称"), "项目职责");
    await actor.click(screen.getByRole("button", { name: "保存并生效" }));
    expect(await screen.findByText("项目职责 已保存并生效。")).toBeInTheDocument();
  });

  it("explains planner fallback and candidate flow without contradictory labels", async () => {
    const question = "分别说明 TXT-SENTINEL-1501、MD-SENTINEL-1502 和 HTML-SENTINEL-1503 的状态";
    const detail: ChatSessionDetail = {
      ...session,
      messages: [{
        id: "70000000-0000-0000-0000-000000000011",
        role: "USER",
        status: "COMPLETED",
        content: question,
        language: "zh",
        runId: "80000000-0000-0000-0000-000000000011",
        hidden: false,
        createdAt: "2026-08-02T10:00:00Z",
      }, {
        id: "70000000-0000-0000-0000-000000000012",
        role: "ASSISTANT",
        status: "COMPLETED",
        content: "没有找到足够且有权限的证据来回答这个问题。",
        language: "zh",
        runId: "80000000-0000-0000-0000-000000000011",
        hidden: false,
        createdAt: "2026-08-02T10:00:01Z",
        citations: [],
      }],
      runs: [{
        id: "80000000-0000-0000-0000-000000000011",
        status: "REFUSED",
        errorCode: null,
        graphProfileVersion: null,
        graphGeneration: null,
        graphModeRequested: "AUTO",
        graphModeUsed: "HYBRID",
        graphDegraded: false,
        graphDegradationCode: null,
        queryProfileVersion: "query-intelligence-v1",
        historyMessageIds: [],
        historyCounterVersion: "conservative-utf8-v1",
        historyTokenCount: 0,
        historyTrimReasons: [],
        standaloneQuery: question,
        querySlots: [{
          round: 1,
          slot: 1,
          query: question,
          status: "SUCCESS",
          candidateCount: 85,
          degradationCode: null,
        }],
        plannerCallCount: 1,
        retrievalCallCount: 1,
        rerankCallCount: 1,
        coverageSufficient: true,
        queryDegraded: true,
        queryDegradationCode: "QUERY_PLANNER_FAILED",
        retrievedCandidateCount: 85,
        authorizedCandidateCount: 12,
        rerankedCandidateCount: 12,
        evidenceCandidateCount: 8,
        validatedEvidenceCount: 0,
        routeSelectedMode: "HYBRID",
        routerCallCount: 1,
        routeReasonCode: "SAFE_FALLBACK",
        routeDegraded: true,
        routeDegradationCode: "ROUTER_FAILED",
        createdAt: "2026-08-02T10:00:00Z",
        completedAt: "2026-08-02T10:00:01Z",
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/chat/sessions")) return jsonResponse({ items: [session] });
      if (path.endsWith(`/chat/sessions/${session.id}`)) return jsonResponse(detail);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPage();
    await actor.click(await screen.findByText(/运行详情 · 智能选择/));

    expect(screen.getByText("问题拆解暂时未完成，已按原问题继续检索。")).toBeInTheDocument();
    expect(screen.getByText("智能选择暂时不可用，本次使用标准检索。")).toBeInTheDocument();
    expect(screen.queryByText("检索结果已达到当前回答所需的证据覆盖。")).not.toBeInTheDocument();
    expect(screen.getByText(/本次未能对问题中的每一项单独检索/)).toBeInTheDocument();
    expect(screen.getByText("检索直接使用了原始问题，未重复展示相同文本。")).toBeInTheDocument();
    expect(screen.getByText(/共召回 85 个去重候选；权限与版本复核后保留 12 个/)).toBeInTheDocument();
    expect(screen.getByText("QUERY_PLANNER_FAILED").closest("details"))
      .toHaveClass("chat-technical-details");
    expect(screen.getByText("ROUTER_FAILED").closest("details"))
      .toHaveClass("chat-technical-details");
  });

  it("renders an empty failed assistant message as one terminal status", async () => {
    const runId = "80000000-0000-0000-0000-000000000004";
    const failedDetail: ChatSessionDetail = {
      ...session,
      messages: [{
        id: "70000000-0000-0000-0000-000000000004",
        role: "ASSISTANT",
        status: "FAILED",
        content: "",
        language: "zh",
        runId,
        hidden: false,
        createdAt: "2026-07-31T07:23:25Z",
        citations: [],
      }],
      runs: [{
        id: runId,
        status: "FAILED",
        errorCode: "LLM_OUTPUT_TRUNCATED",
        graphProfileVersion: null,
        graphGeneration: null,
        graphModeRequested: "AUTO",
        graphModeUsed: "HYBRID",
        graphDegraded: false,
        graphDegradationCode: null,
        createdAt: "2026-07-31T07:23:19Z",
        completedAt: "2026-07-31T07:23:25Z",
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/chat/sessions")) {
        return jsonResponse({ items: [session] });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`)) {
        return jsonResponse(failedDetail);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderPage();

    expect(await screen.findAllByText("生成失败")).toHaveLength(1);
  });

  it("keeps AUTO for new runs while a failed global run and its retry retain their own mode", async () => {
    const sourceRunId = "80000000-0000-0000-0000-000000000005";
    const retryRunId = "80000000-0000-0000-0000-000000000006";
    let retryCalled = false;
    let newRunBody: Record<string, unknown> | null = null;
    const failedGlobalDetail: ChatSessionDetail = {
      ...session,
      messages: [{
        id: "70000000-0000-0000-0000-000000000005",
        role: "ASSISTANT",
        status: "FAILED",
        content: "",
        language: "zh",
        runId: sourceRunId,
        hidden: false,
        createdAt: "2026-07-31T07:30:00Z",
        citations: [],
      }],
      runs: [{
        id: sourceRunId,
        status: "FAILED",
        errorCode: "GLOBAL_TIMEOUT",
        graphProfileVersion: null,
        graphGeneration: null,
        graphModeRequested: "GLOBAL_GRAPH",
        graphModeUsed: null,
        graphDegraded: false,
        graphDegradationCode: null,
        answerStrategyRequested: "DEEP_GLOBAL",
        answerStrategyUsed: null,
        createdAt: "2026-07-31T07:29:30Z",
        completedAt: "2026-07-31T07:30:00Z",
      }],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "chat-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/chat/sessions") && (!init?.method || init.method === "GET")) {
        return jsonResponse({ items: [session] });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && (!init?.method || init.method === "GET")) {
        return jsonResponse(failedGlobalDetail);
      }
      if (path.endsWith(`/chat/runs/${sourceRunId}/retry`) && init?.method === "POST") {
        expect(init.body).toBeUndefined();
        retryCalled = true;
        return eventStream([{
          type: "failed",
          data: {
            runId: retryRunId,
            status: "FAILED",
            code: "GLOBAL_TIMEOUT",
            message: "全局分析暂时不可用",
          },
        }]);
      }
      if (path.endsWith(`/chat/sessions/${session.id}/runs`) && init?.method === "POST") {
        newRunBody = JSON.parse(String(init.body)) as Record<string, unknown>;
        return eventStream([{
          type: "failed",
          data: {
            runId: "80000000-0000-0000-0000-000000000007",
            status: "FAILED",
            code: "MODEL_TIMEOUT",
            message: "模型暂时不可用",
          },
        }]);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPage();
    const modeSelect = await screen.findByLabelText("检索模式");
    expect(modeSelect).toHaveValue("AUTO");
    expect(screen.getByText("高级设置 · 智能选择")).toBeInTheDocument();
    expect(await screen.findByText(
      "运行详情 · 全局分析 → 未确定 · 0 条引用",
    )).toBeInTheDocument();

    await actor.click(screen.getByRole("button", { name: "创建新 Run 重试" }));
    expect(await screen.findByText("全局分析暂时不可用")).toBeInTheDocument();
    expect(retryCalled).toBe(true);
    expect(modeSelect).toHaveValue("AUTO");
    expect(screen.getAllByText(
      "运行详情 · 全局分析 → 未确定 · 0 条引用",
    )).toHaveLength(2);

    await actor.type(screen.getByLabelText("问题"), "继续分析");
    await actor.click(screen.getByRole("button", { name: "发送问题" }));
    await waitFor(() => expect(newRunBody).toEqual({
      question: "继续分析",
      graphModeRequested: "AUTO",
    }));
    expect(modeSelect).toHaveValue("AUTO");
    expect(await screen.findByText(
      "运行详情 · 智能选择 → 未确定 · 0 条引用",
    )).toBeInTheDocument();
  });

  it("cancels an active run and retries through a new SSE stream", async () => {
    let activeStreamController: ReadableStreamDefaultController<Uint8Array>;
    let cancelCalled = false;
    let retryCalled = false;
    const encoder = new TextEncoder();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(user);
      if (path.endsWith("/auth/csrf")) {
        return jsonResponse({ token: "chat-csrf", headerName: "X-CSRF-TOKEN" });
      }
      if (path.endsWith("/chat/sessions") && (!init?.method || init.method === "GET")) {
        return jsonResponse({ items: [session] });
      }
      if (path.endsWith(`/chat/sessions/${session.id}`) && (!init?.method || init.method === "GET")) {
        return jsonResponse(emptyDetail());
      }
      if (path.endsWith(`/chat/sessions/${session.id}/runs`) && init?.method === "POST") {
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            activeStreamController = controller;
            controller.enqueue(encoder.encode(
              `event: answer_delta\ndata: ${JSON.stringify({
                runId: "80000000-0000-0000-0000-000000000002",
                text: "尚未完成的回答",
              })}\n\n`,
            ));
          },
        });
        init.signal?.addEventListener("abort", () => {
          activeStreamController.error(new DOMException("Aborted", "AbortError"));
        }, { once: true });
        return new Response(stream, { headers: { "Content-Type": "text/event-stream" } });
      }
      if (path.endsWith("/chat/runs/80000000-0000-0000-0000-000000000002/cancel")) {
        cancelCalled = true;
        return jsonResponse(null, 204);
      }
      if (path.endsWith("/chat/runs/80000000-0000-0000-0000-000000000002/retry")) {
        retryCalled = true;
        return eventStream([
          {
            type: "answer_delta",
            data: {
              runId: "80000000-0000-0000-0000-000000000003",
              text: "重试后回答完成。",
            },
          },
          {
            type: "completed",
            data: {
              runId: "80000000-0000-0000-0000-000000000003",
              messageId: "70000000-0000-0000-0000-000000000003",
              status: "COMPLETED",
            },
          },
        ]);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const actor = userEvent.setup();

    renderPage();
    await actor.type(await screen.findByLabelText("问题"), "这个流程是什么？");
    await actor.click(screen.getByRole("button", { name: "发送问题" }));
    expect(await screen.findByText("尚未完成的回答")).toBeInTheDocument();

    await actor.click(screen.getByRole("button", { name: "停止回答" }));
    expect(await screen.findByText("回答已由你停止。")).toBeInTheDocument();
    expect(cancelCalled).toBe(true);

    await actor.click(screen.getByRole("button", { name: "创建新 Run 重试" }));
    expect(await screen.findByText("重试后回答完成。")).toBeInTheDocument();
    expect(retryCalled).toBe(true);
    expect(screen.queryByText("回答已由你停止。")).not.toBeInTheDocument();
  });
});
