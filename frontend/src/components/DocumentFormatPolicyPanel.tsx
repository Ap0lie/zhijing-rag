import { useEffect, useMemo, useState, type FormEvent } from "react";

import { ApiError, apiRequest } from "../api";
import { resetDocumentFormatsCache } from "../documentFiles";
import type {
  DocumentFormat,
  DocumentFormatCapability,
  DocumentFormatsResponse,
  DocumentRuntimePolicyEvent,
  ParserProvider,
} from "../types";

interface PolicyTarget {
  format: DocumentFormat;
  displayName: string;
  parserProvider: ParserProvider | null;
  enabled: boolean;
}

interface Props {
  onSessionExpired: () => void;
  onMessage: (message: string) => void;
}

const STATUS_LABELS = {
  AVAILABLE: "可用",
  UNAVAILABLE: "不可用",
  DISABLED: "已禁用",
  HEARTBEAT_STALE: "心跳过期",
} as const;

function formatStatus(format: DocumentFormatCapability) {
  return format.runtimeStatus ?? (format.enabled ? "AVAILABLE" : "UNAVAILABLE");
}

function providerStatus(provider: DocumentFormatCapability["parserProviders"][number]) {
  return provider.runtimeStatus ?? (provider.available ? "AVAILABLE" : "UNAVAILABLE");
}

function targetName(target: PolicyTarget) {
  return target.parserProvider
    ? `${target.displayName} / ${target.parserProvider}`
    : target.displayName;
}

