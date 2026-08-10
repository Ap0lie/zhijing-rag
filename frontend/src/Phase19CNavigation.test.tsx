import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";

import { EvaluationPage } from "./pages/EvaluationPage";
import type { EvaluationRun } from "./types";

const run: EvaluationRun = {
  id: "run-10000000-0000-0000-0000-000000000001",
  evaluationSubjectId: "subject-1",
  subjectName: "导航连续性评测",
  subjectType: "RETRIEVAL",
  datasetVersionId: "dataset-version-1",
  datasetKey: "navigation",
  datasetVersion: "navigation-v1",
  originalRunId: null,
  status: "SUCCEEDED",
  evaluatorVersion: "phase19c-navigation-v1",
  totalCases: 1,
  completedCases: 1,
  succeededCases: 1,
  failedCases: 0,
  blockedCases: 0,
  cancelRequested: false,
  attempt: 1,
  leaseOwner: null,
  leaseExpiresAt: null,
  errorCode: null,
  errorMessage: null,
  createdAt: "2026-08-02T10:00:00Z",
  startedAt: "2026-08-02T10:00:01Z",
  completedAt: "2026-08-02T10:00:02Z",
  updatedAt: "2026-08-02T10:00:02Z",
};

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function installFetch() {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.includes(`/runs/${run.id}/results`)) {
      return jsonResponse({ runId: run.id, page: 0, size: 100, total: 0, items: [] });
    }
    if (path.includes(`/runs/${run.id}/events`)) return jsonResponse([]);
    if (path.includes("/evaluations/runs?")) {
      return jsonResponse({ page: 0, size: 50, total: 1, items: [run] });
    }
    if (path.endsWith("/evaluations/datasets")) return jsonResponse([]);
    if (path.endsWith("/evaluations/targets")) return jsonResponse([]);
    if (path.endsWith("/evaluations/subjects")) return jsonResponse([]);
    if (path.endsWith("/evaluations/multiformat-release")) return jsonResponse(null);
    if (path.endsWith("/admin/answer-profiles")) return jsonResponse([]);
    if (path.endsWith("/admin/answer-profiles/runtime")) {
      return jsonResponse({
        enabled: false,
        modelProvider: "OPENAI_COMPATIBLE",
        modelId: "",
        modelRevision: "",
        endpointIdentity: "",
        promptVersion: "",
        orchestrationVersion: "",
        timeoutMs: 0,
        maxOutputTokens: 0,
        remoteEvidenceAllowed: false,
        remoteMemoryAllowed: false,
      });
    }
    if (path.endsWith("/evaluations/baselines")) return jsonResponse([]);
    if (path.endsWith("/evaluations/feedback")) return jsonResponse([]);
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("scrollTo", vi.fn());
  return fetchMock;
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function renderPage(initialEntry: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <EvaluationPage />
      <LocationProbe />
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("keeps tab and report selection in the URL without reloading overview data", async () => {
  const fetchMock = installFetch();
  const user = userEvent.setup();
  renderPage("/admin/evaluations");

  expect(await screen.findByText("选择不可变 DatasetVersion")).toBeInTheDocument();
  const shell = screen.getByRole("heading", { name: "评测工作台" });
  const overviewRequests = () => fetchMock.mock.calls.filter(([input]) =>
    String(input).endsWith("/evaluations/datasets")).length;
  expect(overviewRequests()).toBe(1);

  const tabs = screen.getByRole("navigation", { name: "评测中心页签" });
  await user.click(within(tabs).getByRole("button", { name: "评测运行" }));
  expect(await screen.findByRole("heading", { name: "Run 队列" })).toBeInTheDocument();
  expect(screen.getByTestId("location")).toHaveTextContent("tab=runs");
  await user.click(within(tabs).getByRole("button", { name: "报告" }));
  expect(await screen.findByRole("heading", { name: "导航连续性评测" })).toBeInTheDocument();
  expect(screen.getByTestId("location")).toHaveTextContent(`tab=report&run=${run.id}`);
  expect(screen.getByRole("heading", { name: "评测工作台" })).toBe(shell);

  await user.click(within(tabs).getByRole("button", { name: "基线" }));
  await user.click(within(tabs).getByRole("button", { name: "报告" }));
  expect(await screen.findByRole("heading", { name: "导航连续性评测" })).toBeInTheDocument();
  expect(screen.getByTestId("location")).toHaveTextContent(`tab=report&run=${run.id}`);
  expect(overviewRequests()).toBe(1);
  expect(fetchMock.mock.calls.filter(([input]) =>
    String(input).includes(`/runs/${run.id}/results`))).toHaveLength(1);
});

it("writes advanced tool state to the URL", async () => {
  installFetch();
  const user = userEvent.setup();
  renderPage("/admin/evaluations");

  expect(await screen.findByText("选择不可变 DatasetVersion")).toBeInTheDocument();
  await user.click(screen.getByText("高级工具", { selector: "summary" }));
  for (const [label, value] of [
    ["数据管理", "datasets"],
    ["运行对比", "compare"],
    ["反馈审核", "feedback"],
    ["观测", "observability"],
    ["故障演练", "drills"],
  ]) {
    await user.click(screen.getByRole("button", { name: label }));
    expect(screen.getByTestId("location")).toHaveTextContent(`tab=${value}`);
    expect(screen.getByRole("heading", { name: "评测工作台" })).toBeInTheDocument();
  }
});

it("normalizes an unknown tab without blanking the workspace", async () => {
  installFetch();
  renderPage("/admin/evaluations?tab=unknown");

  expect(await screen.findByRole("heading", { name: "评测工作台" })).toBeInTheDocument();
  expect(await screen.findByText("选择不可变 DatasetVersion")).toBeInTheDocument();
  await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("tab=new"));
});

it("preserves a report deep link for refresh and sharing", async () => {
  installFetch();
  renderPage(`/admin/evaluations?tab=report&run=${run.id}`);

  await waitFor(() => expect(screen.getByTestId("location"))
    .toHaveTextContent(`tab=report&run=${run.id}`));
  expect(await screen.findByRole("heading", { name: "导航连续性评测" })).toBeInTheDocument();
});

it("clears an unsubmitted high-risk reason when leaving its tab", async () => {
  installFetch();
  const user = userEvent.setup();
  renderPage(`/admin/evaluations?tab=runs&run=${run.id}`);

  const reason = await screen.findByRole("textbox", { name: "操作审计理由" });
  await user.clear(reason);
  await user.type(reason, "尚未提交的危险操作理由");

  const tabs = screen.getByRole("navigation", { name: "评测中心页签" });
  await user.click(within(tabs).getByRole("button", { name: "报告" }));
  await user.click(within(tabs).getByRole("button", { name: "评测运行" }));

  expect(await screen.findByRole("textbox", { name: "操作审计理由" }))
    .toHaveValue("管理员手动操作");
});
