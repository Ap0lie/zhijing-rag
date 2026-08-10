import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import {
  SourceLocation,
  sourceLocationText,
} from "./SourceLocation";

const pdfLocator = {
  kind: "PAGE" as const,
  startUnit: "10000000-0000-0000-0000-000000000001",
  endUnit: "10000000-0000-0000-0000-000000000002",
  startOffset: 3,
  endOffset: 18,
  address: "pages:3-4",
  sourceTextHash: "a".repeat(64),
  normalizationVersion: "pdf-page-v1",
  startPage: 3,
  endPage: 4,
  sourceLabel: "第 3–4 页",
};

describe("SourceLocation", () => {
  it("uses the server label and opens an authorized PDF page", () => {
    render(
      <SourceLocation
        source={{
          documentFormat: "PDF",
          sourceLocator: pdfLocator,
          sourceLabel: "第 3–4 页",
        }}
        documentId="document-1"
        revisionId="revision-1"
        revisionNumber={2}
      />,
    );

    expect(screen.getByRole("link", { name: "R2 · 第 3–4 页" })).toHaveAttribute(
      "href",
      "/api/v1/documents/document-1/revisions/revision-1/download?inline=true#page=3",
    );
  });

  it("uses legacy PDF pages only when the locator is absent", () => {
    expect(sourceLocationText({ startPage: 5, endPage: 6 })).toBe("第 5–6 页");
  });

  it("links a non-PDF locator to its authorized structure block", () => {
    render(
      <SourceLocation
        source={{
          documentFormat: "MARKDOWN",
          sourceLabel: "检索策略 · 第 7–10 行",
          sourceLocator: {
            ...pdfLocator,
            kind: "HEADING_BLOCK",
            address: "heading:retrieval/lines:7-10",
            startPage: null,
            endPage: null,
            sourceLabel: "检索策略 · 第 7–10 行",
          },
        }}
        documentId="document-1"
        revisionId="revision-1"
      />,
    );

    expect(screen.getByRole("link", { name: "检索策略 · 第 7–10 行" }))
      .toHaveAttribute(
        "href",
        "/documents/document-1?revision=revision-1&source=10000000-0000-0000-0000-000000000001",
      );
  });
});