export function DocumentFormatPolicyPanel({ onSessionExpired, onMessage }: Props) {
  const [formats, setFormats] = useState<DocumentFormatCapability[]>([]);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [target, setTarget] = useState<PolicyTarget | null>(null);
  const [reason, setReason] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [events, setEvents] = useState<DocumentRuntimePolicyEvent[]>([]);
  const [eventsLoading, setEventsLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function handleError(caught: unknown, fallback: string) {
    if (caught instanceof ApiError && caught.status === 401) {
      onSessionExpired();
      return;
    }
    setError(caught instanceof ApiError ? caught.message : fallback);
  }

  function load() {
    setState("loading");
    setError(null);
    apiRequest<DocumentFormatsResponse>("/api/v1/admin/document-formats")
      .then((response) => {
        setFormats(response.formats);
        setState("ready");
      })
      .catch((caught: unknown) => {
        handleError(caught, "格式运行策略加载失败");
        setState("error");
      });
  }

  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function begin(nextTarget: PolicyTarget) {
    setTarget(nextTarget);
    setReason("");
    setConfirmed(false);
    setError(null);
    setEventsLoading(true);
    try {
      const history = await apiRequest<DocumentRuntimePolicyEvent[]>(
        `/api/v1/admin/document-formats/${nextTarget.format}/events?limit=8`,
      );
      setEvents(history);
    } catch (caught) {
      handleError(caught, "审计历史加载失败");
      setEvents([]);
    } finally {
      setEventsLoading(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!target || !confirmed || reason.trim().length < 8) return;
    const action = target.enabled ? "DISABLE" : "RESTORE";
    const parser = target.parserProvider !== null;
    const confirmation = parser
      ? action === "DISABLE" ? "DISABLE_PARSER" : "RESTORE_PARSER"
      : action === "DISABLE" ? "DISABLE_DOCUMENT_FORMAT" : "RESTORE_DOCUMENT_FORMAT";
    setSubmitting(true);
    setError(null);
    try {
      const response = await apiRequest<DocumentFormatsResponse>(
        `/api/v1/admin/document-formats/${target.format}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            parserProvider: target.parserProvider,
            action,
            confirmation,
            reason: reason.trim(),
          }),
        },
      );
      setFormats(response.formats);
      resetDocumentFormatsCache();
      onMessage(`${targetName(target)}已${action === "DISABLE" ? "禁用" : "恢复"}`);
      const history = await apiRequest<DocumentRuntimePolicyEvent[]>(
        `/api/v1/admin/document-formats/${target.format}/events?limit=8`,
      );
      setEvents(history);
      setTarget(null);
    } catch (caught) {
      handleError(caught, "运行策略更新失败");
    } finally {
      setSubmitting(false);
    }
  }

  const activeFormat = useMemo(
    () => target ? formats.find((format) => format.format === target.format) ?? null : null,
    [formats, target],
  );

  return (
    <section className="format-policy-panel" aria-labelledby="format-policy-title">
      <header>
        <div>
          <p className="eyebrow">运行策略</p>
          <h2 id="format-policy-title">文档格式与解析器</h2>
          <p>禁用只阻止新上传、重解析和新任务领取，已有授权原件仍可下载。</p>
        </div>
        <button className="secondary-button" type="button" onClick={load} disabled={state === "loading"}>
          {state === "loading" ? "刷新中" : "刷新状态"}
        </button>
      </header>

      {state === "loading" ? <div className="inline-state"><span className="spinner" />正在检查 Parser 心跳</div> : null}
      {state === "error" ? <div className="inline-state error-state">{error}<button className="secondary-button" type="button" onClick={load}>重试</button></div> : null}
      {state === "ready" ? (
        <div className="format-policy-grid">
          {formats.map((format) => {
            const status = formatStatus(format);
            const policyEnabled = (format.policyStatus ?? (format.enabled ? "ENABLED" : "DISABLED")) === "ENABLED";
            return (
              <article key={format.format} className="format-policy-card">
                <header>
                  <div><strong>{format.displayName}</strong><small>策略 v{format.policyVersion ?? 1}</small></div>
                  <span className={`runtime-badge ${status.toLowerCase()}`}>{STATUS_LABELS[status]}</span>
                </header>
                <p>{format.extensions.join(" / ")} · 运行中 {format.runningJobs ?? 0}</p>
                <div className="parser-policy-list">
                  {format.parserProviders.map((provider) => {
                    const parserRuntime = providerStatus(provider);
                    const parserEnabled = (provider.policyStatus ?? (provider.available ? "ENABLED" : "DISABLED")) === "ENABLED";
                    return (
                      <div key={provider.provider}>
                        <span><strong>{provider.provider}</strong><small>{STATUS_LABELS[parserRuntime]} · {provider.runningJobs ?? 0} 个任务</small></span>
                        <button
                          className="text-button"
                          type="button"
                          onClick={() => begin({
                            format: format.format,
                            displayName: format.displayName,
                            parserProvider: provider.provider,
                            enabled: parserEnabled,
                          })}
                        >
                          {parserEnabled ? "禁用" : "恢复"}
                        </button>
                      </div>
                    );
                  })}
                </div>
                <button
                  className={policyEnabled ? "danger-button" : "secondary-button"}
                  type="button"
                  onClick={() => begin({
                    format: format.format,
                    displayName: format.displayName,
                    parserProvider: null,
                    enabled: policyEnabled,
                  })}
                >
                  {policyEnabled ? "禁用此格式" : "恢复此格式"}
                </button>
              </article>
            );
          })}
        </div>
      ) : null}

      {target ? (
        <div className="policy-editor" role="dialog" aria-modal="false" aria-labelledby="policy-editor-title">
          <form onSubmit={submit}>
            <header>
              <div>
                <p className="eyebrow">{target.enabled ? "禁用" : "恢复"}</p>
                <h3 id="policy-editor-title">{targetName(target)}</h3>
              </div>
              <button className="text-button" type="button" onClick={() => setTarget(null)}>关闭</button>
            </header>
            {!target.enabled && activeFormat && formatStatus(activeFormat) === "HEARTBEAT_STALE" ? (
              <div className="form-warning">Parser 心跳已过期，恢复会被服务端拒绝；请先恢复 parser-worker。</div>
            ) : null}
            <label htmlFor="policy-reason">审计理由</label>
            <textarea id="policy-reason" value={reason} onChange={(event) => setReason(event.target.value)} minLength={8} maxLength={500} required />
            <label className="confirmation-row">
              <input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
              <span>我确认此次变更会影响新的上传、重解析和任务领取</span>
            </label>
            {error ? <div className="form-error" role="alert">{error}</div> : null}
            <div className="policy-editor-actions">
              <button className="secondary-button" type="button" onClick={() => setTarget(null)}>取消</button>
              <button className={target.enabled ? "danger-button" : "primary-button"} type="submit" disabled={submitting || !confirmed || reason.trim().length < 8}>
                {submitting ? "提交中" : target.enabled ? "确认禁用" : "确认恢复"}
              </button>
            </div>
          </form>
          <aside>
            <h4>最近审计记录</h4>
            {eventsLoading ? <p>加载中…</p> : events.length === 0 ? <p>暂无变更记录</p> : (
              <ol>{events.map((item) => (
                <li key={item.id}>
                  <strong>{item.parserProvider ?? item.documentFormat} · {item.action === "DISABLE" ? "禁用" : "恢复"}</strong>
                  <span>{item.actorUsername} · {new Intl.DateTimeFormat("zh-CN", { dateStyle: "short", timeStyle: "short" }).format(new Date(item.createdAt))}</span>
                  <p>{item.reason}</p>
                </li>
              ))}</ol>
            )}
          </aside>
        </div>
      ) : null}
    </section>
  );
}
