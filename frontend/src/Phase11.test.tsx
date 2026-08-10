import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";

import { EvaluationOperationsPanel } from "./pages/EvaluationOperationsPanel";

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("offers contract smoke and gated real fault verification explicitly", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse([])));

  render(<EvaluationOperationsPanel view="drills" />);

  expect(await screen.findByText("受控故障验证")).toBeInTheDocument();
  expect(screen.getByText(/Contract Smoke 只验证状态机/)).toBeInTheDocument();
  const mode = screen.getByLabelText("执行模式");
  expect(mode).toHaveValue("SIMULATION_ONLY");
  await userEvent.selectOptions(mode, "REAL_VERIFY");
  expect(mode).toHaveValue("REAL_VERIFY");
  expect(screen.getByText("REAL_VERIFY")).toBeInTheDocument();
});

it("refreshes the visible observability panel", async () => {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path.endsWith("/gates")) return jsonResponse([]);
    return jsonResponse({
      enabled: true,
      capturedAt: "2026-07-28T00:00:00Z",
      windowHours: 24,
      captureContent: false,
      highCardinalityLabels: false,
      retentionDays: 14,
      workloadPermit: {
        onlineChatActive: false,
        activeChatRuns: 0,
        evaluationMayClaim: true,
        pauseReason: null,
      },
      queues: {},
      rates: {},
      latencyP50Ms: {},
      latencyP95Ms: {},
      embeddingCache: {},
      graph: {},
    });
  });
  vi.stubGlobal("fetch", fetchMock);

  render(<EvaluationOperationsPanel view="observability" />);
  await screen.findByText("在线 Chat 优先");
  await userEvent.click(screen.getByRole("button", { name: "刷新观测" }));

  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
});
