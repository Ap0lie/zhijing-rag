import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { auditActionLabel } from "../auditSemantics";
import { useAuth } from "../auth";
import type { AdminAuditEvent, AdminAuditPage } from "../types";

const MODULE_LABELS: Record<string, string> = {
  ACCESS: "访问治理",
  PIPELINE: "Pipeline",
  RUNTIME_POLICY: "格式策略",
  RETRIEVAL: "检索",
  INDEX: "索引",
  GRAPH: "知识图谱",
  GLOBAL_GRAPH: "公共报告",
  QUERY: "查询智能",
  ANSWER: "回答配置",
  BASELINE: "评测基线",
};

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).format(new Date(value));
}

function Summary({ title, value }: { title: string; value: Record<string, unknown> }) {
  const entries = Object.entries(value).filter(([, item]) => item !== null && item !== undefined);
  return (
    <div className="audit-summary">
      <strong>{title}</strong>
      {entries.length === 0 ? <span>无状态摘要</span> : (
        <dl>{entries.map(([key, item]) => <div key={key}><dt>{key}</dt><dd>{String(item)}</dd></div>)}</dl>
      )}
    </div>
  );
}

export function AdminAuditPage() {
  const { expireSession } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [events, setEvents] = useState<AdminAuditEvent[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [actor, setActor] = useState(searchParams.get("actor") ?? "");
  const [object, setObject] = useState(searchParams.get("object") ?? "");
  const [module, setModule] = useState(searchParams.get("module") ?? "");
  const [action, setAction] = useState(searchParams.get("action") ?? "");

  const load = useCallback(async (append = false, cursor?: string | null) => {
    setState("loading");
    const params = new URLSearchParams(searchParams);
    params.set("size", "30");
    if (cursor) params.set("cursor", cursor);
    else params.delete("cursor");
    try {
      const result = await apiRequest<AdminAuditPage>(`/api/v1/admin/audit-events?${params}`);
      setEvents((current) => append ? [...current, ...result.items] : result.items);
      setNextCursor(result.nextCursor);
      setState("ready");
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setState("error");
    }
  }, [expireSession, searchParams]);

  useEffect(() => { void load(); }, [load]);

  function applyFilters(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams();
    if (module) next.set("module", module);
    if (action.trim()) next.set("action", action.trim());
    if (actor.trim()) next.set("actor", actor.trim());
    if (object.trim()) next.set("object", object.trim());
    setSearchParams(next);
  }

  return (
    <section className="audit-page">
      <header className="audit-toolbar">
        <div><span className="eyebrow">只读治理时间线</span><h2>关键状态变化</h2><p>既有模块事件仍是事实源，本页只做规范化读取。</p></div>
        <button className="primary-button" type="button" disabled={state === "loading"} onClick={() => void load()}>刷新日志</button>
      </header>

      <form className="audit-filters" onSubmit={applyFilters}>
        <label>模块<select value={module} onChange={(event) => setModule(event.target.value)}><option value="">全部模块</option>{Object.entries(MODULE_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
        <label>操作<input value={action} onChange={(event) => setAction(event.target.value)} placeholder="例如 PUBLISH" /></label>
        <label>操作人<input value={actor} onChange={(event) => setActor(event.target.value)} placeholder="用户名" /></label>
        <label>对象<input value={object} onChange={(event) => setObject(event.target.value)} placeholder="名称或 ID" /></label>
        <button className="secondary-button" type="submit">应用筛选</button>
      </form>

      {state === "error" ? <div className="screen-state error-state" role="alert"><p>操作日志加载失败</p><button className="secondary-button" onClick={() => void load()}>重试</button></div> : null}
      <div className="audit-timeline">
        {events.map((event) => (
          <article key={event.sourceEvent} className="audit-event">
            <header>
              <div><span className="status-badge">{MODULE_LABELS[event.module] ?? event.module}</span><strong>{auditActionLabel(event.action)}</strong></div>
              <time dateTime={event.occurredAt}>{formatTime(event.occurredAt)}</time>
            </header>
            <p><strong>{event.actorSnapshot}</strong> 操作了 {event.objectLabel}</p>
            <p className="audit-reason">{event.reason}</p>
            <details>
              <summary>查看状态变化与技术来源</summary>
              <div className="audit-detail-grid"><Summary title="操作前" value={event.before} /><Summary title="操作后" value={event.after} /></div>
              <small>{event.objectType} · {event.objectId} · {event.sourceEvent}</small>
            </details>
          </article>
        ))}
        {state === "loading" && events.length === 0 ? <div className="screen-state"><span className="spinner" /><p>正在加载操作日志</p></div> : null}
        {state === "ready" && events.length === 0 ? <div className="screen-state empty-state"><strong>没有匹配的操作记录</strong><p>调整筛选条件后重试</p></div> : null}
      </div>
      {nextCursor ? <button className="secondary-button audit-more" type="button" disabled={state === "loading"} onClick={() => void load(true, nextCursor)}>加载更多</button> : null}
    </section>
  );
}
