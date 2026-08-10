import { useCallback, useEffect, useRef, useState, type DragEvent, type FormEvent } from "react";
import { Link } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { CloseIcon, DocumentIcon, SearchIcon, UploadIcon } from "../components/Icons";
import { DocumentFormatBadge } from "../components/SourceLocation";
import { DocumentFormatPolicyPanel } from "../components/DocumentFormatPolicyPanel";
import {
  enabledDocumentFormats,
  fileAcceptValue,
  loadDocumentFormats,
  titleFromFilename,
  validateDocumentFile,
} from "../documentFiles";
import type {
  DocumentDetail,
  DocumentFormatCapability,
  DocumentPage,
  DocumentSummary,
  EvaluationProvenance,
  ManagedUser,
} from "../types";

const EMPTY_PAGE: DocumentPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

interface UploadDrawerProps {
  users: ManagedUser[];
  formats: DocumentFormatCapability[];
  onClose: () => void;
  onUploaded: (document: DocumentDetail) => void;
}

function ProvenanceSummary({
  provenance,
  visibility,
}: {
  provenance: EvaluationProvenance;
  visibility: DocumentSummary["visibility"];
}) {
  return (
    <div className="evaluation-provenance">
      <span className={visibility === "ALL_USERS" ? "eval-public-badge" : "eval-restricted-badge"}>
        {visibility === "ALL_USERS" ? "[EVAL][PUBLIC]" : "[EVAL][RESTRICTED]"}
      </span>
      <a href={provenance.sourceUrl} target="_blank" rel="noreferrer">
        {provenance.sourceTitle}
      </a>
      <small>{provenance.sourceDataset} · {provenance.sourceLicense}</small>
    </div>
  );
}

function documentProvenance(document: DocumentSummary) {
  return document.effectiveEvaluationProvenance
    ?? document.latestEvaluationProvenance
    ?? null;
}

