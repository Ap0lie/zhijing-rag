import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { Link } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import type {
  MemoryEvent,
  MemoryItem,
  MemoryProfile,
  MemorySettings,
  MemorySource,
  MemorySourceInput,
  MemorySourceType,
  MemoryStatus,
  MemoryType,
} from "../types";

type LoadState = "loading" | "ready" | "error";

interface MemoryForm {
  memoryType: MemoryType;
  memoryKey: string;
  content: string;
  candidate: boolean;
  expiresAt: string;
}

const initialForm: MemoryForm = {
  memoryType: "USER_PREFERENCE",
  memoryKey: "",
  content: "",
  candidate: false,
  expiresAt: "",
};

const emptySource: MemorySourceInput = {
  sourceType: "CHAT_SESSION",
  chatSessionId: null,
  chatMessageId: null,
  documentId: null,
  revisionId: null,
  childChunkId: null,
  sourceSpanId: null,
};

const typeLabels: Record<MemoryType, string> = {
  USER_PREFERENCE: "用户偏好",
  USER_FACT: "用户事实",
  SESSION_SUMMARY: "会话摘要",
  DOCUMENT_FACT: "文档事实",
};

const statusLabels: Record<MemoryStatus, string> = {
  CANDIDATE: "待确认",
  ACTIVE: "生效中",
  REJECTED: "已拒绝",
  REVOKED: "已撤销",
  EXPIRED: "已过期",
  FORGOTTEN: "已忘记",
};

const sourceTypeLabels: Record<MemorySourceType, string> = {
  CHAT_SESSION: "会话",
  CHAT_MESSAGE: "消息",
  DOCUMENT_SPAN: "文档 SourceSpan",
};

