import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import type {
  QueryIntelligenceProfile,
  QueryIntelligencePublicationEvent,
  QueryIntelligenceRuntime,
} from "../types";

type LoadState = "loading" | "ready" | "error";

interface ProfileForm {
  version: string;
  enabled: boolean;
  modelContextTokens: number;
  historyMessageLimit: number;
  historyTokenBudget: number;
  historyContextPercent: number;
  maxSubQueries: number;
  maxRetrievalRounds: number;
  plannerCallLimit: number;
  timeoutMs: number;
  reason: string;
}

const initialForm: ProfileForm = {
  version: "phase12c-route-rewrite-v1",
  enabled: true,
  modelContextTokens: 32768,
  historyMessageLimit: 12,
  historyTokenBudget: 2048,
  historyContextPercent: 20,
  maxSubQueries: 3,
  maxRetrievalRounds: 2,
  plannerCallLimit: 2,
  timeoutMs: 3000,
  reason: "启用 Phase 12C 请求级 AUTO Router、多轮与 3×2 共享召回",
};

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

export function QueryIntelligencePage() {
  const { expireSession } = useAuth();
  const [state, setState] = useState<LoadState>("loading");
  const [runtime, setRuntime] = useState<QueryIntelligenceRuntime | null>(null);
  const [profiles, setProfiles] = useState<QueryIntelligenceProfile[]>([]);
  const [events, setEvents] = useState<QueryIntelligencePublicationEvent[]>([]);
  const [form, setForm] = useState<ProfileForm>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [publicationReason, setPublicationReason] = useState("启用请求级 AUTO Router 与多轮共享召回");
  const [publicationConfirmation, setPublicationConfirmation] = useState("");
  const [intentRunId, setIntentRunId] = useState("");
  const [multiTurnRunId, setMultiTurnRunId] = useState("");

  const activeProfile = useMemo(
    () => profiles.find((profile) => profile.published) ?? null,
    [profiles],
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
      const [runtimeResult, profileResult, eventResult] = await Promise.all([
        apiRequest<QueryIntelligenceRuntime>("/api/v1/admin/query-intelligence/runtime"),
        apiRequest<QueryIntelligenceProfile[]>("/api/v1/admin/query-intelligence/profiles"),
        apiRequest<QueryIntelligencePublicationEvent[]>("/api/v1/admin/query-intelligence/events"),
      ]);
      setRuntime(runtimeResult);
      setProfiles(profileResult);
      setEvents(eventResult);
      setState("ready");
    } catch (caught) {
      setState("error");
      handleError(caught, "Query Intelligence 配置加载失败");
    }
  }, [handleError]);

  useEffect(() => {
    void load();
  }, [load]);

  async function createProfile(event: FormEvent) {
    event.preventDefault();
    if (!runtime) return;
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      await apiRequest<QueryIntelligenceProfile>("/api/v1/admin/query-intelligence/profiles", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...form,
          plannerProvider: runtime.plannerProvider,
          plannerModel: runtime.plannerModel,
          plannerRevision: runtime.plannerRevision,
          promptVersion: runtime.promptVersion,
          schemaVersion: runtime.schemaVersion,
          tokenCounterType: runtime.supportedCounterType,
          tokenCounterVersion: runtime.supportedCounterVersion,
          fallbackMode: "ORIGINAL_QUERY",
        }),
      });
      setNotice(`Profile ${form.version} 已创建，尚未发布。`);
      await load();
    } catch (caught) {
      handleError(caught, "创建 Profile 失败");
    } finally {
      setSubmitting(false);
    }
  }

  async function changePublication(
    profileVersion: string,
    action: "publish" | "rollback",
  ) {
    const reason = publicationReason.trim();
    const expectedConfirmation = action === "publish"
      ? "PUBLISH_QUERY_PROFILE"
      : "ROLLBACK_QUERY_PROFILE";
    if (!reason) {
      setError("请填写发布审计理由");
      return;
    }
    if (publicationConfirmation.trim() !== expectedConfirmation) {
      setError(`请输入确认字段 ${expectedConfirmation}`);
      return;
    }
    if (action === "publish"
        && (!intentRunId.trim() || !multiTurnRunId.trim())) {
      setError("发布必须提供已通过硬门禁的 INTENT 和 MULTI_TURN Run ID");
      return;
    }
    setSubmitting(true);
    setError(null);
    setNotice(null);
    try {
      await apiRequest(
        `/api/v1/admin/query-intelligence/${action === "publish" ? "publications" : "rollbacks"}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            profileVersion,
            confirmation: expectedConfirmation,
            reason,
            ...(action === "publish"
              ? {
                  intentRunId: intentRunId.trim(),
                  multiTurnRunId: multiTurnRunId.trim(),
                }
              : {}),
          }),
        },
      );
      setNotice(`${profileVersion} 已${action === "publish" ? "发布" : "回滚"}。`);
      setPublicationConfirmation("");
      setIntentRunId("");
      setMultiTurnRunId("");
      await load();
    } catch (caught) {
      handleError(caught, "发布操作失败");
    } finally {
      setSubmitting(false);
    }
  }

  if (state === "loading") {
    return <section className="query-intelligence-page" aria-label="查询智能"><div className="screen-state"><span className="spinner" /><p>加载 Query Profile</p></div></section>;
  }

  return (
    <section className="query-intelligence-page">
      <header className="query-intelligence-hero">
        <div>
          <h2>安全历史与查询智能</h2>
          <p>配置只影响新问答；每个请求只路由一次，每轮最多 3 条查询、最多 2 轮，共不超过 6 次召回。</p>
        </div>
        <button className="secondary-button" type="button" onClick={() => void load()}>刷新</button>
      </header>

      {error ? <div className="chat-alert failed" role="alert">{error}</div> : null}
      {notice ? <div className="chat-alert completed" role="status">{notice}</div> : null}
      {state === "error" ? <button className="primary-button" type="button" onClick={() => void load()}>重新加载</button> : null}

      {runtime ? (
        <section className="query-intelligence-card">
          <header>
            <div><span>运行状态</span><h3>当前运行时</h3></div>
            <strong className={runtime.llmEnabled ? "status-dot online" : "status-dot offline"}>
              {runtime.llmEnabled ? "LLM 可用" : "LLM 未启用"}
            </strong>
          </header>
          <dl className="query-intelligence-facts">
            <div><dt>Planner</dt><dd>{runtime.plannerModel}</dd></div>
            <div><dt>Revision</dt><dd>{runtime.plannerRevision}</dd></div>
            <div><dt>Prompt / Schema</dt><dd>{runtime.promptVersion} / {runtime.schemaVersion}</dd></div>
            <div><dt>Counter</dt><dd>{runtime.supportedCounterVersion}</dd></div>
          </dl>
        </section>
      ) : null}

      <section className="query-intelligence-card">
        <header>
          <div><span>生效状态</span><h3>当前发布</h3></div>
          <strong>{activeProfile?.version ?? "未发布 · 历史关闭"}</strong>
        </header>
        {activeProfile ? (
          <p>
            最多 {activeProfile.historyMessageLimit} 条 / {activeProfile.historyTokenBudget} Token /
            上下文 {activeProfile.historyContextPercent}%；每轮 {activeProfile.maxSubQueries} 条，
            最多 {activeProfile.maxRetrievalRounds} 轮 / {activeProfile.plannerCallLimit} 次 Planner，
            共享超时 {activeProfile.timeoutMs} ms
          </p>
        ) : <p>未发布兼容 Profile 时，Chat 保持 HYBRID 安全路径，不启用历史或 AUTO Router。</p>}
        <div className="query-intelligence-form-grid">
          <label>
            发布审计理由
            <input
              value={publicationReason}
              onChange={(event) => setPublicationReason(event.target.value)}
            />
          </label>
          <label>
            确认字段
            <input
              value={publicationConfirmation}
              placeholder="PUBLISH_QUERY_PROFILE 或 ROLLBACK_QUERY_PROFILE"
              onChange={(event) => setPublicationConfirmation(event.target.value)}
            />
          </label>
          <label>
            INTENT Evaluation Run ID
            <input
              value={intentRunId}
              placeholder="发布时必填"
              onChange={(event) => setIntentRunId(event.target.value)}
            />
          </label>
          <label>
            MULTI_TURN Evaluation Run ID
            <input
              value={multiTurnRunId}
              placeholder="发布时必填"
              onChange={(event) => setMultiTurnRunId(event.target.value)}
            />
          </label>
        </div>
      </section>

      {runtime ? (
        <form className="query-intelligence-card query-intelligence-form" onSubmit={createProfile}>
          <header><div><span>新建</span><h3>创建不可变配置</h3></div></header>
          <div className="query-intelligence-form-grid">
            <label>版本<input value={form.version} onChange={(event) => setForm({ ...form, version: event.target.value })} required /></label>
            <label>模型上下文 Token<input type="number" min={1024} value={form.modelContextTokens} onChange={(event) => setForm({ ...form, modelContextTokens: Number(event.target.value) })} /></label>
            <label>历史消息上限<input type="number" min={1} max={12} value={form.historyMessageLimit} onChange={(event) => setForm({ ...form, historyMessageLimit: Number(event.target.value) })} /></label>
            <label>历史 Token 预算<input type="number" min={64} max={2048} value={form.historyTokenBudget} onChange={(event) => setForm({ ...form, historyTokenBudget: Number(event.target.value) })} /></label>
            <label>上下文占比 %<input type="number" min={1} max={20} value={form.historyContextPercent} onChange={(event) => setForm({ ...form, historyContextPercent: Number(event.target.value) })} /></label>
            <label>每轮查询上限<input type="number" min={1} max={3} value={form.maxSubQueries} onChange={(event) => setForm({ ...form, maxSubQueries: Number(event.target.value) })} /></label>
            <label>检索轮数上限<input type="number" min={1} max={2} value={form.maxRetrievalRounds} onChange={(event) => setForm({ ...form, maxRetrievalRounds: Number(event.target.value) })} /></label>
            <label>Planner 调用上限<input type="number" min={0} max={2} value={form.plannerCallLimit} onChange={(event) => setForm({ ...form, plannerCallLimit: Number(event.target.value) })} /></label>
            <label>硬超时 ms<input type="number" min={100} max={30000} value={form.timeoutMs} onChange={(event) => setForm({ ...form, timeoutMs: Number(event.target.value) })} /></label>
            <label>审计理由<input value={form.reason} onChange={(event) => setForm({ ...form, reason: event.target.value })} required /></label>
          </div>
          <label className="query-intelligence-toggle">
            <input type="checkbox" checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
            启用历史窗口、Rewrite 与请求级 Router
          </label>
          <button className="primary-button" type="submit" disabled={submitting}>{submitting ? "保存中" : "创建 Profile"}</button>
        </form>
      ) : null}

      <section className="query-intelligence-card">
        <header><div><span>版本</span><h3>不可变配置</h3></div><strong>{profiles.length}</strong></header>
        {profiles.length === 0 ? <p>尚无 Profile。</p> : (
          <div className="query-intelligence-list">
            {profiles.map((profile) => (
              <article key={profile.version}>
                <div>
                  <strong>{profile.version}</strong>
                  <span>{profile.published ? "当前生效" : profile.enabled ? "可发布" : "已停用"}</span>
                </div>
                <p>{profile.plannerModel} · {profile.historyMessageLimit} 条 · {profile.historyTokenBudget} Token · {formatDate(profile.createdAt)}</p>
                <button
                  className="secondary-button"
                  type="button"
                  disabled={submitting || profile.published}
                  onClick={() => void changePublication(
                    profile.version,
                    events.some((event) => event.profileVersion === profile.version)
                      ? "rollback"
                      : "publish",
                  )}
                >
                  {events.some((event) => event.profileVersion === profile.version)
                    ? "回滚到此版本"
                    : "发布此版本"}
                </button>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="query-intelligence-card">
        <header><div><span>AUDIT</span><h3>发布事件</h3></div><strong>{events.length}</strong></header>
        {events.length === 0 ? <p>尚无发布事件。</p> : (
          <ol className="query-intelligence-events">
            {events.map((event) => (
              <li key={event.eventId}>
                <strong>{event.action} · {event.profileVersion}</strong>
                <span>{event.reason} · {formatDate(event.createdAt)}</span>
                {event.intentRunId && event.multiTurnRunId ? (
                  <span>INTENT {event.intentRunId} · MULTI_TURN {event.multiTurnRunId}</span>
                ) : null}
              </li>
            ))}
          </ol>
        )}
      </section>
    </section>
  );
}
