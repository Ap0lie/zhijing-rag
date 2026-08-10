import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";

import { ReleaseReportPanel } from "./pages/ReleaseReportPanel";
import type { EvaluationReleaseReport } from "./types";

const report: EvaluationReleaseReport = {
  runId: "run-id",
  runStatus: "SUCCEEDED",
  evaluatorVersion: "phase18d-real-multiformat-v4",
  datasetVersion: "multiformat-release-v4",
  subjectId: "subject-id",
  subjectSnapshotHash: "a".repeat(64),
  frozenSubject: {
    indexGeneration: 8,
    retrievalProfileVersion: "phase6c-hybrid-rerank-v1",
    graphGeneration: 4,
    globalGeneration: 3,
    answerProfileVersion: "answer-deepseek-v1",
    queryProfileVersion: "query-intelligence-v1",
  },
  totalCases: 16,
  succeededCases: 16,
  failedCases: 0,
  blockedCases: 0,
  locatorResolutionRate: 1,
  citationResolutionRate: 1,
  hardGateFailures: 0,
  degradationCount: 1,
  executionBaseline: {
    queryProfileVersion: "query-intelligence-v1",
    plannerCallCount: 8,
    retrievalCallCount: 12,
    rerankCallCount: 8,
    queryDegradedCount: 1,
    memoryContractVersion: "phase14-structured-memory-v1",
    memoryInjectedCount: 0,
    memoryUsedCount: 0,
    memoryTokenCount: 0,
  },
  performance: {
    search: { samples: 8, p50Ms: 120, p95Ms: 240, maxMs: 260, errorRate: 0 },
    chat: { samples: 8, p50Ms: 900, p95Ms: 1800, maxMs: 1900, errorRate: 0 },
  },
  formats: [{
    documentFormat: "PDF",
    caseId: "case-id",
    caseKey: "multiformat:pdf",
    status: "SUCCEEDED",
    documentId: "document-id",
    revisionId: "revision-id",
    locatorKind: "PAGE",
    sourceLabel: "第 1 页",
    hardGatePassed: true,
    citationResolved: true,
    degraded: false,
    degradationCode: null,
    errorCode: null,
    durationMs: 2080,
  }],
  blockers: [],
  unmeasuredItems: ["phase11b.judge.advisory"],
  recommendation: "READY_FOR_BASELINE",
};

it("renders measured release facts and keeps unmeasured judge data explicit", () => {
  render(<ReleaseReportPanel report={report} />);

  expect(screen.getByText("可发布 Baseline")).toBeInTheDocument();
  expect(screen.getAllByText("100.0%")).toHaveLength(2);
  expect(screen.getByText("第 1 页")).toBeInTheDocument();
  expect(screen.getByText(/phase11b\.judge\.advisory/)).toBeInTheDocument();
  expect(screen.getByText("Query / Memory 实际基线")).toBeInTheDocument();
  expect(screen.getByText("phase14-structured-memory-v1")).toBeInTheDocument();
  expect(screen.getByText(/攻击样本均由本 Run 实际执行/)).toBeInTheDocument();
});