function UploadDrawer({ users, formats, onClose, onUploaded }: UploadDrawerProps) {
  const { expireSession } = useAuth();
  const dialogRef = useRef<HTMLElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [title, setTitle] = useState("");
  const [visibility, setVisibility] = useState<"ALL_USERS" | "RESTRICTED">("RESTRICTED");
  const [grantedUserIds, setGrantedUserIds] = useState<string[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    const focusableSelector = [
      "button:not([disabled])",
      "input:not([disabled])",
      "select:not([disabled])",
      "textarea:not([disabled])",
      "[tabindex]:not([tabindex='-1'])",
    ].join(", ");
    const focusableElements = () =>
      Array.from(dialog.querySelectorAll<HTMLElement>(focusableSelector));
    document.body.style.overflow = "hidden";
    dialog.querySelector<HTMLElement>("input:not([disabled])")?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key !== "Tab") return;
      const elements = focusableElements();
      if (elements.length === 0) {
        event.preventDefault();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    dialog.addEventListener("keydown", handleKeyDown);
    return () => {
      dialog.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [onClose]);

  function chooseFile(next: File | null) {
    setError(null);
    if (!next) {
      setFile(null);
      return;
    }
    const validationError = validateDocumentFile(next, formats);
    if (validationError) {
      setError(validationError);
      setFile(null);
      return;
    }
    setFile(next);
    if (!title.trim()) {
      setTitle(titleFromFilename(next.name, formats));
    }
  }

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragging(false);
    chooseFile(event.dataTransfer.files[0] ?? null);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) {
      setError("请先选择文档文件");
      return;
    }
    setSubmitting(true);
    setError(null);
    const body = new FormData();
    body.append("title", title.trim());
    body.append("visibility", visibility);
    body.append("file", file);
    if (visibility === "RESTRICTED") {
      grantedUserIds.forEach((id) => body.append("grantedUserIds", id));
    }
    try {
      const created = await apiRequest<DocumentDetail>("/api/v1/admin/documents", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body,
      });
      onUploaded(created);
      onClose();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "上传失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  const availableUsers = users.filter((user) => user.enabled && user.role === "USER");
  const formatNames = formats.map((format) => format.displayName).join("、");
  const maximumMiB = Math.max(...formats.map((format) => format.maxFileSizeBytes)) / 1024 / 1024;

  return (
    <div className="drawer-layer" role="presentation">
      <aside ref={dialogRef} className="drawer document-drawer" role="dialog" aria-modal="true" aria-labelledby="upload-title">
        <header className="drawer-header">
          <h2 id="upload-title">{`上传 ${formatNames}`}</h2>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭"><CloseIcon /></button>
        </header>
        <form className="drawer-form" onSubmit={handleSubmit}>
          <div className="drawer-body">
            <label htmlFor="document-title">标题 <span aria-hidden="true">*</span></label>
            <input id="document-title" value={title} onChange={(event) => setTitle(event.target.value)} maxLength={500} required />

            <label>文档文件 <span aria-hidden="true">*</span></label>
            <div
              className={`file-drop ${dragging ? "dragging" : ""}`}
              onDragOver={(event) => { event.preventDefault(); setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={handleDrop}
            >
              <UploadIcon />
              <strong>{file ? file.name : `拖放 ${formatNames} 到这里`}</strong>
              <span>{file ? `${(file.size / 1024 / 1024).toFixed(2)} MiB` : `最大 ${maximumMiB.toFixed(0)} MiB`}</span>
              <button className="secondary-button" type="button" onClick={() => fileInputRef.current?.click()}>选择文件</button>
              <input
                ref={fileInputRef}
                className="sr-only"
                type="file"
                accept={fileAcceptValue(formats)}
                onChange={(event) => chooseFile(event.target.files?.[0] ?? null)}
                aria-label="选择文档文件"
              />
            </div>

            <label htmlFor="document-visibility">可见性</label>
            <select id="document-visibility" value={visibility} onChange={(event) => setVisibility(event.target.value as typeof visibility)}>
              <option value="RESTRICTED">受限用户</option>
              <option value="ALL_USERS">所有已登录用户</option>
            </select>

            {visibility === "RESTRICTED" ? (
              <fieldset className="user-picker">
                <legend>授权用户</legend>
                {availableUsers.length === 0 ? <p>暂无可授权的普通用户</p> : availableUsers.map((user) => (
                  <label key={user.id}>
                    <input
                      type="checkbox"
                      checked={grantedUserIds.includes(user.id)}
                      onChange={(event) => setGrantedUserIds((current) => event.target.checked
                        ? [...current, user.id]
                        : current.filter((id) => id !== user.id))}
                    />
                    <span>{user.username}</span>
                  </label>
                ))}
              </fieldset>
            ) : null}

            {error ? <div className="form-error drawer-error" role="alert">{error}</div> : null}
          </div>
          <footer className="drawer-footer">
            <button className="secondary-button" type="button" onClick={onClose}>取消</button>
            <button className="primary-button" type="submit" disabled={submitting || !title.trim() || !file}>
              {submitting ? "上传中" : "上传"}
            </button>
          </footer>
        </form>
      </aside>
    </div>
  );
}

export function HomePage() {
  const { user, expireSession } = useAuth();
  const [documents, setDocuments] = useState<DocumentPage>(EMPTY_PAGE);
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  const [visibility, setVisibility] = useState("");
  const [page, setPage] = useState(0);
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [uploadOpen, setUploadOpen] = useState(false);
  const [formatPoliciesOpen, setFormatPoliciesOpen] = useState(false);
  const [users, setUsers] = useState<ManagedUser[]>([]);
  const [uploadFormats, setUploadFormats] = useState<DocumentFormatCapability[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const listRequestRef = useRef<AbortController | null>(null);

  const loadDocuments = useCallback(() => {
    listRequestRef.current?.abort();
    const controller = new AbortController();
    listRequestRef.current = controller;
    setState("loading");
    const params = new URLSearchParams({ query, page: String(page), size: "20" });
    if (visibility) params.set("visibility", visibility);
    apiRequest<DocumentPage>(`/api/v1/documents?${params}`, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        setDocuments(result);
        setState("ready");
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) {
          expireSession();
          return;
        }
        setState("error");
      })
      .finally(() => {
        if (listRequestRef.current === controller) listRequestRef.current = null;
      });
  }, [expireSession, page, query, visibility]);

  useEffect(() => {
    loadDocuments();
    return () => listRequestRef.current?.abort();
  }, [loadDocuments]);

  const openUpload = useCallback(async () => {
    if (user?.role !== "ADMIN") return;
    try {
      const [formatResponse, availableUsers] = await Promise.all([
        loadDocumentFormats(),
        users.length > 0
          ? Promise.resolve(users)
          : apiRequest<ManagedUser[]>("/api/v1/admin/users"),
      ]);
      const formats = enabledDocumentFormats(formatResponse);
      if (formats.length === 0) {
        setMessage("当前没有可上传的文档格式");
        return;
      }
      setUsers(availableUsers);
      setUploadFormats(formats);
      setUploadOpen(true);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) expireSession();
      else setMessage("无法加载上传能力，请稍后重试");
    }
  }, [expireSession, user?.role, users]);

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setPage(0);
    setQuery(search.trim());
  }

  return (
    <section className="documents-page">
      <div className="document-toolbar">
        <form className="document-filters" onSubmit={submitSearch}>
          <label className="search-field">
            <SearchIcon />
            <span className="sr-only">搜索文档标题</span>
            <input type="search" placeholder="搜索文档标题" value={search} onChange={(event) => setSearch(event.target.value)} />
          </label>
          <select aria-label="筛选可见性" value={visibility} onChange={(event) => { setVisibility(event.target.value); setPage(0); }}>
            <option value="">全部可见性</option>
            <option value="ALL_USERS">所有用户</option>
            <option value="RESTRICTED">受限</option>
          </select>
          <button className="secondary-button" type="submit">筛选</button>
        </form>
        {user?.role === "ADMIN" ? (
          <div className="document-toolbar-actions">
            <button className="secondary-button" type="button" onClick={() => setFormatPoliciesOpen((open) => !open)}>
              {formatPoliciesOpen ? "收起运行策略" : "格式运行策略"}
            </button>
            <button className="primary-button upload-button" type="button" onClick={openUpload}><UploadIcon />上传文档</button>
          </div>
        ) : null}
      </div>

      {message ? <div className="success-message" role="status">{message}</div> : null}

      {user?.role === "ADMIN" && formatPoliciesOpen ? (
        <DocumentFormatPolicyPanel
          onSessionExpired={expireSession}
          onMessage={setMessage}
        />
      ) : null}

      <div className="table-wrap documents-table-wrap">
        <table className="documents-table">
          <thead><tr><th>文档</th><th>可见性</th><th>版本状态</th><th>所有者</th><th>更新时间</th></tr></thead>
          <tbody>{documents.items.map((document) => (
            <tr key={document.id}>
              <td>
                <Link to={`/documents/${document.id}`}><strong>{document.title}</strong></Link>
                <DocumentFormatBadge format={document.documentFormat} />
                {documentProvenance(document) ? (
                  <ProvenanceSummary
                    provenance={documentProvenance(document)!}
                    visibility={document.visibility}
                  />
                ) : null}
              </td>
              <td>{document.visibility === "ALL_USERS" ? "所有用户" : "受限"}</td>
              <td><span className="revision-badge">{document.latestRevisionNumber ? `R${document.latestRevisionNumber} · ${document.latestRevisionStatus}` : "待上传"}</span></td>
              <td>{document.ownerUsername}</td>
              <td>{new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(document.updatedAt))}</td>
            </tr>
          ))}</tbody>
        </table>

        {state === "loading" ? <div className="table-state"><span className="spinner" /><p>正在加载文档</p></div> : null}
        {state === "error" ? <div className="table-state error-state"><p>文档列表加载失败</p><button className="secondary-button" onClick={() => loadDocuments()}>重试</button></div> : null}
        {state === "ready" && documents.items.length === 0 ? (
          <div className="table-state empty-state"><DocumentIcon /><strong>暂无可访问文档</strong><p>{user?.role === "ADMIN" ? "上传第一份文档开始建立知识库" : "请联系管理员授权文档"}</p></div>
        ) : null}
      </div>

      {documents.totalPages > 1 ? (
        <nav className="pagination" aria-label="文档分页">
          <button className="secondary-button" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>上一页</button>
          <span>第 {documents.page + 1} / {documents.totalPages} 页，共 {documents.totalElements} 项</span>
          <button className="secondary-button" disabled={page + 1 >= documents.totalPages} onClick={() => setPage((current) => current + 1)}>下一页</button>
        </nav>
      ) : null}

      {uploadOpen ? (
        <UploadDrawer
          users={users}
          formats={uploadFormats}
          onClose={() => setUploadOpen(false)}
          onUploaded={(created) => {
            setMessage(`已上传 ${created.document.title}`);
            loadDocuments();
          }}
        />
      ) : null}
    </section>
  );
}