function formatDate(value: string | null) {
  if (!value) return "永久";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function requestKey(prefix: string) {
  const suffix = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}:${suffix}`;
}

function expiryValue(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function sourceLabel(source: MemorySourceInput) {
  if (source.sourceType === "CHAT_SESSION") {
    return `会话 ${source.chatSessionId}`;
  }
  if (source.sourceType === "CHAT_MESSAGE") {
    return `消息 ${source.chatMessageId}`;
  }
  return `SourceSpan ${source.sourceSpanId}`;
}

function sourceTypesFor(memoryType: MemoryType): MemorySourceType[] {
  if (memoryType === "SESSION_SUMMARY") {
    return ["CHAT_SESSION", "CHAT_MESSAGE"];
  }
  if (memoryType === "DOCUMENT_FACT") {
    return ["DOCUMENT_SPAN"];
  }
  return ["CHAT_SESSION", "CHAT_MESSAGE", "DOCUMENT_SPAN"];
}

function emptySourceFor(memoryType: MemoryType): MemorySourceInput {
  return {
    ...emptySource,
    sourceType: sourceTypesFor(memoryType)[0],
  };
}

function validateSource(
  draft: MemorySourceInput,
  current: MemorySourceInput[],
) {
  const complete = draft.sourceType === "CHAT_SESSION"
    ? Boolean(draft.chatSessionId)
    : draft.sourceType === "CHAT_MESSAGE"
      ? Boolean(draft.chatSessionId && draft.chatMessageId)
      : Boolean(
        draft.documentId
        && draft.revisionId
        && draft.childChunkId
        && draft.sourceSpanId
      );
  if (!complete) {
    return "请完整填写来源 ID";
  }
  if (current.some((source) => sourceLabel(source) === sourceLabel(draft))) {
    return "该来源已经添加";
  }
  return null;
}

function SourceFields({
  draft,
  onChange,
  allowedTypes = ["CHAT_SESSION", "CHAT_MESSAGE", "DOCUMENT_SPAN"],
}: {
  draft: MemorySourceInput;
  onChange: (source: MemorySourceInput) => void;
  allowedTypes?: MemorySourceType[];
}) {
  function changeType(sourceType: MemorySourceType) {
    onChange({ ...emptySource, sourceType });
  }

  return (
    <div className="memory-source-fields">
      <label>
        来源类型
        <select
          value={draft.sourceType}
          onChange={(event) => changeType(event.target.value as MemorySourceType)}
          disabled={allowedTypes.length === 1}
        >
          {allowedTypes.map((sourceType) => (
            <option key={sourceType} value={sourceType}>
              {sourceTypeLabels[sourceType]}
            </option>
          ))}
        </select>
      </label>
      {draft.sourceType === "CHAT_SESSION" ? (
        <label>
          Session ID
          <input
            value={draft.chatSessionId ?? ""}
            onChange={(event) => onChange({ ...draft, chatSessionId: event.target.value || null })}
            placeholder="UUID"
          />
        </label>
      ) : null}
      {draft.sourceType === "CHAT_MESSAGE" ? (
        <>
          <label>
            Session ID
            <input
              value={draft.chatSessionId ?? ""}
              onChange={(event) => onChange({ ...draft, chatSessionId: event.target.value || null })}
              placeholder="UUID"
            />
          </label>
          <label>
            Message ID
            <input
              value={draft.chatMessageId ?? ""}
              onChange={(event) => onChange({ ...draft, chatMessageId: event.target.value || null })}
              placeholder="UUID"
            />
          </label>
        </>
      ) : null}
      {draft.sourceType === "DOCUMENT_SPAN" ? (
        <>
          <label>
            Document ID
            <input
              value={draft.documentId ?? ""}
              onChange={(event) => onChange({ ...draft, documentId: event.target.value || null })}
              placeholder="UUID"
            />
          </label>
          <label>
            Revision ID
            <input
              value={draft.revisionId ?? ""}
              onChange={(event) => onChange({ ...draft, revisionId: event.target.value || null })}
              placeholder="UUID"
            />
          </label>
          <label>
            Child Chunk ID
            <input
              value={draft.childChunkId ?? ""}
              onChange={(event) => onChange({ ...draft, childChunkId: event.target.value || null })}
              placeholder="UUID"
            />
          </label>
          <label>
            SourceSpan ID
            <input
              value={draft.sourceSpanId ?? ""}
              onChange={(event) => onChange({ ...draft, sourceSpanId: event.target.value || null })}
              placeholder="UUID"
            />
          </label>
        </>
      ) : null}
    </div>
  );
}

interface MemoryCreateDisclosureProps {
  error: string | null;
  open: boolean;
  working: boolean;
  onClose: () => void;
  onOpenChange: (open: boolean) => void;
  onCreate: (
    form: MemoryForm,
    sources: MemorySourceInput[],
  ) => Promise<boolean>;
}

function MemoryCreateDisclosure({
  error,
  open,
  working,
  onClose,
  onOpenChange,
  onCreate,
}: MemoryCreateDisclosureProps) {
  const detailsRef = useRef<HTMLDetailsElement>(null);
  const [form, setForm] = useState<MemoryForm>(initialForm);
  const [sources, setSources] = useState<MemorySourceInput[]>([]);
  const [sourceDraft, setSourceDraft] = useState<MemorySourceInput>(
    emptySourceFor(initialForm.memoryType),
  );
  const [showOptionalSource, setShowOptionalSource] = useState(false);
  const [sourceError, setSourceError] = useState<string | null>(null);
  const submittingRef = useRef(false);
  const allowedSourceTypes = sourceTypesFor(form.memoryType);
  const sourceRequired = form.memoryType === "SESSION_SUMMARY"
    || form.memoryType === "DOCUMENT_FACT";
  const sourceVisible = sourceRequired || showOptionalSource;

  useEffect(() => {
    const details = detailsRef.current;
    if (!details || !open) return;
    const drawer = details;

    const previousFocus = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    drawer.querySelector<HTMLElement>("input:not([disabled]), select:not([disabled])")
      ?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
        return;
      }
      if (event.key !== "Tab") return;

      const focusable = Array.from(drawer.querySelectorAll<HTMLElement>(
        "button:not([disabled]), input:not([disabled]), select:not([disabled]), "
        + "textarea:not([disabled]), summary, a[href], [tabindex]:not([tabindex='-1'])",
      )).filter((element) => !element.hasAttribute("hidden"));
      if (focusable.length === 0) {
        event.preventDefault();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    drawer.addEventListener("keydown", handleKeyDown);
    return () => {
      drawer.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [onClose, open]);

  function changeMemoryType(memoryType: MemoryType) {
    const nextSourceTypes = sourceTypesFor(memoryType);
    setForm((current) => ({ ...current, memoryType }));
    setSources((current) => current.filter((source) =>
      nextSourceTypes.includes(source.sourceType)));
    setSourceDraft(emptySourceFor(memoryType));
    setSourceError(null);
  }

  function addDraftSource() {
    const validationError = validateSource(sourceDraft, sources);
    if (validationError) {
      setSourceError(validationError);
      return;
    }
    setSourceError(null);
    setSources((current) => [...current, sourceDraft]);
    setSourceDraft({
      ...emptySource,
      sourceType: sourceDraft.sourceType,
    });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current) return;
    if (sourceRequired && sources.length === 0) {
      setSourceError(
        form.memoryType === "DOCUMENT_FACT"
          ? "文档事实至少需要一个完整的文档 SourceSpan 来源"
          : "会话摘要至少需要一个会话或消息来源",
      );
      return;
    }
    submittingRef.current = true;
    try {
      if (await onCreate(form, sources)) {
        setForm(initialForm);
        setSources([]);
        setSourceDraft(emptySourceFor(initialForm.memoryType));
        setShowOptionalSource(false);
        setSourceError(null);
        onClose();
      }
    } finally {
      submittingRef.current = false;
    }
  }

  if (!open) return null;

  return (
    <section
      className="memory-card memory-create is-open"
      aria-labelledby="memory-create-title"
      aria-modal="true"
      role="dialog"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <details
        ref={detailsRef}
        open={open}
        onToggle={(event) => onOpenChange(event.currentTarget.open)}
      >
        <summary className="secondary-button">
          新增记忆
        </summary>

        <form onSubmit={handleSubmit} aria-labelledby="memory-create-title">
          <header>
            <div>
              <span>新增记忆</span>
              <h3 id="memory-create-title">保存一条长期记忆</h3>
              <p>仅保存你希望系统在后续问答中使用的偏好或事实。</p>
            </div>
          </header>
          <div className="memory-form-grid">
            <label htmlFor="memory-type">类型</label>
            <select
              id="memory-type"
              value={form.memoryType}
              onChange={(event) => changeMemoryType(event.target.value as MemoryType)}
            >
              {Object.entries(typeLabels).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>

            <label htmlFor="memory-key">记忆名称</label>
            <input
              id="memory-key"
              value={form.memoryKey}
              maxLength={160}
              onChange={(event) => setForm((current) => ({
                ...current,
                memoryKey: event.target.value,
              }))}
              placeholder="例如：回答语言"
              required
            />

            <label htmlFor="memory-content">简短事实</label>
            <textarea
              id="memory-content"
              value={form.content}
              maxLength={1200}
              onChange={(event) => setForm((current) => ({
                ...current,
                content: event.target.value,
              }))}
              placeholder="不保存密码、Cookie、Token、API Key、私钥或完整文档正文"
              aria-describedby="memory-content-help"
              required
            />
            <p id="memory-content-help" className="field-help">
              {form.content.length}/1200 · 只保存完成任务所需的最少信息
            </p>

            <label htmlFor="memory-expiry">过期时间（可选）</label>
            <input
              id="memory-expiry"
              type="datetime-local"
              value={form.expiresAt}
              onChange={(event) => setForm((current) => ({
                ...current,
                expiresAt: event.target.value,
              }))}
            />

            <label className="toggle-row">
              <input
                type="checkbox"
                checked={form.candidate}
                onChange={(event) => setForm((current) => ({
                  ...current,
                  candidate: event.target.checked,
                }))}
              />
              <span>
                <strong>先保存为候选</strong>
                <small>确认前不会参与回答，适合稍后复核的内容。</small>
              </span>
            </label>

            {!sourceRequired ? (
              <button
                className="secondary-button"
                type="button"
                aria-expanded={showOptionalSource}
                aria-controls="memory-source-fields"
                onClick={() => setShowOptionalSource((current) => !current)}
              >
                {showOptionalSource ? "收起来源信息" : "添加来源信息（可选）"}
              </button>
            ) : (
              <p className="field-help">
                {form.memoryType === "DOCUMENT_FACT"
                  ? "文档事实必须绑定当前 Revision 的 Child 与 SourceSpan。"
                  : "会话摘要必须绑定你自己的会话或已完成消息。"}
              </p>
            )}

            {sourceVisible ? (
              <div id="memory-source-fields" className="memory-source-builder">
                <SourceFields
                  draft={sourceDraft}
                  onChange={setSourceDraft}
                  allowedTypes={allowedSourceTypes}
                />
                <button
                  className="secondary-button"
                  type="button"
                  onClick={addDraftSource}
                >
                  添加来源
                </button>
                {sourceError ? (
                  <p className="field-error" role="alert">{sourceError}</p>
                ) : null}
                {sources.length ? (
                  <ul className="memory-source-chips">
                    {sources.map((source, index) => (
                      <li key={sourceLabel(source)}>
                        <span>{sourceLabel(source)}</span>
                        <button
                          type="button"
                          onClick={() => setSources((current) =>
                            current.filter((_, candidate) => candidate !== index))}
                        >
                          移除
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            ) : null}

            {error ? <div className="form-error" role="alert">{error}</div> : null}
          </div>

          <footer className="row-actions">
            <button className="secondary-button" type="button" onClick={onClose}>
              取消
            </button>
            <button className="primary-button" type="submit" disabled={working}>
              {working ? "保存中" : form.candidate ? "保存候选" : "保存并生效"}
            </button>
          </footer>
        </form>
      </details>
    </section>
  );
}

export function MemoryPage() {
  const { expireSession } = useAuth();
  const [state, setState] = useState<LoadState>("loading");
  const [settings, setSettings] = useState<MemorySettings | null>(null);
  const [items, setItems] = useState<MemoryItem[]>([]);
  const [profile, setProfile] = useState<MemoryProfile | null>(null);
  const [typeFilter, setTypeFilter] = useState<MemoryType | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<MemoryStatus | "ALL">("ALL");
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedSources, setSelectedSources] = useState<MemorySource[]>([]);
  const [events, setEvents] = useState<MemoryEvent[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailMemoryId, setDetailMemoryId] = useState<string | null>(null);
  const [replaceMode, setReplaceMode] = useState(false);
  const [replaceKey, setReplaceKey] = useState("");
  const [replaceContent, setReplaceContent] = useState("");
  const [replaceExpiry, setReplaceExpiry] = useState("");
  const [replaceSources, setReplaceSources] = useState<MemorySourceInput[]>([]);
  const [replaceDraft, setReplaceDraft] = useState<MemorySourceInput>(emptySource);
  const detailRequestRef = useRef(0);
  const [reason, setReason] = useState("");
  const [forgetConfirmation, setForgetConfirmation] = useState("");
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const closeCreateDrawer = useCallback(() => setCreateOpen(false), []);

  const selected = useMemo(
    () => items.find((item) => item.id === selectedId) ?? null,
    [items, selectedId],
  );
  const filtered = useMemo(
    () => items.filter((item) =>
      (typeFilter === "ALL" || item.memoryType === typeFilter)
      && (statusFilter === "ALL" || item.status === statusFilter)),
    [items, statusFilter, typeFilter],
  );

  const handleError = useCallback((caught: unknown, fallback: string) => {
    if (caught instanceof ApiError && caught.status === 401) {
      expireSession();
      return;
    }
    setError(caught instanceof ApiError ? caught.message : fallback);
  }, [expireSession]);

  const load = useCallback(async () => {
    setState("loading");
    setError(null);
    try {
      const [settingsResult, itemResult, profileResult] = await Promise.all([
        apiRequest<MemorySettings>("/api/v1/memories/settings"),
        apiRequest<MemoryItem[]>("/api/v1/memories"),
        apiRequest<MemoryProfile>("/api/v1/memories/profile"),
      ]);
      setSettings(settingsResult);
      setItems(itemResult);
      setProfile(profileResult);
      setState("ready");
    } catch (caught) {
      setState("error");
      handleError(caught, "长期记忆加载失败");
    }
  }, [handleError]);

  const loadDetail = useCallback(async (memoryId: string) => {
    const requestId = ++detailRequestRef.current;
    setDetailLoading(true);
    setDetailMemoryId(null);
    setSelectedSources([]);
    setEvents([]);
    setError(null);
    try {
      const [sourceResult, eventResult] = await Promise.all([
        apiRequest<MemorySource[]>(`/api/v1/memories/${memoryId}/sources`),
        apiRequest<MemoryEvent[]>(`/api/v1/memories/${memoryId}/events`),
      ]);
      if (requestId === detailRequestRef.current) {
        setSelectedSources(sourceResult);
        setEvents(eventResult);
        setDetailMemoryId(memoryId);
      }
    } catch (caught) {
      if (requestId === detailRequestRef.current) {
        handleError(caught, "记忆来源与事件加载失败");
      }
    } finally {
      if (requestId === detailRequestRef.current) {
        setDetailLoading(false);
      }
    }
  }, [handleError]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (selectedId) {
      void loadDetail(selectedId);
    } else {
      detailRequestRef.current += 1;
      setSelectedSources([]);
      setEvents([]);
      setDetailMemoryId(null);
    }
  }, [loadDetail, selectedId]);

  function addSource(
    draft: MemorySourceInput,
    current: MemorySourceInput[],
    update: (next: MemorySourceInput[]) => void,
    reset: (next: MemorySourceInput) => void,
  ) {
    const validationError = validateSource(draft, current);
    if (validationError) {
      setError(validationError);
      return;
    }
    setError(null);
    update([...current, draft]);
    reset({ ...emptySource, sourceType: draft.sourceType });
  }

  async function saveSettings() {
    if (!settings) return;
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await apiRequest<MemorySettings>("/api/v1/memories/settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          enabled: settings.enabled,
          suggestionEnabled: settings.suggestionEnabled,
          expectedVersion: settings.version,
        }),
      });
      setSettings(updated);
      setProfile((current) => current
        ? { ...current, memoryEnabled: updated.enabled }
        : current);
      setNotice("记忆设置已保存。");
    } catch (caught) {
      handleError(caught, "保存记忆设置失败");
    } finally {
      setWorking(false);
    }
  }

  async function createMemory(
    form: MemoryForm,
    sources: MemorySourceInput[],
  ) {
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      const created = await apiRequest<MemoryItem>("/api/v1/memories", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": requestKey("memory-create"),
        },
        body: JSON.stringify({
          ...form,
          expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString() : null,
          sources,
        }),
      });
      setNotice(created.status === "CANDIDATE"
        ? "候选记忆已保存，确认前不会生效。"
        : "记忆已保存。");
      setSelectedId(created.id);
      await load();
      return true;
    } catch (caught) {
      handleError(caught, "创建记忆失败");
      return false;
    } finally {
      setWorking(false);
    }
  }

  async function runAction(action: "confirm" | "reject" | "revoke") {
    if (!selected) return;
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await apiRequest<MemoryItem>(
        `/api/v1/memories/${selected.id}/${action}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ reason: reason.trim() || null }),
        },
      );
      setReason("");
      setNotice(`${updated.memoryKey}：${statusLabels[updated.status]}。`);
      await load();
      await loadDetail(updated.id);
    } catch (caught) {
      handleError(caught, "记忆状态更新失败");
    } finally {
      setWorking(false);
    }
  }

  function beginReplace() {
    if (!selected?.content
      || detailLoading
      || detailMemoryId !== selected.id) return;
    setReplaceMode(true);
    setReplaceKey(selected.memoryKey);
    setReplaceContent(selected.content);
    setReplaceExpiry(expiryValue(selected.expiresAt));
    setReplaceSources(selectedSources
      .filter((source) => source.sourceDeletedAt === null)
      .map((source) => ({
        sourceType: source.sourceType,
        chatSessionId: source.chatSessionId,
        chatMessageId: source.chatMessageId,
        documentId: source.documentId,
        revisionId: source.revisionId,
        childChunkId: source.childChunkId,
        sourceSpanId: source.sourceSpanId,
      })));
  }

  async function replaceMemory(event: FormEvent) {
    event.preventDefault();
    if (!selected) return;
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      const replacement = await apiRequest<MemoryItem>(
        `/api/v1/memories/${selected.id}/replace`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": requestKey("memory-replace"),
          },
          body: JSON.stringify({
            memoryKey: replaceKey,
            content: replaceContent,
            expiresAt: replaceExpiry
              ? new Date(replaceExpiry).toISOString()
              : null,
            sources: replaceSources,
            reason: reason.trim() || null,
          }),
        },
      );
      setReplaceMode(false);
      setReason("");
      setNotice("已创建新版本，旧版本已撤销。");
      setSelectedId(replacement.id);
      await load();
    } catch (caught) {
      handleError(caught, "替换记忆失败");
    } finally {
      setWorking(false);
    }
  }

  async function forgetMemory() {
    if (!selected || forgetConfirmation !== "FORGET_MEMORY") return;
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      await apiRequest<MemoryItem>(
        `/api/v1/memories/${selected.id}/forget`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            confirmation: "FORGET_MEMORY",
            reason: reason.trim() || null,
          }),
        },
      );
      setForgetConfirmation("");
      setReason("");
      setNotice("记忆正文与来源已清除，只保留无正文 Tombstone。");
      await load();
      await loadDetail(selected.id);
    } catch (caught) {
      handleError(caught, "忘记操作失败");
    } finally {
      setWorking(false);
    }
  }

  if (state === "loading") {
    return (
      <section className="memory-page" aria-label="长期记忆">
        <div className="screen-state">
          <span className="spinner" />
          <p>加载长期记忆</p>
        </div>
      </section>
    );
  }

  return (
    <section className="memory-page" aria-labelledby="memory-page-title">
      <header className="memory-hero">
        <div>
          <span>个人记忆</span>
          <h2 id="memory-page-title">可控长期记忆</h2>
          <p>默认关闭。启用后，已确认的个人记忆会作为受限 Memory Pack 参与有证据回答，但不会替代 Citation。</p>
        </div>
        <div className="row-actions">
          <button
            className="primary-button"
            type="button"
            disabled={working}
            onClick={() => {
              setError(null);
              setCreateOpen(true);
            }}
          >
            新增记忆
          </button>
          <button className="secondary-button" type="button" onClick={() => void load()}>
            刷新
          </button>
        </div>
      </header>

      {error ? <div className="chat-alert failed" role="alert">{error}</div> : null}
      {notice ? <div className="chat-alert completed" role="status">{notice}</div> : null}
      {state === "error" ? (
        <button className="primary-button" type="button" onClick={() => void load()}>
          重新加载
        </button>
      ) : null}

      {settings ? (
        <section className="memory-card memory-settings">
          <header>
            <div>
              <span>设置</span>
              <h3>记忆控制</h3>
            </div>
            <strong>{settings.enabled ? "已启用" : "默认关闭"}</strong>
          </header>
          <div className="memory-toggle-grid">
            <label>
              <input
                type="checkbox"
                checked={settings.enabled}
                onChange={(event) => setSettings({
                  ...settings,
                  enabled: event.target.checked,
                })}
              />
              <span><strong>长期记忆总开关</strong><small>关闭后问答不会召回 Memory Pack，已保存事实仍可管理。</small></span>
            </label>
            <label>
              <input
                type="checkbox"
                checked={settings.suggestionEnabled}
                onChange={(event) => setSettings({
                  ...settings,
                  suggestionEnabled: event.target.checked,
                })}
              />
              <span><strong>允许生成记忆建议</strong><small>回答完成后异步分析你自己的消息；远程模型还需服务器许可，候选仍需你确认。</small></span>
            </label>
          </div>
          <button className="primary-button" type="button" onClick={() => void saveSettings()} disabled={working}>
            保存设置
          </button>
        </section>
      ) : null}

      <section className="memory-workspace">
        <div className="memory-card memory-list">
          <header>
            <div><span>记忆</span><h3>事实版本</h3></div>
            <strong>{filtered.length}/{items.length}</strong>
          </header>
          <div className="memory-filters">
            <select
              aria-label="按类型筛选"
              value={typeFilter}
              onChange={(event) => setTypeFilter(event.target.value as MemoryType | "ALL")}
            >
              <option value="ALL">全部类型</option>
              {Object.entries(typeLabels).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <select
              aria-label="按状态筛选"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value as MemoryStatus | "ALL")}
            >
              <option value="ALL">全部状态</option>
              {Object.entries(statusLabels).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </div>
          {filtered.length === 0 ? (
            <p className="memory-empty">没有符合条件的记忆。</p>
          ) : (
            <div className="memory-items">
              {filtered.map((item) => (
                <button
                  key={item.id}
                  className={selectedId === item.id ? "selected" : ""}
                  type="button"
                  onClick={() => {
                    setSelectedId(item.id);
                    setReplaceMode(false);
                    setForgetConfirmation("");
                  }}
                >
                  <span>{typeLabels[item.memoryType]} · v{item.versionNumber}</span>
                  <strong>{item.memoryKey}</strong>
                  <p>{item.content ?? "正文已清除"}</p>
                  <footer>
                    <em className={`memory-status ${item.status.toLowerCase()}`}>
                      {statusLabels[item.status]}
                    </em>
                    <small>{item.sourceCount} 个来源</small>
                  </footer>
                </button>
              ))}
            </div>
          )}
        </div>

        <aside className="memory-card memory-inspector" aria-live="polite">
          {!selected ? (
            <div className="memory-empty"><h3>选择一条记忆</h3><p>查看生命周期、来源和事件。</p></div>
          ) : (
            <>
              <header>
                <div><span>详情</span><h3>{selected.memoryKey}</h3></div>
                <em className={`memory-status ${selected.status.toLowerCase()}`}>
                  {statusLabels[selected.status]}
                </em>
              </header>
              <dl className="memory-facts">
                <div><dt>类型 / 版本</dt><dd>{typeLabels[selected.memoryType]} / v{selected.versionNumber}</dd></div>
                <div><dt>来源</dt><dd>{selected.origin} / {selected.sourceCount} 条</dd></div>
                <div><dt>过期</dt><dd>{formatDate(selected.expiresAt)}</dd></div>
                <div><dt>更新</dt><dd>{formatDate(selected.updatedAt)}</dd></div>
              </dl>
              <div className="memory-body">
                {selected.content ?? "正文已永久清除。"}
              </div>
              {selected.supersedesMemoryId ? (
                <p className="memory-lineage">替换自 {selected.supersedesMemoryId}</p>
              ) : null}

              {detailLoading ? <p className="memory-empty">加载来源与事件…</p> : (
                <>
                  <section className="memory-detail-section">
                    <h4>来源</h4>
                    {selectedSources.length === 0 ? <p>没有来源。</p> : (
                      <ul>
                        {selectedSources.map((source) => (
                          <li key={source.id}>
                            <strong>{source.sourceType}</strong>
                            {source.sourceType === "DOCUMENT_SPAN" && source.childChunkId ? (
                              <Link to={`/chunks/${source.childChunkId}`}>
                                {sourceLabel(source)}
                              </Link>
                            ) : <span>{sourceLabel(source)}</span>}
                            {source.sourceDeletedAt ? (
                              <small>原会话或消息已删除，仅保留来源 Tombstone</small>
                            ) : null}
                          </li>
                        ))}
                      </ul>
                    )}
                  </section>
                  <section className="memory-detail-section">
                    <h4>事件</h4>
                    {events.length === 0 ? <p>没有事件。</p> : (
                      <ol>
                        {events.map((event) => (
                          <li key={event.id}>
                            <strong>{event.eventType}</strong>
                            <span>{formatDate(event.createdAt)}</span>
                            {event.reason ? <p>{event.reason}</p> : null}
                          </li>
                        ))}
                      </ol>
                    )}
                  </section>
                </>
              )}

              {selected.status !== "FORGOTTEN" ? (
                <div className="memory-actions">
                  <label>
                    操作理由（可选）
                    <input value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} />
                  </label>
                  <div>
                    {selected.status === "CANDIDATE" ? (
                      <>
                        <button className="primary-button" type="button" disabled={working} onClick={() => void runAction("confirm")}>确认</button>
                        <button className="secondary-button" type="button" disabled={working} onClick={() => void runAction("reject")}>拒绝</button>
                      </>
                    ) : null}
                    {["ACTIVE", "CANDIDATE"].includes(selected.status) ? (
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={working || detailLoading || detailMemoryId !== selected.id}
                        onClick={beginReplace}
                      >
                        创建替换版本
                      </button>
                    ) : null}
                    {selected.status === "ACTIVE" ? (
                      <button className="secondary-button" type="button" disabled={working} onClick={() => void runAction("revoke")}>撤销</button>
                    ) : null}
                  </div>
                  <div className="memory-forget">
                    <label>
                      输入 FORGET_MEMORY 永久清除正文与来源
                      <input value={forgetConfirmation} onChange={(event) => setForgetConfirmation(event.target.value)} />
                    </label>
                    <button
                      className="danger-button"
                      type="button"
                      disabled={working || forgetConfirmation !== "FORGET_MEMORY"}
                      onClick={() => void forgetMemory()}
                    >
                      忘记
                    </button>
                  </div>
                </div>
              ) : null}

              {replaceMode ? (
                <form className="memory-replace" onSubmit={replaceMemory}>
                  <header><h4>新版本</h4><button type="button" onClick={() => setReplaceMode(false)}>取消</button></header>
                  <label>记忆名称<input value={replaceKey} maxLength={160} onChange={(event) => setReplaceKey(event.target.value)} required /></label>
                  <label>简短事实<textarea value={replaceContent} maxLength={1200} onChange={(event) => setReplaceContent(event.target.value)} required /></label>
                  <label>过期时间<input type="datetime-local" value={replaceExpiry} onChange={(event) => setReplaceExpiry(event.target.value)} /></label>
                  <SourceFields draft={replaceDraft} onChange={setReplaceDraft} />
                  <button
                    className="secondary-button"
                    type="button"
                    onClick={() => addSource(
                      replaceDraft,
                      replaceSources,
                      setReplaceSources,
                      setReplaceDraft,
                    )}
                  >
                    添加来源
                  </button>
                  {replaceSources.length ? (
                    <ul className="memory-source-chips">
                      {replaceSources.map((source, index) => (
                        <li key={sourceLabel(source)}>
                          <span>{sourceLabel(source)}</span>
                          <button type="button" onClick={() => setReplaceSources(
                            replaceSources.filter((_, candidate) => candidate !== index),
                          )}>移除</button>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  <button className="primary-button" type="submit" disabled={working}>保存新版本</button>
                </form>
              ) : null}
            </>
          )}
        </aside>
      </section>

      <section className="memory-card">
        <header>
          <div><span>用户偏好</span><h3>当前偏好</h3></div>
          <strong>{profile?.preferences.length ?? 0}</strong>
        </header>
        {!profile?.preferences.length ? (
          <p className="memory-empty">没有 ACTIVE 用户偏好。这里不是第二个可编辑的画像事实源。</p>
        ) : (
          <dl className="memory-profile-list">
            {profile.preferences.map((entry) => (
              <div key={entry.memoryId}>
                <dt>{entry.key}</dt>
                <dd>{entry.value}<small>v{entry.versionNumber}</small></dd>
              </div>
            ))}
          </dl>
        )}
      </section>

      <MemoryCreateDisclosure
        error={error}
        open={createOpen}
        working={working}
        onClose={closeCreateDrawer}
        onOpenChange={setCreateOpen}
        onCreate={createMemory}
      />
    </section>
  );
}
