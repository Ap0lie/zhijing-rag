import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { auditActionLabel } from "../auditSemantics";
import { useAuth } from "../auth";
import { OperationImpactPanel } from "../components/OperationImpactPanel";
import type {
  AdminAuditPage,
  OperationImpact,
  UserAccessView,
  UserDocumentGrant,
  UserDocumentGrantPage,
} from "../types";

const ACCESS_LABELS: Record<UserDocumentGrant["accessSource"], string> = {
  PUBLIC: "公共范围",
  OWNER: "文档所有者",
  EXPLICIT: "明确授权",
  NONE: "未授权",
};

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  }).format(new Date(value));
}

export function UserDetailPage() {
  const { userId = "" } = useParams();
  const { expireSession } = useAuth();
  const [access, setAccess] = useState<UserAccessView | null>(null);
  const [grants, setGrants] = useState<UserDocumentGrantPage | null>(null);
  const [audit, setAudit] = useState<AdminAuditPage | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [page, setPage] = useState(0);
  const [changes, setChanges] = useState<Record<string, boolean>>({});
  const [reason, setReason] = useState("");
  const [impact, setImpact] = useState<OperationImpact | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    setState("loading");
    try {
      const params = new URLSearchParams({ query: appliedQuery, page: String(page), size: "20" });
      const [nextAccess, nextGrants, nextAudit] = await Promise.all([
        apiRequest<UserAccessView>(`/api/v1/admin/users/${userId}/access`, { signal }),
        apiRequest<UserDocumentGrantPage>(`/api/v1/admin/users/${userId}/document-grants?${params}`, { signal }),
        apiRequest<AdminAuditPage>(`/api/v1/admin/audit-events?object=${encodeURIComponent(userId)}&size=8`, { signal }),
      ]);
      setAccess(nextAccess);
      setGrants(nextGrants);
      setAudit(nextAudit);
      setChanges({});
      setImpact(null);
      setState("ready");
    } catch (caught) {
      if (signal?.aborted) return;
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setState("error");
    }
  }, [appliedQuery, expireSession, page, userId]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const pendingChanges = useMemo(() => grants?.items.flatMap((item) => {
    const value = changes[item.documentId];
    return value === undefined || value === item.granted
      ? []
      : [{ documentId: item.documentId, granted: value, expectedAclVersion: item.aclVersion }];
  }) ?? [], [changes, grants]);

  function search(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    setAppliedQuery(query.trim());
  }

  function toggle(item: UserDocumentGrant, value: boolean) {
    setChanges((current) => ({ ...current, [item.documentId]: value }));
    setImpact(null);
    setMessage(null);
  }

  async function submitChanges() {
    if (!access || pendingChanges.length === 0) return;
    setError(null);
    setMessage(null);
    if (!impact) {
      try {
        const preview = await apiRequest<OperationImpact>("/api/v1/admin/operation-impact/preflight", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            operation: "DOCUMENT_GRANT_BATCH",
            objectId: userId,
            parameters: { changeCount: pendingChanges.length },
          }),
        });
        setImpact(preview);
      } catch (caught) {
        setError(caught instanceof ApiError ? caught.message : "影响预检失败");
      }
      return;
    }
    if (impact.blockers.length > 0 || reason.trim().length < 8) return;
    setSaving(true);
    try {
      await apiRequest(`/api/v1/admin/users/${userId}/document-grants`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          changes: pendingChanges,
          idempotencyKey: crypto.randomUUID(),
          reason: reason.trim(),
          confirmation: impact.confirmation,
          expectedUserSecurityVersion: access.user.securityVersion ?? impact.factVersion,
        }),
      });
      setReason("");
      setMessage(`已应用 ${pendingChanges.length} 项明确授权变化`);
      await load();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "文档权限保存失败");
      setImpact(null);
    } finally {
      setSaving(false);
    }
  }

  if (state === "loading") {
    return <div className="screen-state"><span className="spinner" /><p>正在加载用户权限</p></div>;
  }
  if (state === "error" || !access || !grants) {
    return <div className="screen-state error-state" role="alert"><p>用户权限加载失败</p><button className="secondary-button" onClick={() => void load()}>重试</button></div>;
  }

  const summary = access.access;
  return (
    <section className="user-access-page">
      <header className="user-access-hero">
        <div>
          <Link to="/admin/users">← 返回用户列表</Link>
          <h2>{access.user.username}</h2>
          <p>{access.user.role === "ADMIN" ? "管理员" : "普通用户"} · {access.user.enabled ? "允许登录" : "已禁用"} · 安全版本 {access.user.securityVersion}</p>
        </div>
        <span className={`status-badge ${access.user.enabled ? "ready" : "failed"}`}>{access.user.enabled ? "启用" : "禁用"}</span>
      </header>

      <section className="admin-card">
        <header><div><span className="eyebrow">基本信息</span><h3>有效访问范围</h3></div></header>
        {summary.platformAccess ? (
          <div className="platform-access"><strong>全部文档</strong><p>管理员通过平台角色访问全部未删除文档，空 Grant 不代表没有权限。</p></div>
        ) : (
          <dl className="access-summary-grid">
            <div><dt>公共</dt><dd>{summary.publicDocuments}</dd></div>
            <div><dt>拥有</dt><dd>{summary.ownedDocuments}</dd></div>
            <div><dt>明确授权</dt><dd>{summary.explicitGrants}</dd></div>
            <div><dt>去重合计</dt><dd>{summary.totalDocuments}</dd></div>
          </dl>
        )}
      </section>

      <section className="admin-card grant-workspace">
        <header>
          <div><span className="eyebrow">文档权限</span><h3>明确授权</h3><p>公共和所有者权限只读；这里只修改受限文档的明确授权。</p></div>
        </header>
        <form className="grant-toolbar" onSubmit={search}>
          <input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索文档标题" aria-label="搜索文档标题" />
          <button className="secondary-button" type="submit">搜索</button>
        </form>
        <div className="grant-list">
          {grants.items.map((item) => {
            const checked = changes[item.documentId] ?? item.granted;
            return (
              <article key={item.documentId} className={`grant-row ${item.editable ? "" : "derived"}`}>
                <label>
                  <input type="checkbox" checked={checked} disabled={!item.editable} onChange={(event) => toggle(item, event.target.checked)} />
                  <span><strong>{item.title}</strong><small>{ACCESS_LABELS[item.accessSource]} · ACL v{item.aclVersion} · 所有者 {item.ownerUsername}</small></span>
                </label>
                <Link to={`/documents/${item.documentId}`}>查看文档</Link>
              </article>
            );
          })}
          {grants.items.length === 0 ? <div className="table-state empty-state"><strong>没有匹配文档</strong></div> : null}
        </div>
        <footer className="grant-pagination">
          <button className="secondary-button" type="button" disabled={page === 0} onClick={() => setPage((value) => Math.max(value - 1, 0))}>上一页</button>
          <span>第 {page + 1} 页 · 共 {grants.total} 份文档</span>
          <button className="secondary-button" type="button" disabled={(page + 1) * grants.size >= grants.total} onClick={() => setPage((value) => value + 1)}>下一页</button>
        </footer>
        {pendingChanges.length > 0 ? (
          <div className="grant-save">
            <label>审计理由<input value={reason} onChange={(event) => { setReason(event.target.value); setImpact(null); }} minLength={8} maxLength={500} placeholder="说明为什么调整访问范围（至少 8 字符）" /></label>
            {impact ? <OperationImpactPanel impact={impact} /> : null}
            {error ? <div className="form-error" role="alert">{error}</div> : null}
            <button className="primary-button" type="button" disabled={saving || (impact !== null && (impact.blockers.length > 0 || reason.trim().length < 8))} onClick={() => void submitChanges()}>
              {saving ? "保存中" : impact ? "确认并应用" : `查看 ${pendingChanges.length} 项变化的影响`}
            </button>
          </div>
        ) : null}
        {message ? <div className="success-message" role="status">{message}</div> : null}
      </section>

      <section className="admin-card">
        <header><div><span className="eyebrow">操作记录</span><h3>最近用户与权限事件</h3></div><Link to={`/admin/audit?object=${userId}`}>查看全部</Link></header>
        <div className="compact-audit-list">
          {audit?.items.map((event) => (
            <article key={event.sourceEvent}><strong>{auditActionLabel(event.action)}</strong><span>{event.actorSnapshot} · {formatTime(event.occurredAt)}</span><p>{event.reason}</p></article>
          ))}
          {audit?.items.length === 0 ? <p className="muted-copy">暂无操作记录</p> : null}
        </div>
      </section>
    </section>
  );
}
