import { useState } from "react";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import type { ParserEngine, PipelineJob } from "../types";

export function ParserDecisionSummary({ job }: { job: PipelineJob }) {
  if (job.stage !== "PARSE") return <span>—</span>;
  const terminalWithoutDecision = ["SUCCEEDED", "FAILED", "QUARANTINED", "CANCELLED"].includes(job.status)
    && !job.parserProvider
    && !job.parserSelectedEngine
    && !job.parserDecisionCode;
  const flags = [
    job.parserOcrRequired ? "OCR" : null,
    job.parserMulticolumnCandidate ? "多栏" : null,
    job.parserTableCandidate ? "表格" : null,
    job.parserImageCandidate ? "图片" : null,
  ].filter(Boolean);
  return (
    <div className="parser-decision">
      <strong>
        {terminalWithoutDecision
          ? "历史任务 · 决策未记录"
          : `${job.parserRequestedEngine ?? "AUTO"} → ${job.parserProvider ?? job.parserSelectedEngine ?? "待判定"}`}
      </strong>
      {job.parserEngineVersion ? <span>{job.parserEngineVersion}</span> : null}
      {job.parserDecisionCode ? <small>{job.parserDecisionCode}</small> : null}
      {job.parserPageCount ? <small>{job.parserPageCount} 页{flags.length ? ` · ${flags.join(" / ")}` : ""}</small> : null}
      {!job.parserPageCount && job.parserSourceUnitCount
        ? <small>{job.parserSourceUnitCount} 个来源单元{flags.length ? ` · ${flags.join(" / ")}` : ""}</small>
        : null}
      {job.parserModelRevision ? (
        <small title={job.parserModelRevision}>模型 revision {job.parserModelRevision.slice(0, 12)}…</small>
      ) : null}
      {job.parserModelManifestChecksum ? (
        <small title={job.parserModelManifestChecksum}>
          模型清单 SHA-256 {job.parserModelManifestChecksum.slice(0, 12)}…
        </small>
      ) : null}
      {job.parserOverrideReason ? <small title={job.parserOverrideReason}>覆盖：{job.parserOverrideReason}</small> : null}
    </div>
  );
}

export function ParserOverrideForm({
  job,
  onCreated,
}: {
  job: PipelineJob;
  onCreated: (created: PipelineJob) => void;
}) {
  const { expireSession } = useAuth();
  const [open, setOpen] = useState(false);
  const [target, setTarget] = useState<Exclude<ParserEngine, "AUTO">>("MINERU");
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (job.stage !== "PARSE"
      || (job.documentFormat && job.documentFormat !== "PDF")
      || !job.retryable
      || !["FAILED", "QUARANTINED"].includes(job.status)) return null;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!confirmed || reason.trim().length < 8) return;
    setWorking(true);
    setError(null);
    try {
      const created = await apiRequest<PipelineJob>(
        `/api/v1/admin/pipeline-jobs/${job.id}/parser-override`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            targetParser: target,
            reason: reason.trim(),
            confirmation: "OVERRIDE_PARSER",
            idempotencyKey,
          }),
        },
      );
      onCreated(created);
      setOpen(false);
      setReason("");
      setConfirmed(false);
      setIdempotencyKey(crypto.randomUUID());
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "解析器覆盖失败");
    } finally {
      setWorking(false);
    }
  }

  if (!open) {
    return <button className="text-button" type="button" onClick={() => setOpen(true)}>覆盖解析器</button>;
  }

  return (
    <form className="parser-override-form" onSubmit={submit}>
      <label>
        <span>目标解析器</span>
        <select value={target} onChange={(event) => setTarget(event.target.value as Exclude<ParserEngine, "AUTO">)}>
          <option value="MINERU">MinerU</option>
          <option value="PDFBOX">PDFBox</option>
        </select>
      </label>
      <label>
        <span>审计理由</span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          minLength={8}
          maxLength={500}
          placeholder="说明为什么需要更换解析器"
          required
        />
      </label>
      <label className="parser-confirm">
        <input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
        <span>确认创建新的 PARSE Job；旧任务保持不变</span>
      </label>
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      <div className="parser-override-actions">
        <button className="primary-button" type="submit" disabled={working || !confirmed || reason.trim().length < 8}>
          {working ? "创建中" : "创建覆盖任务"}
        </button>
        <button className="secondary-button" type="button" disabled={working} onClick={() => setOpen(false)}>取消</button>
      </div>
    </form>
  );
}
