import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import type {
  OperationImpact,
  PipelineJobAttempt,
  PipelineRecoveryResponse,
  PipelineRevisionPage,
  PipelineRevisionSummary,
  PipelineStage,
  PipelineStatus,
} from "../types";

const EMPTY_PAGE: PipelineRevisionPage = {
  items: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
  counts: { attention: 0, failed: 0, quarantined: 0, running: 0, completed: 0 },
};
const STAGES: PipelineStage[] = ["INGEST", "PARSE", "CHUNK", "EMBED", "INDEX"];

const STATUS_LABEL: Record<string, string> = {
  PENDING: "等待中", RUNNING: "运行中", SUCCEEDED: "已完成", FAILED: "失败",
  QUARANTINED: "已隔离", CANCELLED: "已取消", NOT_AVAILABLE: "尚未产生",
  ACTIVE: "已生效", PROJECTED: "已投影", ELIGIBLE: "可进入公共报告",
  STALE: "待追平", NOT_APPLICABLE: "不适用", NOT_AVAILABLE_PROJECTION: "暂无投影",
};

function formatTime(value: string | null) {
  if (!value) return "尚未产生";
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatDuration(value: number | null) {
  if (value === null) return "等待完成";
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} 秒`;
}

function statusLabel(value: string) {
  return STATUS_LABEL[value] ?? value;
}

function latestActionable(revision: PipelineRevisionSummary) {
  return revision.jobs
    .filter((job) => job.manualActionCode)
    .slice()
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))[0] ?? null;
}

export function PipelineJobsPage() {
  const { expireSession } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [data, setData] = useState<PipelineRevisionPage>(EMPTY_PAGE);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [queryDraft, setQueryDraft] = useState(searchParams.get("query") ?? "");
  const [recovery, setRecovery] = useState<{ revision: PipelineRevisionSummary; job: PipelineJobAttempt } | null>(null);
  const [impact, setImpact] = useState<OperationImpact | null>(null);
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [recovering, setRecovering] = useState(false);
  const requestRef = useRef<AbortController | null>(null);

  const page = Math.max(0, Number(searchParams.get("page") ?? "0") || 0);
  const status = searchParams.get("status") ?? "";
  const stage = searchParams.get("stage") ?? "";
  const attention = searchParams.get("attention") === "true";
  const expandedRevision = searchParams.get("revision");

  const updateParams = useCallback((changes: Record<string, string | null>, resetPage = true) => {
    const next = new URLSearchParams(searchParams);
    Object.entries(changes).forEach(([key, value]) => {
      if (value) next.set(key, value);
      else next.delete(key);
    });
    if (resetPage) next.delete("page");
    setSearchParams(next);
  }, [searchParams, setSearchParams]);

  const load = useCallback(() => {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setState("loading");
    const params = new URLSearchParams({ page: String(page), size: "20" });
    if (attention) params.set("attention", "true");
    if (status) params.set("status", status);
    if (stage) params.set("stage", stage);
    if (searchParams.get("query")) params.set("documentQuery", searchParams.get("query")!);
    apiRequest<PipelineRevisionPage>(`/api/v1/admin/pipeline-revisions?${params}`, { signal: controller.signal })
      .then((result) => {
        if (!controller.signal.aborted) {
          setData(result);
          setState("ready");
        }
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) return expireSession();
        setState("error");
      });
  }, [attention, expireSession, page, searchParams, stage, status]);

  useEffect(() => {
    load();
    return () => requestRef.current?.abort();
  }, [load]);

  async function openRecovery(revision: PipelineRevisionSummary, job: PipelineJobAttempt) {
    setRecovery({ revision, job });
    setImpact(null);
    setReason("");
    setConfirmed(false);
    try {
      const value = await apiRequest<OperationImpact>("/api/v1/admin/operation-impact/preflight", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ operation: "PIPELINE_RECOVER", objectId: job.id, parameters: {} }),
      });
      setImpact(value);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) return expireSession();
      setMessage({ text: caught instanceof ApiError ? caught.message : "恢复影响预检失败", error: true });
    }
  }

  async function submitRecovery() {
    if (!recovery || !impact || impact.blockers.length || reason.trim().length < 8 || !confirmed) return;
    setRecovering(true);
    try {
      const response = await apiRequest<PipelineRecoveryResponse>(
        `/api/v1/admin/pipeline-jobs/${recovery.job.id}/recover`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            reason: reason.trim(),
            confirmation: "RECOVER_PIPELINE_JOB",
            idempotencyKey: `pipeline-recover:${recovery.job.id}:${Date.now()}`,
          }),
        },
      );
      setRecovery(null);
      setMessage({ text: `${response.revision.documentTitle} R${response.revision.revisionNumber} 已人工重新排队`, error: false });
      load();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) return expireSession();
      setMessage({ text: caught instanceof ApiError ? caught.message : "人工恢复失败", error: true });
    } finally {
      setRecovering(false);
    }
  }

  const filters = [
    { label: "待处理", count: data.counts.attention, changes: { attention: "true", status: null } },
    { label: "仅失败", count: data.counts.failed, changes: { attention: null, status: "FAILED" } },
    { label: "仅隔离", count: data.counts.quarantined, changes: { attention: null, status: "QUARANTINED" } },
    { label: "运行中", count: data.counts.running, changes: { attention: null, status: "RUNNING" } },
    { label: "已完成", count: data.counts.completed, changes: { attention: null, status: "SUCCEEDED" } },
  ];

  return (
    <section className="pipeline-page revision-workbench">
      <header className="workbench-intro">
        <div><p className="eyebrow">Revision Pipeline</p><h2>处理工作台</h2><p>每个文档版本只占一行；失败、隔离和下游投影分开呈现。</p></div>
        <button className="secondary-button" type="button" onClick={load}>刷新事实</button>
      </header>

      <nav className="pipeline-quick-filters" aria-label="Pipeline 快速筛选">
        {filters.map((filter) => {
          const selected = filter.changes.attention === "true" ? attention : status === filter.changes.status;
          return <button key={filter.label} type="button" className={selected ? "active" : ""} aria-pressed={selected} onClick={() => updateParams({ ...filter.changes, revision: null })}><span>{filter.label}</span><strong>{filter.count}</strong></button>;
        })}
        <button type="button" className={!attention && !status ? "active" : ""} onClick={() => updateParams({ attention: null, status: null, revision: null })}>全部</button>
      </nav>

      <form className="pipeline-toolbar" onSubmit={(event) => { event.preventDefault(); updateParams({ query: queryDraft.trim() || null, revision: null }); }}>
        <label><span>搜索文档</span><input value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} placeholder="标题或关键字" /></label>
        <label><span>阶段</span><select value={stage} onChange={(event) => updateParams({ stage: event.target.value || null, revision: null })}><option value="">全部阶段</option>{STAGES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
        <button className="secondary-button" type="submit">应用筛选</button>
      </form>

      {message ? <div role={message.error ? "alert" : "status"} className={message.error ? "form-error" : "success-message"}>{message.text}</div> : null}

      <div className="revision-workbench-list" aria-busy={state === "loading"}>
        {data.items.map((revision) => {
          const expanded = expandedRevision === revision.revisionId;
          const actionable = latestActionable(revision);
          return (
            <article className={`revision-workbench-row ${revision.aggregateStatus.toLowerCase()}`} key={revision.revisionId}>
              <button className="revision-row-summary" type="button" aria-expanded={expanded} onClick={() => updateParams({ revision: expanded ? null : revision.revisionId }, false)}>
                <span className="revision-document"><strong>{revision.documentTitle}</strong><small>{revision.documentFormat} · R{revision.revisionNumber}{revision.currentRevision ? " · 当前版本" : ""}</small></span>
                <span><small>当前阶段</small><strong>{revision.currentStage}</strong></span>
                <span><small>总状态</small><strong className={`pipeline-status ${revision.aggregateStatus.toLowerCase()}`}>{statusLabel(revision.aggregateStatus)}</strong></span>
                <span><small>下一步</small><strong>{revision.nextActionLabel}</strong></span>
                <span><small>最近更新</small><strong>{formatTime(revision.updatedAt)}</strong></span>
                <span className="revision-expand">{expanded ? "收起" : "展开"}</span>
              </button>

              {revision.isolationReason ? <div className="quarantine-callout" role="alert"><strong>隔离原因 · {revision.isolationCode}</strong><p>{revision.isolationReason}</p><span>确定性隔离不会通过普通重试绕过；请按下方建议处理。</span></div> : null}

              {expanded ? (
                <div className="revision-workbench-detail">
                  <section><h3>主处理时间线</h3><ol className="revision-stage-strip">{revision.stages.map((fact) => <li key={fact.stage} className={fact.status.toLowerCase()}><strong>{fact.stage}</strong><span>{statusLabel(fact.status)}</span><small>{fact.source === "JOB" ? "任务事实" : "派生事实"}</small></li>)}</ol></section>
                  <section><h3>任务尝试</h3><div className="job-attempt-list">{revision.jobs.map((job) => <JobAttemptRow key={job.id} job={job} />)}</div></section>
                  <section><h3>下游投影</h3><div className="projection-grid">{Object.values(revision.downstream).map((projection) => <div key={projection.kind}><strong>{projection.kind}</strong><span>{statusLabel(projection.status)}</span><small>{projection.generation ? `Generation ${projection.generation}` : "尚未发布"}{projection.reasonCode ? ` · ${projection.reasonCode}` : ""}</small></div>)}</div></section>
                  <footer className="revision-actions">
                    <Link className="secondary-button" to={`/documents/${revision.documentId}`}>查看文档版本</Link>
                    {revision.nextActionCode === "MANUAL_REQUEUE" && actionable?.manualActionCode === "MANUAL_REQUEUE" ? <button className="primary-button" type="button" onClick={() => void openRecovery(revision, actionable)}>{actionable.automaticRetryExhausted ? "人工重新排队" : "恢复任务"}</button> : null}
                    {revision.nextActionCode === "SWITCH_PARSER" && actionable?.manualActionCode === "SWITCH_PARSER" ? <Link className="primary-button" to={`/documents/${revision.documentId}?revision=${revision.revisionId}&action=parser`}>切换 Parser</Link> : null}
                    {revision.nextActionCode === "CHECK_PROVIDER" && actionable?.manualActionCode === "CHECK_PROVIDER" ? <Link className="primary-button" to="/admin/overview">检查 Provider</Link> : null}
                    {revision.nextActionCode === "CREATE_REPARSE_REVISION" && actionable?.manualActionCode === "CREATE_REPARSE_REVISION" ? <Link className="primary-button" to={`/documents/${revision.documentId}?revision=${revision.revisionId}&action=reparse`}>创建重解析版本</Link> : null}
                  </footer>
                </div>
              ) : null}
            </article>
          );
        })}
        {state === "loading" ? <div className="table-state"><span className="spinner" /><p>正在聚合 Revision Pipeline</p></div> : null}
        {state === "error" ? <div className="table-state error-state"><p>Pipeline 聚合加载失败</p><button className="secondary-button" type="button" onClick={load}>重试</button></div> : null}
        {state === "ready" && data.items.length === 0 ? <div className="table-state empty-state"><strong>当前没有匹配的 Revision</strong><p>切换快速筛选或清除搜索条件。</p></div> : null}
      </div>

      {data.totalPages > 1 ? <nav className="pagination" aria-label="Revision Pipeline 分页"><button className="secondary-button" disabled={page === 0} onClick={() => updateParams({ page: String(page - 1), revision: null }, false)}>上一页</button><span>第 {page + 1} / {data.totalPages} 页，共 {data.totalElements} 个 Revision</span><button className="secondary-button" disabled={page + 1 >= data.totalPages} onClick={() => updateParams({ page: String(page + 1), revision: null }, false)}>下一页</button></nav> : null}

      {recovery ? <div className="modal-backdrop" role="presentation"><section className="operation-dialog" role="dialog" aria-modal="true" aria-labelledby="recovery-title"><header><div><p className="eyebrow">人工恢复</p><h2 id="recovery-title">{recovery.revision.documentTitle} · R{recovery.revision.revisionNumber}</h2></div><button className="text-button" type="button" onClick={() => setRecovery(null)}>关闭</button></header>{impact ? <><div className="impact-summary"><strong>此操作会影响</strong><ul>{impact.immediateEffects.map((item) => <li key={item}>{item}</li>)}</ul><strong>不会影响</strong><ul>{impact.notAffected.map((item) => <li key={item}>{item}</li>)}</ul>{impact.blockers.length ? <div className="form-error" role="alert">{impact.blockers.join("；")}</div> : null}</div><label><span>审计理由</span><textarea value={reason} onChange={(event) => setReason(event.target.value)} placeholder="说明为什么需要人工恢复（8–500 字）" /></label><label className="confirm-check"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} /><span>我已核对影响范围，并确认保留原失败历史</span></label><footer><button className="secondary-button" type="button" onClick={() => setRecovery(null)}>取消</button><button className="primary-button" type="button" disabled={recovering || impact.blockers.length > 0 || reason.trim().length < 8 || !confirmed} onClick={() => void submitRecovery()}>{recovering ? "提交中" : "确认人工重新排队"}</button></footer></> : <div className="table-state"><span className="spinner" /><p>正在计算影响范围</p></div>}</section></div> : null}
    </section>
  );
}

function JobAttemptRow({ job }: { job: PipelineJobAttempt }) {
  return <article className="job-attempt"><header><span className={`pipeline-status ${job.status.toLowerCase()}`}>{statusLabel(job.status)}</span><strong>{job.stage}</strong><small>自动尝试 {job.attempt}/{job.maxAttempts}</small></header><p>{job.quarantineReason ?? job.errorMessage ?? (job.leaseOwner ? `Worker ${job.leaseOwner}` : "无异常")}</p><footer><span>{formatDuration(job.durationMs)}</span><span>{formatTime(job.updatedAt)}</span>{job.automaticRetryExhausted ? <strong>自动重试已停止</strong> : null}</footer></article>;
}
