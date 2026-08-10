import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import App from "./App";
import { resetCsrfToken } from "./api";
import { AuthProvider } from "./auth";
import type { PipelineJob, PipelineRevisionPage } from "./types";

const admin = { id: "10000000-0000-0000-0000-000000000001", username: "admin", role: "ADMIN" } as const;
const reader = { id: "10000000-0000-0000-0000-000000000002", username: "reader", role: "USER" } as const;
const documentId = "20000000-0000-0000-0000-000000000001";
const revisionId = "30000000-0000-0000-0000-000000000001";

const failedJob: PipelineJob = {
  id: "40000000-0000-0000-0000-000000000001",
  documentId,
  revisionId,
  revisionNumber: 1,
  documentTitle: "平台手册",
  stage: "PARSE",
  status: "FAILED",
  attempt: 3,
  maxAttempts: 3,
  leaseOwner: null,
  startedAt: "2026-07-22T10:00:00Z",
  completedAt: "2026-07-22T10:00:03Z",
  durationMs: 3000,
  errorCode: "PARSE_TIMEOUT",
  errorMessage: "解析超过 120 秒",
  quarantineReason: null,
  parserRequestedEngine: "AUTO",
  parserSelectedEngine: "MINERU",
  parserDecisionCode: "SCANNED_OR_OCR",
  parserEngineVersion: "3.4.4",
  parserModelRevision: "bff20d4ae2bf202df9f45284b4d43681555a97ed",
  parserModelManifestChecksum: "16981dc38075623ddec4fdcf7f055c89688f44a92076f534919f465be46c82e7",
  createdAt: "2026-07-22T10:00:00Z",
  updatedAt: "2026-07-22T10:00:03Z",
  retryable: true,
};

function revisionPage(overrides: Partial<PipelineRevisionPage["items"][number]> = {}): PipelineRevisionPage {
  return {
    items: [{
      documentId,
      revisionId,
      revisionNumber: 1,
      documentTitle: "平台手册",
      documentFormat: "PDF",
      revisionStatus: "FAILED",
      currentRevision: false,
      aggregateStatus: "FAILED",
      currentStage: "PARSE",
      updatedAt: failedJob.updatedAt,
      nextActionCode: "MANUAL_REQUEUE",
      nextActionLabel: "人工重新排队",
      automaticRetryExhausted: true,
      isolationCode: null,
      isolationReason: null,
      parserProvider: "MINERU",
      stages: [
        { stage: "INGEST", status: "SUCCEEDED", source: "REVISION", updatedAt: failedJob.createdAt },
        { stage: "PARSE", status: "FAILED", source: "JOB", updatedAt: failedJob.updatedAt },
        { stage: "CHUNK", status: "NOT_AVAILABLE", source: "DERIVED", updatedAt: null },
        { stage: "EMBED", status: "NOT_AVAILABLE", source: "DERIVED", updatedAt: null },
        { stage: "INDEX", status: "NOT_AVAILABLE", source: "DERIVED", updatedAt: null },
      ],
      jobs: [{
        id: failedJob.id,
        stage: "PARSE",
        status: "FAILED",
        attempt: 3,
        maxAttempts: 3,
        parserProvider: "MINERU",
        parserDecisionCode: "SCANNED_OR_OCR",
        leaseOwner: null,
        leaseExpiresAt: null,
        heartbeatAt: null,
        errorCode: "PARSE_TIMEOUT",
        errorMessage: "解析超过 120 秒",
        quarantineReason: null,
        startedAt: failedJob.startedAt,
        completedAt: failedJob.completedAt,
        durationMs: failedJob.durationMs,
        createdAt: failedJob.createdAt,
        updatedAt: failedJob.updatedAt,
        automaticRetryExhausted: true,
        manualActionCode: "MANUAL_REQUEUE",
      }],
      downstream: {
        index: { kind: "INDEX", generation: 7, status: "STALE", reasonCode: "INDEX_NOT_PROJECTED" },
        graph: { kind: "GRAPH", generation: 6, status: "STALE", reasonCode: "GRAPH_STALE" },
        global: { kind: "GLOBAL", generation: 3, status: "NOT_APPLICABLE", reasonCode: "RESTRICTED_SOURCE" },
      },
      ...overrides,
    }],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    counts: { attention: 1, failed: 1, quarantined: 0, running: 0, completed: 0 },
  };
}

