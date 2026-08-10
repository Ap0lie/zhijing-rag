import type { ReactNode } from "react";

import type { AdminValueState } from "../types";

const stateLabels: Record<Exclude<AdminValueState, "VALUE">, string> = {
  NOT_APPLICABLE: "—",
  NOT_AVAILABLE: "暂无数据",
  ERROR: "获取失败",
  BLOCKED: "已阻断",
  STALE: "待更新",
};

export function AdminValue({
  state,
  children,
  reasonCode,
}: {
  state: AdminValueState;
  children?: ReactNode;
  reasonCode?: string | null;
}) {
  const label = state === "VALUE" ? children : children ?? stateLabels[state];
  return (
    <span className={`admin-value admin-value-${state.toLowerCase().replaceAll("_", "-")}`} title={reasonCode ?? undefined}>
      {label}
    </span>
  );
}
