import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import { ApiError, apiRequest, downloadRequest } from "../api";
import { useAuth } from "../auth";
import { DownloadIcon, UploadIcon } from "../components/Icons";
import { RevisionProcessingPanel } from "../components/RevisionProcessingPanel";
import { DocumentFormatBadge } from "../components/SourceLocation";
import {
  enabledDocumentFormats,
  documentFormatForFilename,
  fileAcceptValue,
  loadDocumentFormats,
  validateDocumentFile,
} from "../documentFiles";
import type {
  DocumentDetail,
  DocumentFormatCapability,
  ManagedUser,
  ParserEngine,
  ReparseResponse,
  RevisionStatus,
} from "../types";

const DOWNLOADABLE_REVISION_STATUSES: ReadonlySet<RevisionStatus> = new Set([
  "UPLOADED",
  "PROCESSING",
  "READY",
  "FAILED",
  "QUARANTINED",
]);

function formatBytes(bytes: number) {
  return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1024 / 1024).toFixed(2)} MiB`;
}

export function DocumentDetailPage() {
  const { documentId = "" } = useParams();
  const navigate = useNavigate();
  const { user, expireSession } = useAuth();
  const [detail, setDetail] = useState<DocumentDetail | null>(null);
  const [users, setUsers] = useState<ManagedUser[] | null>([]);
  const [revisionFormats, setRevisionFormats] = useState<DocumentFormatCapability[]>([]);
  const [formatState, setFormatState] = useState<"loading" | "ready" | "error">("loading");
  const [title, setTitle] = useState("");
  const [visibility, setVisibility] = useState<"ALL_USERS" | "RESTRICTED">("RESTRICTED");
  const [grantedUserIds, setGrantedUserIds] = useState<string[]>([]);
  const [aclReason, setAclReason] = useState("管理员更新文档权限");
  const [revisionUpload, setRevisionUpload] = useState<{
    file: File;
    format: DocumentDetail["document"]["documentFormat"];
    idempotencyKey: string;
  } | null>(null);
  const [formatChangeReason, setFormatChangeReason] = useState("");
  const [formatChangeConfirmed, setFormatChangeConfirmed] = useState(false);
  const [reparseParser, setReparseParser] = useState<ParserEngine>("AUTO");
  const [reparseReason, setReparseReason] = useState("");
  const [reparseConfirmed, setReparseConfirmed] = useState(false);
  const [reparseKey, setReparseKey] = useState(() => crypto.randomUUID());
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [working, setWorking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const currentDocumentFormat = detail?.revisions.find((revision) =>
    revision.id === detail.currentRevisionId)?.documentFormat
    ?? detail?.document.documentFormat
    ?? detail?.revisions[0]?.documentFormat
    ?? null;
  const revisionChangesFormat = Boolean(
    revisionUpload?.format
    && currentDocumentFormat
    && revisionUpload.format !== currentDocumentFormat,
  );

  const applyDetail = useCallback((next: DocumentDetail) => {
    setDetail(next);
    setTitle(next.document.title);
    setVisibility(next.document.visibility);
    setGrantedUserIds(next.grantedUsers.map((entry) => entry.id));
  }, []);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    const requests: [Promise<DocumentDetail>, Promise<ManagedUser[] | null>] = [
      apiRequest<DocumentDetail>(`/api/v1/documents/${documentId}`, { signal }),
      user?.role === "ADMIN"
        ? apiRequest<ManagedUser[]>("/api/v1/admin/users", { signal }).catch((caught: unknown) => {
          if (caught instanceof ApiError && caught.status === 401) throw caught;
          return null;
        })
        : Promise.resolve([]),
    ];
    Promise.all(requests)
      .then(([document, managedUsers]) => {
        if (signal?.aborted) return;
        applyDetail(document);
        setUsers(managedUsers);
        setState("ready");
      })
      .catch((caught: unknown) => {
        if (signal?.aborted) return;
        setDetail(null);
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setState("error");
      });
  }, [applyDetail, documentId, expireSession, user?.role]);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  useEffect(() => {
    if (user?.role !== "ADMIN") return;
    let active = true;
    setFormatState("loading");
    loadDocumentFormats()
      .then((response) => {
        if (!active) return;
        setRevisionFormats(enabledDocumentFormats(response));
        setFormatState("ready");
      })
      .catch((caught: unknown) => {
        if (!active) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setFormatState("error");
      });
    return () => {
      active = false;
    };
  }, [expireSession, user?.role]);

  async function saveAcl(event: FormEvent) {
    event.preventDefault();
    setWorking(true);
    setMessage(null);
    setError(null);
    try {
      const updated = await apiRequest<DocumentDetail>(`/api/v1/admin/documents/${documentId}/acl`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: title.trim(),
          visibility,
          grantedUserIds: visibility === "RESTRICTED"
            ? grantedUserIds.filter((id) => users?.some((entry) => entry.id === id && entry.enabled && entry.role === "USER"))
            : [],
          expectedAclVersion: detail?.document.aclVersion,
          reason: aclReason.trim(),
        }),
      });
      applyDetail(updated);
      setAclReason("管理员更新文档权限");
      setMessage("文档信息与访问权限已更新");
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        setError("权限已被其他操作修改，已加载最新状态，请重新确认");
        load();
      } else {
        handleError(caught, "更新失败，请稍后重试");
      }
    } finally {
      setWorking(false);
    }
  }

  function chooseRevisionFile(input: HTMLInputElement) {
    const file = input.files?.[0] ?? null;
    setMessage(null);
    setError(null);
    if (!file) {
      setRevisionUpload(null);
      setFormatChangeReason("");
      setFormatChangeConfirmed(false);
      return;
    }
    if (formatState !== "ready") {
      setRevisionUpload(null);
      setError("上传能力尚未就绪，请稍后重试");
      input.value = "";
      return;
    }
    const validationError = validateDocumentFile(file, revisionFormats);
    if (validationError) {
      setRevisionUpload(null);
      setError(validationError);
      input.value = "";
      return;
    }
    const format = documentFormatForFilename(file.name, revisionFormats);
    if (!format) {
      setRevisionUpload(null);
      setError("无法识别所选文件格式");
      input.value = "";
      return;
    }
    setRevisionUpload({ file, format, idempotencyKey: crypto.randomUUID() });
    setFormatChangeReason("");
    setFormatChangeConfirmed(false);
  }

  async function addRevision(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!revisionUpload
      || (revisionChangesFormat
        && (!formatChangeConfirmed || formatChangeReason.trim().length < 8))) return;
    const form = event.currentTarget;
    setWorking(true);
    setMessage(null);
    setError(null);
    const body = new FormData();
    body.append("file", revisionUpload.file);
    if (revisionChangesFormat) {
      body.append("formatChangeConfirmation", "CHANGE_DOCUMENT_FORMAT");
      body.append("formatChangeReason", formatChangeReason.trim());
    }
    try {
      const updated = await apiRequest<DocumentDetail>(`/api/v1/admin/documents/${documentId}/revisions`, {
        method: "POST",
        headers: { "Idempotency-Key": revisionUpload.idempotencyKey },
        body,
      });
      applyDetail(updated);
      setRevisionUpload(null);
      setFormatChangeReason("");
      setFormatChangeConfirmed(false);
      form.reset();
      setMessage("新 Revision 已上传，旧版本保持不变");
    } catch (caught) {
      handleError(caught, "版本上传失败");
    } finally {
      setWorking(false);
    }
  }

  async function reparse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const sourceRevisionId = detail?.currentRevisionId;
    if (!sourceRevisionId || !reparseConfirmed || reparseReason.trim().length < 8) return;
    setWorking(true);
    setMessage(null);
    setError(null);
    try {
      const created = await apiRequest<ReparseResponse>(
        `/api/v1/admin/documents/${documentId}/reparse`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            sourceRevisionId,
            targetParser: reparseParser,
            idempotencyKey: reparseKey,
            reason: reparseReason.trim(),
            confirmation: "REPARSE",
          }),
        },
      );
      setReparseReason("");
      setReparseConfirmed(false);
      setReparseKey(crypto.randomUUID());
      setMessage(`已创建 R${created.revisionNumber} 重解析任务；R${effectiveRevision?.revisionNumber ?? "—"} 在新版本发布前继续服务`);
      load();
    } catch (caught) {
      handleError(caught, "重解析任务创建失败");
    } finally {
      setWorking(false);
    }
  }

  async function download(revisionId: string) {
    setError(null);
    try {
      const result = await downloadRequest(`/api/v1/documents/${documentId}/revisions/${revisionId}/download`);
      const url = URL.createObjectURL(result.blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = result.filename;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        setDetail(null);
        expireSession();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "下载失败");
    }
  }

  async function removeDocument() {
    if (!window.confirm("删除后文档将立即不可访问，是否继续？")) return;
    setWorking(true);
    try {
      await apiRequest<void>(`/api/v1/admin/documents/${documentId}`, { method: "DELETE" });
      navigate("/", { replace: true });
    } catch (caught) {
      handleError(caught, "删除失败");
      setWorking(false);
    }
  }

  function handleError(caught: unknown, fallback: string) {
    if (caught instanceof ApiError && caught.status === 401) {
      setDetail(null);
      expireSession();
      return;
    }
    if (caught instanceof ApiError && (caught.status === 403 || caught.status === 404)) {
      setDetail(null);
      setState("error");
      return;
    }
    setError(caught instanceof ApiError ? caught.message : fallback);
  }

  if (state === "loading") return <div className="table-state"><span className="spinner" /><p>正在加载文档详情</p></div>;
  if (state === "error" || !detail) return <div className="detail-error"><h2>文档不可访问</h2><p>文档可能已删除或当前账户不再具有权限。</p><Link to="/">返回文档中心</Link></div>;

  const availableUsers = (users ?? []).filter((entry) => entry.enabled && entry.role === "USER");
  const availableUserIds = new Set(availableUsers.map((entry) => entry.id));
  const unavailableGrantedUsers = detail.grantedUsers.filter((entry) => !availableUserIds.has(entry.id));
  const aclEditingUnavailable = user?.role === "ADMIN" && users === null;
  const effectiveRevision = detail.revisions.find((revision) =>
    revision.id === detail.document.effectiveRevisionId
    && revision.effective
    && DOWNLOADABLE_REVISION_STATUSES.has(revision.status));
  const evaluationProvenance = detail.document.effectiveEvaluationProvenance
    ?? detail.document.latestEvaluationProvenance
    ?? null;

  return (
    <section className="document-detail-page">
      <div className="detail-title-row">
        <div>
          <Link className="back-link" to="/">← 返回文档中心</Link>
          <h2>{detail.document.title}</h2>
          <p>
            <DocumentFormatBadge format={detail.document.documentFormat} />
            ACL v{detail.document.aclVersion} · 所有者 {detail.document.ownerUsername}
          </p>
          {evaluationProvenance ? (
            <div className="evaluation-provenance provenance-detail">
              <span className={detail.document.visibility === "ALL_USERS" ? "eval-public-badge" : "eval-restricted-badge"}>
                {detail.document.visibility === "ALL_USERS" ? "[EVAL][PUBLIC]" : "[EVAL][RESTRICTED]"}
              </span>
              <a
                href={evaluationProvenance.sourceUrl}
                target="_blank"
                rel="noreferrer"
              >
                {evaluationProvenance.sourceTitle}
              </a>
              <small>
                {evaluationProvenance.sourceDataset}
                {" · "}{evaluationProvenance.sourceLicense}
                {" · revision "}{evaluationProvenance.sourceRevision.slice(0, 12)}
              </small>
            </div>
          ) : null}
        </div>
        {effectiveRevision ? (
          <button className="secondary-button" onClick={() => download(effectiveRevision.id)}><DownloadIcon />下载当前可用版本</button>
        ) : null}
      </div>

      {message ? <div className="success-message" role="status">{message}</div> : null}
      {error ? <div className="form-error drawer-error" role="alert">{error}</div> : null}

      <div className="detail-grid">
        <section className="detail-card revision-card">
          <h3>Revision 时间线</h3>
          {detail.currentRevisionId ? <p className="field-help">已发布版本由 currentRevisionId 标记。</p> : <p className="pending-note">待解析/索引，当前下载使用最新上传版本。</p>}
          <ol className="revision-timeline">{detail.revisions.map((revision) => (
            <li id={`revision-${revision.id}`} key={revision.id}>
              <div>
                <strong>R{revision.revisionNumber}</strong>
                <DocumentFormatBadge format={revision.documentFormat} />
                <span className="revision-badge">{revision.status}</span>
                {revision.current ? <span className="current-badge">当前发布</span> : revision.effective ? <span className="effective-badge">当前可用</span> : null}
              </div>
              <p>{revision.originalFilename} · {formatBytes(revision.fileSizeBytes)}</p>
              <small>{new Intl.DateTimeFormat("zh-CN", { dateStyle: "long", timeStyle: "short" }).format(new Date(revision.createdAt))} · SHA-256 {revision.contentHash.slice(0, 12)}…</small>
              {revision.sourceRevisionId ? (
                <small>
                  重解析自既有 Revision · {revision.reparseRequestedParser ?? "AUTO"} · {revision.reparseReason}
                </small>
              ) : null}
              {revision.formatChangeFrom ? (
                <small>
                  格式变更 {revision.formatChangeFrom} → {revision.documentFormat}
                  {revision.formatChangeReason ? ` · ${revision.formatChangeReason}` : ""}
                </small>
              ) : null}
              {(user?.role === "ADMIN" || revision.effective) && DOWNLOADABLE_REVISION_STATUSES.has(revision.status) ? <button className="text-button" onClick={() => download(revision.id)}>下载此版本</button> : null}
            </li>
          ))}</ol>

          {user?.role === "ADMIN" ? (
            <form className="revision-upload" onSubmit={addRevision}>
              <label htmlFor="revision-file">上传新 Revision</label>
              <input
                id="revision-file"
                type="file"
                accept={fileAcceptValue(revisionFormats)}
                disabled={formatState !== "ready" || revisionFormats.length === 0}
                onChange={(event) => chooseRevisionFile(event.currentTarget)}
              />
              {formatState === "loading" ? <small>正在加载上传能力…</small> : null}
              {formatState === "error" ? <small className="form-error">上传能力加载失败，请刷新后重试</small> : null}
              {revisionChangesFormat ? (
                <div className="format-change-confirmation">
                  <p className="pending-note">
                    当前为 {currentDocumentFormat}，新文件为 {revisionUpload?.format}。格式变化会完整重建解析、索引和图谱投影。
                  </p>
                  <label htmlFor="format-change-reason">格式变更审计理由</label>
                  <textarea
                    id="format-change-reason"
                    value={formatChangeReason}
                    onChange={(event) => setFormatChangeReason(event.target.value)}
                    minLength={8}
                    maxLength={500}
                    required
                  />
                  <label className="parser-confirm">
                    <input
                      type="checkbox"
                      checked={formatChangeConfirmed}
                      onChange={(event) => setFormatChangeConfirmed(event.target.checked)}
                    />
                    <span>确认更换原始文档格式并创建新 Revision</span>
                  </label>
                </div>
              ) : null}
              <button
                className="primary-button"
                type="submit"
                disabled={!revisionUpload || working || (revisionChangesFormat
                  && (!formatChangeConfirmed || formatChangeReason.trim().length < 8))}
              >
                <UploadIcon />{working ? "上传中" : "上传新版本"}
              </button>
            </form>
          ) : null}

          {user?.role === "ADMIN" && detail.currentRevisionId ? (
            <form className="revision-upload" onSubmit={reparse}>
              <h4>重解析当前发布版本</h4>
              <p className="field-help">复制原始文档创建新 Revision；PARSE/INDEX 全部成功后才切换当前发布版本。</p>
              <label htmlFor="reparse-parser">目标解析器</label>
              <select
                id="reparse-parser"
                value={reparseParser}
                onChange={(event) => setReparseParser(event.target.value as ParserEngine)}
              >
                <option value="AUTO">自动路由</option>
                {revisionFormats
                  .filter((format) => format.parserOverrideAllowed)
                  .find((format) => format.format === (effectiveRevision?.documentFormat ?? "PDF"))
                  ?.parserProviders
                  .filter((provider) => provider.available)
                  .map((provider) => (
                    <option key={provider.provider} value={provider.provider}>{provider.provider}</option>
                  ))}
              </select>
              <label htmlFor="reparse-reason">审计理由</label>
              <textarea
                id="reparse-reason"
                value={reparseReason}
                onChange={(event) => setReparseReason(event.target.value)}
                minLength={8}
                maxLength={500}
                required
              />
              <label className="parser-confirm">
                <input
                  type="checkbox"
                  checked={reparseConfirmed}
                  onChange={(event) => setReparseConfirmed(event.target.checked)}
                />
                <span>确认创建新 Revision，不覆盖当前版本</span>
              </label>
              <button
                className="secondary-button"
                type="submit"
                disabled={working || !reparseConfirmed || reparseReason.trim().length < 8}
              >
                {working ? "创建中" : "开始重解析"}
              </button>
            </form>
          ) : null}
        </section>

        <section className="detail-card acl-card">
          <h3>访问权限</h3>
          {user?.role === "ADMIN" ? (
            <form onSubmit={saveAcl}>
              {aclEditingUnavailable ? <div className="form-error drawer-error" role="alert">授权用户列表加载失败，文档仍可查看，但暂不能修改权限。</div> : null}
              <label htmlFor="detail-title">标题</label>
              <input id="detail-title" value={title} onChange={(event) => setTitle(event.target.value)} required maxLength={500} disabled={aclEditingUnavailable} />
              <label htmlFor="detail-visibility">可见性</label>
              <select id="detail-visibility" value={visibility} onChange={(event) => setVisibility(event.target.value as typeof visibility)} disabled={aclEditingUnavailable}>
                <option value="RESTRICTED">受限用户</option><option value="ALL_USERS">所有已登录用户</option>
              </select>
              {visibility === "RESTRICTED" && !aclEditingUnavailable ? <fieldset className="user-picker"><legend>授权用户</legend>{availableUsers.map((entry) => (
                <label key={entry.id}><input type="checkbox" checked={grantedUserIds.includes(entry.id)} onChange={(event) => setGrantedUserIds((current) => event.target.checked ? [...current, entry.id] : current.filter((id) => id !== entry.id))} /><span>{entry.username}</span></label>
              ))}{unavailableGrantedUsers.map((entry) => (
                <p className="field-help" key={entry.id}>{entry.username} 已禁用或不再是普通用户，保存后将从授权列表移除。</p>
              ))}</fieldset> : visibility === "ALL_USERS" ? <p className="field-help">所有已登录用户均可访问。</p> : null}
              <label htmlFor="acl-reason">审计理由</label>
              <input id="acl-reason" value={aclReason} onChange={(event) => setAclReason(event.target.value)} minLength={8} maxLength={500} required disabled={aclEditingUnavailable} />
              <button className="primary-button" type="submit" disabled={working || !title.trim() || aclReason.trim().length < 8 || aclEditingUnavailable}>{working ? "保存中" : "保存权限"}</button>
            </form>
          ) : <p>{detail.document.visibility === "ALL_USERS" ? "所有已登录用户可访问" : "你的账户已被明确授权"}</p>}
        </section>
      </div>

      <RevisionProcessingPanel
        key={detail.document.latestRevisionNumber ?? 0}
        documentId={documentId}
        revisions={detail.revisions}
      />

      {user?.role === "ADMIN" ? <section className="danger-zone"><div><strong>删除文档</strong><p>逻辑删除立即生效，对象将在 60 秒内清理。</p></div><button className="danger-button" disabled={working} onClick={removeDocument}>删除文档</button></section> : null}
    </section>
  );
}
