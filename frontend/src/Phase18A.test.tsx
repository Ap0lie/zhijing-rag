import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";

import { MultiformatReleasePanel } from "./pages/MultiformatReleasePanel";
import type { MultiformatRelease } from "./types";

const formats = ["PDF", "TXT", "MARKDOWN", "HTML", "DOCX", "PPTX", "XLSX", "CSV"];

function release(state: "PREVIEW" | "FROZEN" = "PREVIEW"): MultiformatRelease {
  return {
    state,
    version: "multiformat-release-v4",
    datasetVersionId: state === "FROZEN" ? "dataset-version-id" : null,
    subjectId: state === "FROZEN" ? "subject-id" : null,
    subjectReadinessStatus: state === "FROZEN" ? "BLOCKED_PREREQUISITE" : null,
    subjectBlockedReason: state === "FROZEN" ? "Phase 18D 多格式真实 Evaluator 尚未启用" : null,
    subjectSnapshotHash: state === "FROZEN" ? "a".repeat(64) : null,
    readyFormats: 8,
    totalFormats: 8,
    formats: formats.map((documentFormat) => ({
      documentFormat,
      mappingStatus: "READY",
      blockedReason: null,
      documentId: `${documentFormat}-document`,
      revisionId: `${documentFormat}-revision`,
      childChunkId: `${documentFormat}-child`,
      sourceSpanId: `${documentFormat}-span`,
      documentTitle: `${documentFormat} 代表文档`,
      documentVisibility: "ALL_USERS",
      aclVersion: 1,
      originalFilename: `sample.${documentFormat.toLowerCase()}`,
      fileSha256: "b".repeat(64),
      sourceTitle: `${documentFormat} source`,
      sourceLicense: "PROJECT",
      sourceRevision: "revision-1",
      expectedParserProvider: `${documentFormat}_PARSER`,
      expectedParserVersion: "parser-v1",
      expectedChunkerVersion: "chunker-v1",
      locatorKind: documentFormat === "PDF" ? "PAGE" : "SECTION",
      sourceLabel: documentFormat === "PDF" ? "第 1 页" : "第一节",
      locatorHash: "c".repeat(64),
      securityAssertions: ["RESOURCE_LIMIT"],
    })),
  };
}

it("requires an explicit confirmation before freezing all eight formats", async () => {
  const freeze = vi.fn(async () => undefined);
  render(<MultiformatReleasePanel release={release()} working={false} onFreeze={freeze} />);

  expect(screen.getByText("8/8 可冻结")).toBeInTheDocument();
  expect(formats.every((format) => screen.getByText(format))).toBe(true);
  const button = screen.getByRole("button", { name: "冻结 multiformat-release-v4" });
  expect(button).toBeDisabled();

  await userEvent.click(screen.getByRole("checkbox"));
  await userEvent.click(button);

  expect(freeze).toHaveBeenCalledWith("冻结当前多格式运行版本");
});

it("keeps frozen facts immutable while allowing a confirmed Phase 18D subject refresh", async () => {
  const freeze = vi.fn(async () => undefined);
  render(<MultiformatReleasePanel release={release("FROZEN")} working={false} onFreeze={freeze} />);

  expect(screen.getByText("已冻结")).toBeInTheDocument();
  expect(screen.getByText("Phase 18D 多格式真实 Evaluator 尚未启用")).toBeInTheDocument();
  const refresh = screen.getByRole("button", { name: "刷新冻结配置" });
  expect(refresh).toBeDisabled();
  await userEvent.click(screen.getByRole("checkbox"));
  await userEvent.click(refresh);
  expect(freeze).toHaveBeenCalledWith("冻结当前多格式运行版本");
});