const documentDetail = {
  document: {
    id: documentId,
    title: "平台手册",
    visibility: "RESTRICTED",
    ownerUsername: "admin",
    aclVersion: 1,
    effectiveRevisionId: revisionId,
    latestRevisionNumber: 1,
    latestRevisionStatus: "READY",
    createdAt: "2026-07-22T10:00:00Z",
    updatedAt: "2026-07-22T10:00:00Z",
  },
  currentRevisionId: null,
  grantedUsers: [],
  revisions: [{
    id: revisionId,
    revisionNumber: 1,
    status: "READY",
    originalFilename: "guide.pdf",
    fileSizeBytes: 128,
    contentHash: "a".repeat(64),
    createdAt: "2026-07-22T10:00:00Z",
    current: false,
    effective: true,
  }],
} as const;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderApp(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>);
}

describe("Phase 4 pipeline UI", () => {
  beforeEach(resetCsrfToken);
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("blocks a regular user from the Pipeline administration route", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith("/auth/me")) return jsonResponse(reader);
      throw new Error(`Unexpected request: ${String(input)}`);
    }));

    renderApp("/admin/pipeline");

    expect(await screen.findByText("没有访问权限")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Pipeline 任务" })).not.toBeInTheDocument();
  });

  it("shows loading and a recoverable list error", async () => {
    let resolveList!: (response: Response) => void;
    const listResponse = new Promise<Response>((resolve) => {
      resolveList = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/admin/pipeline-revisions?")) return listResponse;
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp("/admin/pipeline");

    expect(await screen.findByText("正在聚合 Revision Pipeline")).toBeInTheDocument();
    resolveList(jsonResponse({ message: "数据库不可用" }, 503));
    expect(await screen.findByText("Pipeline 聚合加载失败")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeEnabled();
  });

  it("filters revisions and confirms an audited manual recovery", async () => {
    const requestedUrls: string[] = [];
    const queuedJob: PipelineJob = { ...failedJob, status: "PENDING", attempt: 0, errorCode: null, errorMessage: null, retryable: false };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/admin/pipeline-revisions?") && (!init?.method || init.method === "GET")) {
        requestedUrls.push(path);
        return jsonResponse(revisionPage());
      }
      if (path.endsWith("/auth/csrf")) return jsonResponse({ token: "pipeline-csrf", headerName: "X-CSRF-TOKEN" });
      if (path.endsWith("/admin/operation-impact/preflight") && init?.method === "POST") {
        return jsonResponse({ operation: "PIPELINE_RECOVER", objectType: "PIPELINE_JOB", objectId: failedJob.id, confirmation: "RECOVER_PIPELINE_JOB", factVersion: 3, immediateEffects: ["重新排队"], asynchronousEffects: ["Worker 重新领取"], notAffected: ["不自动发布"], blockers: [], affectedCounts: { currentAttempt: 3 }, rollback: "再次停止" });
      }
      if (path.endsWith(`/admin/pipeline-jobs/${failedJob.id}/recover`) && init?.method === "POST") {
        expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("pipeline-csrf");
        expect(JSON.parse(String(init.body))).toMatchObject({ confirmation: "RECOVER_PIPELINE_JOB", reason: "人工核对后重新处理此任务" });
        return jsonResponse({ job: queuedJob, revision: revisionPage({ aggregateStatus: "PENDING", nextActionLabel: "等待 Worker" }).items[0], impact: {}, replayed: false });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/admin/pipeline");
    await screen.findByText("平台手册");
    await user.click(screen.getByRole("button", { name: /仅失败/ }));
    await waitFor(() => expect(requestedUrls.some((url) => url.includes("status=FAILED"))).toBe(true));
    await user.click(screen.getByRole("button", { name: /平台手册.*展开/ }));
    expect(screen.getByText("自动重试已停止")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "人工重新排队" }));
    await user.type(await screen.findByPlaceholderText("说明为什么需要人工恢复（8–500 字）"), "人工核对后重新处理此任务");
    await user.click(screen.getByRole("checkbox", { name: "我已核对影响范围，并确认保留原失败历史" }));
    await user.click(screen.getByRole("button", { name: "确认人工重新排队" }));
    expect(await screen.findByText("平台手册 R1 已人工重新排队")).toBeInTheDocument();
  });

  it("keeps superseded failures visible as history without recovery actions", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/admin/pipeline-revisions?")) return jsonResponse(revisionPage({
        currentRevision: false,
        nextActionCode: "HISTORICAL",
        nextActionLabel: "历史记录，无需处理",
        automaticRetryExhausted: false,
      }));
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/admin/pipeline");
    expect(await screen.findByText("历史记录，无需处理")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /平台手册.*展开/ }));
    expect(screen.queryByRole("button", { name: "人工重新排队" })).not.toBeInTheDocument();
  });

  it("routes deterministic quarantine to parser handling instead of blind retry", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.includes("/admin/pipeline-revisions?")) return jsonResponse(revisionPage({
        aggregateStatus: "QUARANTINED", nextActionCode: "SWITCH_PARSER", nextActionLabel: "切换 Parser",
        isolationCode: "SCANNED_PDF", isolationReason: "扫描件需要 OCR",
        jobs: [{ ...revisionPage().items[0].jobs[0], status: "QUARANTINED", errorCode: "SCANNED_PDF", quarantineReason: "扫描件需要 OCR", manualActionCode: "SWITCH_PARSER" }],
      }));
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp("/admin/pipeline");
    await user.click(await screen.findByRole("button", { name: /平台手册.*展开/ }));
    expect(screen.getByRole("alert")).toHaveTextContent("扫描件需要 OCR");
    const link = screen.getByRole("link", { name: "切换 Parser" });
    expect(link).toHaveAttribute("href", `/documents/${documentId}?revision=${revisionId}&action=parser`);
    expect(screen.queryByRole("button", { name: "人工重新排队" })).not.toBeInTheDocument();
  });

  it("keeps the timeline, revision and parsed artifacts traceable to the same source", async () => {
    const succeededJob: PipelineJob = {
      ...failedJob,
      status: "SUCCEEDED",
      attempt: 1,
      errorCode: null,
      errorMessage: null,
      durationMs: 840,
      retryable: false,
    };
    const artifacts = {
      revisionId,
      parserVersion: "pdfbox-3",
      chunkerVersion: "parent-child-v1",
      tokenCounterVersion: "unicode-v1",
      markdown: "# 平台手册\n\n正文内容",
      contentBlocks: [{ id: "block-1", type: "PARAGRAPH", order: 1, text: "正文内容", headingPath: ["平台手册"], startPage: 1, endPage: 1, startOffset: 5, endOffset: 9, charCount: 4, tokenCount: 4 }],
      chunks: [
        { id: "parent-00000000", type: "PARENT", parentChunkId: null, order: 1, text: "父块上下文", headingPath: ["平台手册"], startPage: 1, endPage: 2, charCount: 20, tokenCount: 18, searchable: false },
        { id: "child-00000000", type: "CHILD", parentChunkId: "parent-00000000", order: 1, text: "子块检索文本", headingPath: ["平台手册"], startPage: 1, endPage: 1, charCount: 8, tokenCount: 8, searchable: true },
      ],
    };
    const structure = {
      revisionId,
      resultPackage: {
        parserVersion: "mineru-3.4.4",
        parserRevision: "mineru-model-v1",
        inputHash: "a".repeat(64),
        outputHash: "b".repeat(64),
        schemaVersion: "mineru-content-list-v1",
        offsetEncoding: "UTF16_CODE_UNIT",
        pageCount: 2,
      },
      tables: [{
        id: "table-1",
        order: 0,
        contentBlockId: "block-table",
        previewAssetId: null,
        pageNumber: 1,
        boundingBox: { pageNumber: 1, x0: 100, y0: 120, x1: 900, y1: 420 },
        caption: "版本对照",
        sourceTextHash: "c".repeat(64),
        cells: [
          { id: "cell-1", rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 2, header: true, text: "版本", sourceTextHash: "d".repeat(64) },
          { id: "cell-2", rowIndex: 1, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: false, text: "V1", sourceTextHash: "e".repeat(64) },
          { id: "cell-3", rowIndex: 1, columnIndex: 1, rowSpan: 1, columnSpan: 1, header: false, text: "稳定", sourceTextHash: "f".repeat(64) },
        ],
      }],
      images: [{
        id: "image-1",
        order: 0,
        type: "FIGURE",
        contentBlockId: null,
        pageNumber: 2,
        boundingBox: { pageNumber: 2, x0: 120, y0: 200, x1: 880, y1: 700 },
        filename: "architecture.png",
        mediaType: "image/png",
        byteSize: 2048,
        contentHash: "1".repeat(64),
        caption: "平台架构图",
        contentUrl: `/api/v1/documents/${documentId}/revisions/${revisionId}/assets/image-1/content`,
      }],
      sourceSpans: [{
        id: "span-1",
        chunkId: "child-00000000",
        chunkType: "CHILD",
        chunkOrder: 1,
        order: 0,
        startPage: 1,
        endPage: 2,
        pageStartOffset: 18,
        pageEndOffset: 32,
        chunkStartOffset: 0,
        chunkEndOffset: 8,
        sourceTextHash: "2".repeat(64),
        boundingBoxes: [
          { pageNumber: 1, x0: 100, y0: 100, x1: 900, y1: 180 },
          { pageNumber: 2, x0: 100, y0: 80, x1: 900, y1: 150 },
        ],
      }],
      truncated: false,
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentId}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/pipeline`)) return jsonResponse([succeededJob]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/artifacts`)) return jsonResponse(artifacts);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/structure`)) return jsonResponse(structure);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentId}`);

    expect(await screen.findByText("# 平台手册", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("SUCCEEDED")).toBeInTheDocument();
    expect(screen.getByText("Parser pdfbox-3")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "ContentBlock (1)" }));
    const source = screen.getByRole("link", { name: "R1 · 第 1 页" });
    expect(source).toHaveAttribute("href", `/api/v1/documents/${documentId}/revisions/${revisionId}/download?inline=true#page=1`);
    expect(source).toHaveAttribute("target", "_blank");
    expect(screen.getByText("正文内容")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Parent Chunk" }));
    expect(screen.getByText("父块上下文")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Child Chunk" }));
    expect(screen.getByText("子块检索文本")).toBeInTheDocument();
    expect(screen.getByText(/Parent parent-0/)).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Tables (1)" }));
    expect(screen.getByText("版本对照")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "版本" })).toHaveAttribute("colspan", "2");

    await user.click(screen.getByRole("tab", { name: "Images (1)" }));
    expect(screen.getByRole("img", { name: "平台架构图" })).toHaveAttribute("src", structure.images[0].contentUrl);

    await user.click(screen.getByRole("tab", { name: "SourceSpan (1)" }));
    expect(screen.getByText(/Chunk offset 0–8/)).toBeInTheDocument();
    expect(screen.getByText(/p\.1 \(100, 100\).*p\.2 \(100, 80\)/)).toBeInTheDocument();
  });

  it("switches spreadsheet sheets and lets the user separate cached values from formulas", async () => {
    const spreadsheetDetail = {
      ...documentDetail,
      document: {
        ...documentDetail.document,
        title: "经营数据",
        documentFormat: "XLSX",
        mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
      revisions: [{
        ...documentDetail.revisions[0],
        originalFilename: "sales.xlsx",
        documentFormat: "XLSX",
        mediaType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      }],
    };
    const succeededJob: PipelineJob = {
      ...failedJob,
      documentTitle: "经营数据",
      status: "SUCCEEDED",
      errorCode: null,
      errorMessage: null,
      retryable: false,
      parserSelectedEngine: null,
      parserProvider: "XLSX_POI",
      parserDecisionCode: "XLSX_EVENT_STREAM",
    };
    const artifacts = {
      revisionId,
      parserVersion: "xlsx-poi-event-5.5.1-v1",
      chunkerVersion: "parent-child-v1",
      tokenCounterVersion: "unicode-v1",
      markdown: "# 销售汇总\n\n| 编号 | 利润 |\n| --- | --- |\n| S-001 | 50 |",
      contentBlocks: [],
      chunks: [],
    };
    const table = (
      id: string,
      sheet: string,
      caption: string,
      cells: unknown[],
    ) => {
      const qualifiedSheet = sheet.includes("!")
        ? `'${sheet.replaceAll("'", "''")}'`
        : sheet;
      return ({
      id,
      order: id === "sales-table" ? 0 : id === "stock-table" ? 1 : 2,
      contentBlockId: `block-${id}`,
      previewAssetId: null,
      pageNumber: null,
      boundingBox: {
        sourceUnitId: `unit-${id}`,
        sourceUnitOrder: id === "sales-table" ? 1 : 2,
        sourceUnitKind: "SHEET",
        pageNumber: null,
        x0: 0,
        y0: 0,
        x1: 1000,
        y1: 1000,
      },
      caption,
      sourceTextHash: "a".repeat(64),
      cells,
      documentFormat: "XLSX",
      sourceLocator: {
        kind: "CELL_RANGE",
        startUnit: `unit-${id}`,
        endUnit: `unit-${id}`,
        startOffset: 0,
        endOffset: 0,
        address: "{}",
        sourceTextHash: "a".repeat(64),
        normalizationVersion: "xlsx-sheet-cell-range-v1",
        startPage: null,
        endPage: null,
        sourceLabel: `${qualifiedSheet}!A1:D3`,
      },
      sourceLabel: `${qualifiedSheet}!A1:D3`,
    });
    };
    const structure = {
      revisionId,
      resultPackage: {
        parserVersion: "xlsx-poi-event-5.5.1-v1",
        parserRevision: "apache-poi-5.5.1",
        inputHash: "a".repeat(64),
        outputHash: "b".repeat(64),
        schemaVersion: "parsed-package-v3",
        offsetEncoding: "UTF16_CODE_UNIT",
        pageCount: null,
        sourceUnitCount: 2,
        documentFormat: "XLSX",
        parserProvider: "XLSX_POI",
        textEncoding: "binary",
        parseDecision: "XLSX_EVENT_STREAM",
        delimiter: null,
      },
      tables: [
        table("sales-table", "销售汇总", "销售汇总 · A1:D3", [
          { id: "sales-header", rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: true, text: "编号", sourceTextHash: "c".repeat(64), cellReference: "A1", cellType: "TEXT", rawValue: "编号", displayValue: "编号", formulaText: null, numberFormat: "General" },
          { id: "sales-profit", rowIndex: 1, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: false, text: "50", sourceTextHash: "d".repeat(64), cellReference: "D2", cellType: "FORMULA", rawValue: "50.0", displayValue: "50", formulaText: "=B2-C2", numberFormat: "#,##0.00" },
        ]),
        table("stock-table", "库存", "库存 · A1:B2", [
          { id: "stock-header", rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: true, text: "SKU", sourceTextHash: "e".repeat(64), cellReference: "A1", cellType: "TEXT", rawValue: "SKU", displayValue: "SKU", formulaText: null, numberFormat: "General" },
          { id: "stock-value", rowIndex: 1, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: false, text: "42", sourceTextHash: "f".repeat(64), cellReference: "B2", cellType: "NUMBER", rawValue: "42", displayValue: "42", formulaText: null, numberFormat: "0" },
        ]),
        table("special-table", "A!B", "A!B · A1:B2", [
          { id: "special-header", rowIndex: 0, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: true, text: "编号", sourceTextHash: "1".repeat(64), cellReference: "A1", cellType: "TEXT", rawValue: "编号", displayValue: "编号", formulaText: null, numberFormat: "General" },
          { id: "special-value", rowIndex: 1, columnIndex: 0, rowSpan: 1, columnSpan: 1, header: false, text: "S-01", sourceTextHash: "2".repeat(64), cellReference: "A2", cellType: "TEXT", rawValue: "S-01", displayValue: "S-01", formulaText: null, numberFormat: "General" },
        ]),
      ],
      images: [],
      sourceSpans: [],
      truncated: false,
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentId}`)) return jsonResponse(spreadsheetDetail);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/pipeline`)) return jsonResponse([succeededJob]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/artifacts`)) return jsonResponse(artifacts);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/structure`)) return jsonResponse(structure);
      throw new Error(`Unexpected request: ${path}`);
    }));
    const user = userEvent.setup();

    renderApp(`/documents/${documentId}`);
    await user.click(await screen.findByRole("tab", { name: "Tables (3)" }));

    expect(screen.getByLabelText("选择工作表")).toHaveValue("销售汇总");
    expect(screen.getByText("=B2-C2")).toBeInTheDocument();
    expect(screen.getByText("#,##0.00")).toBeInTheDocument();
    expect(screen.getByText("销售汇总 · A1:D3")).toBeInTheDocument();
    expect(screen.queryByText("库存 · A1:B2")).not.toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "显示公式与格式" }));
    expect(screen.queryByText("=B2-C2")).not.toBeInTheDocument();
    expect(screen.getByText("50")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("选择工作表"), "库存");
    expect(screen.getByText("库存 · A1:B2")).toBeInTheDocument();
    expect(screen.queryByText("销售汇总 · A1:D3")).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("选择工作表"), "A!B");
    expect(screen.getByText("A!B · A1:B2")).toBeInTheDocument();
    expect(screen.queryByText("库存 · A1:B2")).not.toBeInTheDocument();
  });

  it("keeps core artifacts visible when only structured assets fail to load", async () => {
    const succeededJob: PipelineJob = {
      ...failedJob,
      status: "SUCCEEDED",
      attempt: 1,
      errorCode: null,
      errorMessage: null,
      retryable: false,
    };
    const artifacts = {
      revisionId,
      parserVersion: "pdfbox-3",
      chunkerVersion: "parent-child-v1",
      tokenCounterVersion: "unicode-v1",
      markdown: "# 平台手册\n\n基础解析仍然可用",
      contentBlocks: [],
      chunks: [],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentId}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/pipeline`)) return jsonResponse([succeededJob]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/artifacts`)) return jsonResponse(artifacts);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/structure`)) {
        return jsonResponse({ message: "结构服务暂不可用" }, 503);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp(`/documents/${documentId}`);

    expect(await screen.findByText("# 平台手册", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("SUCCEEDED")).toBeInTheDocument();
    expect(screen.getByText("结构资产加载失败，Markdown、ContentBlock 与 Chunk 仍可查看。")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Tables (不可用)" })).toBeInTheDocument();
    expect(screen.queryByText("解析信息加载失败")).not.toBeInTheDocument();
  });

  it("keeps a processing failure local to the artifact panel", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path.endsWith("/auth/me")) return jsonResponse(admin);
      if (path.endsWith(`/documents/${documentId}`)) return jsonResponse(documentDetail);
      if (path.endsWith("/admin/users")) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/pipeline`)) return jsonResponse([]);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/artifacts`)) return jsonResponse({ message: "产物不可用" }, 503);
      if (path.endsWith(`/documents/${documentId}/revisions/${revisionId}/structure`)) return jsonResponse({ message: "结构不可用" }, 404);
      throw new Error(`Unexpected request: ${path}`);
    }));

    renderApp(`/documents/${documentId}`);

    expect(await screen.findByRole("heading", { name: "平台手册" })).toBeInTheDocument();
    expect(await screen.findByText("解析信息加载失败")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "下载当前可用版本" })).toBeEnabled();
  });
});
