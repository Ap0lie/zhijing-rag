import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";

import { ApiError, apiRequest } from "../api";
import type {
  EvaluationDrill,
  EvaluationDrillExecutionMode,
  EvaluationDrillEvent,
  EvaluationDrillType,
  EvaluationGate,
  EvaluationObservability,
} from "../types";

type OperationsView = "observability" | "drills";
const OPERATIONS_CACHE_TTL_MS = 30_000;

export type EvaluationOperationsCache = {
  loadedAt: Partial<Record<OperationsView, number>>;
  observability: EvaluationObservability | null;
  gates: EvaluationGate[];
  drills: EvaluationDrill[];
  selectedDrillId: string | null;
  eventsByDrill: Map<string, { loadedAt: number; events: EvaluationDrillEvent[] }>;
};

export function createEvaluationOperationsCache(): EvaluationOperationsCache {
  return {
    loadedAt: {},
    observability: null,
    gates: [],
    drills: [],
    selectedDrillId: null,
    eventsByDrill: new Map(),
  };
}

const drillTypeLabel: Record<EvaluationDrillType, string> = {
  MODEL_TIMEOUT: "模型超时",
  OPENSEARCH_UNAVAILABLE: "OpenSearch 不可用",
  GRAPH_STALE: "图谱投影待更新",
  CANARY_LEAK_SCAN: "Canary 泄漏扫描",
};

const metricLabel: Record<string, string> = {
  chatPending: "问答排队",
  chatRunning: "问答运行",
  evaluationPending: "评测排队",
  evaluationRunning: "评测运行",
  drillPending: "演练排队",
  drillRunning: "演练运行",
  pipelinePending: "Pipeline 排队",
  pipelineRunning: "Pipeline 运行",
  chatFailure: "问答失败率",
  chatDegradation: "问答降级率",
  evaluationFailure: "评测失败率",
  pipelineFailure: "Pipeline 失败率",
  chat: "问答",
  evaluationCase: "评测用例",
  pipeline: "Pipeline",
  artifactCount: "Artifact",
  bytes: "存储字节",
  usedWithin24h: "24h 使用",
  activeGeneration: "当前生效代次",
  activeProjectedDocuments: "已投影文档",
  staleDocuments: "待更新文档",
};

const statusLabel: Record<string, string> = {
  PENDING: "排队中",
  RUNNING: "运行中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  PASSED: "通过",
  BLOCKED: "阻断",
};

function formatDate(value: string | null) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "—";
}

function statusClass(status: string) {
  return status.toLowerCase().replaceAll("_", "-");
}

function idempotencyKey() {
  const value = typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `drill:${value}`;
}

function MetricGrid({
  title,
  values,
  formatter = (value) => String(value),
}: {
  title: string;
  values: Record<string, number>;
  formatter?: (value: number) => string;
}) {
  const entries = Object.entries(values);
  return (
    <section className="evaluation-card evaluation-telemetry-card">
      <header><h3>{title}</h3><span>{entries.length}</span></header>
      {entries.length === 0 ? <p className="empty-copy">当前未采集。</p> : (
        <dl>{entries.map(([key, value]) => (
          <div key={key}>
            <dt>{metricLabel[key] ?? key}</dt>
            <dd>{formatter(value)}</dd>
          </div>
        ))}</dl>
      )}
    </section>
  );
}

