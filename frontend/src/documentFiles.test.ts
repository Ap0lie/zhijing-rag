import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  loadDocumentFormats,
  resetDocumentFormatsCache,
  validateDocumentFile,
} from "./documentFiles";
import type { DocumentFormatsResponse } from "./types";

const response: DocumentFormatsResponse = {
  schemaVersion: "document-formats-v2",
  formats: [
    {
      format: "PDF",
      enabled: true,
      displayName: "PDF",
      extensions: [".pdf"],
      mediaTypes: ["application/pdf"],
      maxFileSizeBytes: 50 * 1024 * 1024,
      locatorKinds: ["PAGE"],
      parserProviders: [{ provider: "PDFBOX", available: true, reasonCode: null }],
      parserOverrideAllowed: true,
    },
    {
      format: "TXT",
      enabled: true,
      displayName: "TXT",
      extensions: [".txt"],
      mediaTypes: ["text/plain"],
      maxFileSizeBytes: 10 * 1024 * 1024,
      locatorKinds: ["LINE_RANGE", "HEADING_BLOCK"],
      parserProviders: [{ provider: "TEXT", available: true, reasonCode: null }],
      parserOverrideAllowed: false,
    },
    {
      format: "MARKDOWN",
      enabled: true,
      displayName: "Markdown",
      extensions: [".md", ".markdown"],
      mediaTypes: ["text/markdown", "text/x-markdown", "text/plain"],
      maxFileSizeBytes: 10 * 1024 * 1024,
      locatorKinds: ["LINE_RANGE", "HEADING_BLOCK"],
      parserProviders: [{ provider: "MARKDOWN", available: true, reasonCode: null }],
      parserOverrideAllowed: false,
    },
    {
      format: "HTML",
      enabled: true,
      displayName: "HTML",
      extensions: [".html", ".htm"],
      mediaTypes: ["text/html", "application/xhtml+xml"],
      maxFileSizeBytes: 10 * 1024 * 1024,
      locatorKinds: ["DOM_PATH", "HEADING_BLOCK"],
      parserProviders: [{ provider: "HTML", available: true, reasonCode: null }],
      parserOverrideAllowed: false,
    },
  ],
};

describe("document format capabilities", () => {
  beforeEach(resetDocumentFormatsCache);

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("shares one in-flight capability request", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(response), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    const [first, second] = await Promise.all([
      loadDocumentFormats(),
      loadDocumentFormats(),
    ]);

    expect(first).toEqual(response);
    expect(second).toBe(first);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("accepts a browser file with an empty MIME when its extension is enabled", () => {
    const file = new File(["%PDF-1.7"], "guide.PDF");

    expect(validateDocumentFile(file, response.formats)).toBeNull();
  });

  it("accepts enabled text formats and rejects unsupported extensions", () => {
    expect(validateDocumentFile(
      new File(["# 标题"], "guide.MD", { type: "text/markdown" }),
      response.formats,
    )).toBeNull();
    expect(validateDocumentFile(
      new File(["<h1>标题</h1>"], "guide.html", { type: "text/html" }),
      response.formats,
    )).toBeNull();
    expect(validateDocumentFile(
      new File(["legacy"], "guide.doc"),
      response.formats,
    )).toContain(".pdf");
  });

  it("clears a failed request so the capability endpoint can be retried", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: "暂不可用" }), {
        status: 503,
        headers: { "Content-Type": "application/json" },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify(response), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadDocumentFormats()).rejects.toThrow("暂不可用");
    await expect(loadDocumentFormats()).resolves.toEqual(response);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
