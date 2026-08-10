const terms = {
  MODEL_REVISION: {
    label: "模型版本",
    description: "模型文件或服务端声明的固定版本，用于复现实验与发布结果。",
  },
  HEALTH_PROBE_LATENCY: {
    label: "健康探测耗时",
    description: "后台健康检查请求的耗时，只反映服务可达性，不是线上检索或问答延迟。",
  },
} as const;

export function AdminTerm({ term }: { term: keyof typeof terms }) {
  const value = terms[term];
  const [open, setOpen] = useState(false);
  return (
    <span className="admin-term">
      <button type="button" aria-expanded={open} onClick={() => setOpen((current) => !current)}>
        {value.label}<span aria-hidden="true">?</span>
      </button>
      {open ? <span className="admin-term-popover" role="tooltip">{value.description}</span> : null}
    </span>
  );
}
import { useState } from "react";