export function EvaluationOperationsPanel({
  view,
  cache,
}: {
  view: OperationsView;
  cache?: EvaluationOperationsCache;
}) {
  const localCache = useRef<EvaluationOperationsCache | null>(null);
  if (localCache.current === null) localCache.current = createEvaluationOperationsCache();
  const pageCache = cache ?? localCache.current;
  const [observability, setObservability] =
    useState<EvaluationObservability | null>(pageCache.observability);
  const [gates, setGates] = useState<EvaluationGate[]>(pageCache.gates);
  const [drills, setDrills] = useState<EvaluationDrill[]>(pageCache.drills);
  const [events, setEvents] = useState<EvaluationDrillEvent[]>(() =>
    pageCache.selectedDrillId
      ? pageCache.eventsByDrill.get(pageCache.selectedDrillId)?.events ?? []
      : []);
  const [selectedDrillId, setSelectedDrillId] =
    useState<string | null>(pageCache.selectedDrillId);
  const [drillType, setDrillType] =
    useState<EvaluationDrillType>("CANARY_LEAK_SCAN");
  const [executionMode, setExecutionMode] =
    useState<EvaluationDrillExecutionMode>("SIMULATION_ONLY");
  const [reason, setReason] = useState("验证队列、状态机与降级契约");
  const [loading, setLoading] = useState(() => {
    const loadedAt = pageCache.loadedAt[view] ?? 0;
    return Date.now() - loadedAt >= OPERATIONS_CACHE_TTL_MS;
  });
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const eventsRequest = useRef<AbortController | null>(null);

  const selectedDrill = useMemo(
    () => drills.find((item) => item.id === selectedDrillId) ?? null,
    [drills, selectedDrillId],
  );

  const loadObservability = useCallback(async () => {
    const [snapshot, gateList] = await Promise.all([
      apiRequest<EvaluationObservability>(
        "/api/v1/admin/evaluations/observability",
      ),
      apiRequest<EvaluationGate[]>("/api/v1/admin/evaluations/gates"),
    ]);
    setObservability(snapshot);
    setGates(gateList);
    pageCache.observability = snapshot;
    pageCache.gates = gateList;
    pageCache.loadedAt.observability = Date.now();
  }, [pageCache]);

  const loadDrills = useCallback(async (force = false) => {
    const loadedAt = pageCache.loadedAt.drills ?? 0;
    if (!force && Date.now() - loadedAt < OPERATIONS_CACHE_TTL_MS) {
      setDrills(pageCache.drills);
      setSelectedDrillId(pageCache.selectedDrillId);
      return;
    }
    const items = await apiRequest<EvaluationDrill[]>(
      "/api/v1/admin/evaluations/drills",
    );
    setDrills(items);
    pageCache.drills = items;
    pageCache.loadedAt.drills = Date.now();
    setSelectedDrillId((current) => {
      const next = current ?? items[0]?.id ?? null;
      pageCache.selectedDrillId = next;
      return next;
    });
  }, [pageCache]);

  const loadEvents = useCallback(async (drillId: string, force = false) => {
    const cached = pageCache.eventsByDrill.get(drillId);
    if (!force && cached && Date.now() - cached.loadedAt < OPERATIONS_CACHE_TTL_MS) {
      setEvents(cached.events);
      return;
    }
    eventsRequest.current?.abort();
    const controller = new AbortController();
    eventsRequest.current = controller;
    try {
      const next = await apiRequest<EvaluationDrillEvent[]>(
        `/api/v1/admin/evaluations/drills/${drillId}/events`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted) {
        setEvents(next);
        pageCache.eventsByDrill.set(drillId, { loadedAt: Date.now(), events: next });
      }
    } finally {
      if (eventsRequest.current === controller) {
        eventsRequest.current = null;
      }
    }
  }, [pageCache]);

  const refresh = useCallback(async (quiet = false, force = false) => {
    if (!quiet) setLoading(true);
    try {
      if (view === "observability") {
        const loadedAt = pageCache.loadedAt.observability ?? 0;
        if (force || Date.now() - loadedAt >= OPERATIONS_CACHE_TTL_MS) {
          await loadObservability();
        } else {
          setObservability(pageCache.observability);
          setGates(pageCache.gates);
        }
      } else {
        await loadDrills(force);
      }
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError
        ? caught.message
        : "评测运维数据加载失败");
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [loadDrills, loadObservability, pageCache, view]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (view === "drills" && selectedDrillId) {
      setEvents([]);
      void loadEvents(selectedDrillId).catch((caught) => {
        if (caught instanceof DOMException && caught.name === "AbortError") return;
        setError(caught instanceof ApiError
          ? caught.message
          : "Drill 事件加载失败");
      });
    }
    return () => eventsRequest.current?.abort();
  }, [loadEvents, selectedDrillId, view]);

  useEffect(() => {
    if (view !== "drills") return;
    const active = drills.some((item) =>
      item.status === "PENDING" || item.status === "RUNNING");
    if (!active) return;
    const timer = window.setInterval(() => {
      void loadDrills(true).catch(() => setError("Drill 刷新失败"));
      if (selectedDrillId) {
        void loadEvents(selectedDrillId, true).catch((caught) => {
          if (caught instanceof DOMException && caught.name === "AbortError") return;
          setError("Drill 事件刷新失败");
        });
      }
    }, 1000);
    return () => window.clearInterval(timer);
  }, [drills, loadDrills, loadEvents, selectedDrillId, view]);

  async function createDrill(event: FormEvent) {
    event.preventDefault();
    setWorking(true);
    try {
      const created = await apiRequest<EvaluationDrill>(
        "/api/v1/admin/evaluations/drills",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            drillType,
            executionMode,
            idempotencyKey: idempotencyKey(),
            confirmation: "RUN_EVALUATION_DRILL",
            reason: reason.trim(),
          }),
        },
      );
      setSelectedDrillId(created.id);
      pageCache.selectedDrillId = created.id;
      await Promise.all([loadDrills(true), loadEvents(created.id, true)]);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError
        ? caught.message
        : "故障验证创建失败");
    } finally {
      setWorking(false);
    }
  }

  async function act(
    drill: EvaluationDrill,
    action: "cancel" | "retry",
  ) {
    setWorking(true);
    try {
      const updated = await apiRequest<EvaluationDrill>(
        `/api/v1/admin/evaluations/drills/${drill.id}/${action}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            confirmation: action === "cancel"
              ? "CANCEL_EVALUATION_DRILL"
              : "RETRY_EVALUATION_DRILL",
            reason: `${action === "cancel" ? "取消" : "重试"}：${reason.trim()}`,
          }),
        },
      );
      setSelectedDrillId(updated.id);
      pageCache.selectedDrillId = updated.id;
      await Promise.all([loadDrills(true), loadEvents(updated.id, true)]);
      setError(null);
    } catch (caught) {
      setError(caught instanceof ApiError
        ? caught.message
        : "契约模拟操作失败");
    } finally {
      setWorking(false);
    }
  }

  if (loading) {
    return <div className="inline-state"><span className="spinner" />正在加载评测运维状态</div>;
  }

  if (view === "observability") {
    return (
      <section className="evaluation-section">
        <button className="secondary-button" type="button" onClick={() => void refresh(false, true)} disabled={loading}>
          刷新观测
        </button>
        {error ? <div className="form-error evaluation-alert" role="alert">{error}</div> : null}
        {!observability ? <div className="evaluation-card empty-copy">观测不可用。</div> : (
          <>
            <section className="evaluation-card evaluation-permit">
              <header>
                <div><span>WORKLOAD PERMIT</span><h3>在线 Chat 优先</h3></div>
                <span className={`evaluation-status ${observability.workloadPermit.evaluationMayClaim ? "succeeded" : "blocked-prerequisite"}`}>
                  {observability.workloadPermit.evaluationMayClaim ? "Evaluation 可领取" : "已让路"}
                </span>
              </header>
              <dl>
                <div><dt>活动 Chat</dt><dd>{observability.workloadPermit.activeChatRuns}</dd></div>
                <div><dt>采集内容</dt><dd>{String(observability.captureContent)}</dd></div>
                <div><dt>高基数标签</dt><dd>{String(observability.highCardinalityLabels)}</dd></div>
                <div><dt>Retention</dt><dd>{observability.retentionDays} 天</dd></div>
              </dl>
              <small>最近 {observability.windowHours} 小时 · {formatDate(observability.capturedAt)} · {observability.workloadPermit.pauseReason ?? "无暂停原因"}</small>
            </section>
            <div className="evaluation-telemetry-grid">
              <MetricGrid title="队列与租约" values={observability.queues} />
              <MetricGrid title="错误与降级率" values={observability.rates} formatter={(value) => `${(value * 100).toFixed(1)}%`} />
              <MetricGrid title="p50 延迟" values={observability.latencyP50Ms} formatter={(value) => `${value.toFixed(1)} ms`} />
              <MetricGrid title="p95 延迟" values={observability.latencyP95Ms} formatter={(value) => `${value.toFixed(1)} ms`} />
              <MetricGrid title="Embedding 缓存" values={observability.embeddingCache} />
              <MetricGrid title="图谱投影" values={observability.graph} />
            </div>
            <section className="evaluation-card">
              <header><h3>基线发布门禁</h3><span>{gates.length}</span></header>
              {gates.length === 0 ? <p className="empty-copy">尚无终态 Baseline Gate。</p> : (
                <div className="evaluation-subject-list">{gates.map((gate) => (
                  <article key={gate.baselineId}>
                    <div><strong>{gate.baselineName}</strong><span>{gate.baselineKey}</span></div>
                    <span className={`evaluation-status ${statusClass(gate.gateStatus)}`}>{statusLabel[gate.gateStatus] ?? gate.gateStatus}</span>
                    <code>{gate.runId.slice(0, 8)}</code>
                    <small>{gate.blockers.length ? gate.blockers.join(", ") : "确定性硬门禁通过"} · {gate.published ? "ACTIVE" : "未发布"}</small>
                  </article>
                ))}</div>
              )}
            </section>
          </>
        )}
      </section>
    );
  }

  return (
    <section className="evaluation-section">
      <button className="secondary-button" type="button" onClick={() => void refresh(false, true)} disabled={loading || working}>
        刷新验证
      </button>
      {error ? <div className="form-error evaluation-alert" role="alert">{error}</div> : null}
      <form className="evaluation-card evaluation-drill-form" onSubmit={createDrill}>
        <header><div><span>{executionMode}</span><h3>受控故障验证</h3></div><span>请求级注入，不改变在线 ACTIVE</span></header>
        <p>Contract Smoke 只验证状态机；Real Verify 使用白名单请求级故障点验证真实降级和泄漏门禁。</p>
        <label>执行模式<select value={executionMode} onChange={(event) => setExecutionMode(event.target.value as EvaluationDrillExecutionMode)}>
          <option value="SIMULATION_ONLY">Contract Smoke</option>
          <option value="REAL_VERIFY">Real Verify（需后端开关）</option>
        </select></label>
        <label>故障点<select value={drillType} onChange={(event) => setDrillType(event.target.value as EvaluationDrillType)}>
          {(Object.keys(drillTypeLabel) as EvaluationDrillType[]).map((type) => (
            <option value={type} key={type}>{drillTypeLabel[type]}</option>
          ))}
        </select></label>
        <label>审计理由<input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required /></label>
        <button className="primary-button" disabled={working || !reason.trim()}>RUN_EVALUATION_DRILL</button>
      </form>

      <div className="evaluation-report-grid">
        <section className="evaluation-card">
          <header><h3>演练运行</h3><span>{drills.length}</span></header>
          {drills.length === 0 ? <p className="empty-copy">尚未运行白名单契约模拟。</p> : (
            <div className="evaluation-drill-list">{drills.map((drill) => (
              <article className={selectedDrillId === drill.id ? "selected" : ""} key={drill.id}>
                <button type="button" onClick={() => {
                  pageCache.selectedDrillId = drill.id;
                  setSelectedDrillId(drill.id);
                }}>
                  <strong>{drillTypeLabel[drill.drillType]}</strong>
                  <code>{drill.id.slice(0, 8)}</code>
                </button>
                <span className={`evaluation-status ${statusClass(drill.status)}`}>{statusLabel[drill.status]}</span>
                <small>{drill.executionMode} · 尝试 {drill.attempt}/{drill.maxAttempts} · {formatDate(drill.updatedAt)}</small>
                <div className="evaluation-actions">
                  {drill.status === "PENDING" || drill.status === "RUNNING" ? (
                    <button className="text-button danger-text" type="button" disabled={working} onClick={() => void act(drill, "cancel")}>取消</button>
                  ) : (
                    <button className="text-button" type="button" disabled={working} onClick={() => void act(drill, "retry")}>重试</button>
                  )}
                </div>
              </article>
            ))}</div>
          )}
        </section>

        <section className="evaluation-card">
          <header><h3>演练详情</h3><span>{selectedDrill ? statusLabel[selectedDrill.status] : "未选择"}</span></header>
          {!selectedDrill ? <p className="empty-copy">选择一条演练查看安全结果和事件。</p> : (
            <>
              <dl className="evaluation-drill-summary">
                {Object.entries(selectedDrill.resultSummary).map(([key, value]) => (
                  <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>
                ))}
              </dl>
              {selectedDrill.errorCode ? <p className="form-error">{selectedDrill.errorCode} · {selectedDrill.errorMessage}</p> : null}
              <ol className="evaluation-event-list">{events.map((event) => (
                <li key={event.id}>
                  <span>{event.sequence}</span>
                  <div><strong>{event.eventType}</strong><small>{event.fromStatus ?? "—"} → {event.toStatus} · {formatDate(event.createdAt)}</small></div>
                </li>
              ))}</ol>
            </>
          )}
        </section>
      </div>
    </section>
  );
}
