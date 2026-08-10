import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { Link, useSearchParams } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import { SourceLocation } from "../components/SourceLocation";
import { ProjectionClosureSummary } from "../components/ProjectionClosureSummary";
import { GraphTopologyPanel } from "../components/GraphTopologyPanel";
import { GraphEntityDetailPanel } from "../components/GraphEntityDetailPanel";
import type {
  GraphCommunityDetail,
  GraphCommunityPage,
  GraphConfig,
  GraphEntityDetail,
  GraphEntityPage,
  GraphEntitySummary,
  GraphGeneration,
  GraphOverview,
  GraphRebuildRequest,
  GraphResolutionPreview,
  GraphResolutionCandidateDetail,
  GraphResolutionCandidatePage,
  GraphResolutionCandidateSummary,
  GraphResolutionProposalDetail,
  GraphResolutionProposalPage,
  GraphResolutionProposalSummary,
  GraphRetrievalConfiguration,
  GraphRetrievalProfile,
  GraphRetrievalPublication,
  GraphRootType,
  GlobalCommunityReportDetail,
  GlobalCommunityReportPage,
  GlobalGraphConfig,
  GlobalGraphGeneration,
  GlobalGraphOverview,
} from "../types";

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "medium",
  timeStyle: "medium",
});

const ACTIVE_REBUILD_STATES = new Set<GraphRebuildRequest["state"]>([
  "REQUESTED",
  "GRAPH_BUILDING",
  "GRAPH_READY",
  "GLOBAL_BUILDING",
]);

function formatTime(value: string | null) {
  return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "—";
}

function formatGeneration(value: number | null) {
  return value === null ? "—" : `G${value}`;
}

function graphStageLabel(request: GraphRebuildRequest) {
  if (request.graphReadyAt) return `READY · ${formatTime(request.graphReadyAt)}`;
  if (request.state === "REQUESTED") return "等待构建";
  if (request.state === "GRAPH_BUILDING") return "构建中";
  if (request.state === "SUPERSEDED") return "已被取代";
  return "未记录 READY 时间";
}

function globalStageLabel(request: GraphRebuildRequest) {
  if (!request.globalRebuildRequired) return "Graph 完成后直接收敛";
  if (request.globalReadyAt) return `READY · ${formatTime(request.globalReadyAt)}`;
  if (request.state === "REQUESTED" || request.state === "GRAPH_BUILDING") {
    return "等待 Graph";
  }
  if (request.state === "GRAPH_READY") return "等待构建";
  if (request.state === "GLOBAL_BUILDING") return "构建中";
  if (request.state === "SUPERSEDED") return "已被取代";
  return "未记录 READY 时间";
}

function statusClass(status: string) {
  return `generation-status ${status.toLowerCase()}`;
}

function newIdempotencyKey() {
  return typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `graph-rule-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function candidateTypeLabel(value: GraphResolutionCandidateSummary["candidateType"]) {
  return value === "SUSPECTED_DUPLICATE" ? "疑似重复" : "疑似误合并";
}

function candidateStatusLabel(value: GraphResolutionCandidateSummary["status"]) {
  if (value === "IGNORED") return "已忽略";
  if (value === "STALE") return "事实已过期";
  return "待核对";
}

function candidateSignalLabel(code: string) {
  const labels: Record<string, string> = {
    SAME_NORMALIZED_NAME: "规范化名称相同",
    ALIAS_OVERLAP: "Alias 重叠",
    STRING_SIMILARITY: "名称相似",
    TYPE_COMPATIBLE: "实体类型兼容",
    MENTION_COOCCURRENCE: "原文共同出现",
    NEIGHBOR_OVERLAP: "邻接关系重叠",
    SOURCE_CLUSTER_SEPARATION: "来源簇分离",
    NEIGHBOR_SOURCE_SEPARATION: "邻接来源分离",
    TYPE_HINT_CONFLICT: "类型提示冲突",
    AMBIGUOUS_ALIAS: "Alias 指向不明确",
  };
  return labels[code] ?? code;
}

function proposalStatusLabel(status: GraphResolutionProposalSummary["status"]) {
  const labels: Record<GraphResolutionProposalSummary["status"], string> = {
    DRAFT: "草案",
    READY: "待物化",
    CONFLICTED: "存在冲突",
    STALE: "事实已过期",
    WITHDRAWN: "已撤回",
    MATERIALIZED: "已物化",
    APPLIED: "已被构建吸收",
  };
  return labels[status];
}

function GlobalGraphPanel() {
  const { expireSession } = useAuth();
  const [overview, setOverview] = useState<GlobalGraphOverview | null>(null);
  const [selectedGeneration, setSelectedGeneration] = useState<number | null>(null);
  const [reports, setReports] = useState<GlobalCommunityReportPage | null>(null);
  const [reportDetail, setReportDetail] =
    useState<GlobalCommunityReportDetail | null>(null);
  const [reportPage, setReportPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [browsing, setBrowsing] = useState(false);
  const [action, setAction] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [configDraft, setConfigDraft] = useState({
    version: "",
    reason: "",
    confirmation: "",
  });
  const [buildDraft, setBuildDraft] = useState({
    globalConfigVersion: "",
    reason: "",
    confirmation: "",
  });
  const [releaseReason, setReleaseReason] = useState("");
  const [releaseConfirmation, setReleaseConfirmation] = useState("");

  const handleError = useCallback((caught: unknown, fallback: string) => {
    if (caught instanceof ApiError && caught.status === 401) {
      expireSession();
      return;
    }
    setMessage(caught instanceof ApiError ? caught.message : fallback);
  }, [expireSession]);

  const load = useCallback((quiet = false) => {
    const controller = new AbortController();
    if (!quiet) setLoading(true);
    apiRequest<GlobalGraphOverview>("/api/v1/admin/graph/global", {
      signal: controller.signal,
    })
      .then((result) => {
        if (controller.signal.aborted) return;
        setOverview(result);
        setBuildDraft((current) => ({
          ...current,
          globalConfigVersion:
            current.globalConfigVersion || result.configs.at(-1)?.version || "",
        }));
        setSelectedGeneration((current) => {
          if (
            current !== null
            && result.generations.some((item) => item.globalGeneration === current)
          ) {
            return current;
          }
          return result.activeGeneration
            ?? result.generations.find((item) =>
              ["READY", "ACTIVE", "RETIRED"].includes(item.status))?.globalGeneration
            ?? null;
        });
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) handleError(caught, "Global Graph 状态加载失败");
      })
      .finally(() => {
        if (!controller.signal.aborted && !quiet) setLoading(false);
      });
    return controller;
  }, [handleError]);

  useEffect(() => {
    const controller = load();
    return () => controller.abort();
  }, [load]);

  const building = overview?.generations.some(
    (generation) => generation.status === "BUILDING",
  ) ?? false;

  useEffect(() => {
    if (!building) return;
    const timer = window.setInterval(() => load(true), 2000);
    return () => window.clearInterval(timer);
  }, [building, load]);

  useEffect(() => {
    if (selectedGeneration === null) {
      setReports(null);
      setReportDetail(null);
      return;
    }
    const controller = new AbortController();
    setBrowsing(true);
    setReportDetail(null);
    apiRequest<GlobalCommunityReportPage>(
      `/api/v1/admin/graph/global/reports?generation=${selectedGeneration}&page=${reportPage}&size=20`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (!controller.signal.aborted) setReports(result);
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) handleError(caught, "Global Report 加载失败");
      })
      .finally(() => {
        if (!controller.signal.aborted) setBrowsing(false);
      });
    return () => controller.abort();
  }, [handleError, reportPage, selectedGeneration]);

  async function write<T>(
    key: string,
    path: string,
    body: unknown,
    success: (result: T) => string,
  ) {
    if (action) return false;
    setAction(key);
    setMessage(null);
    try {
      const result = await apiRequest<T>(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      setMessage(success(result));
      load(true);
      return true;
    } catch (caught) {
      handleError(caught, "Global Graph 操作失败");
      return false;
    } finally {
      setAction(null);
    }
  }

  async function createConfig(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const created = await write<GlobalGraphConfig>(
      "global-config",
      "/api/v1/admin/graph/global/configs",
      configDraft,
      (config) => `已创建不可变 GlobalGraphConfig ${config.version}`,
    );
    if (created) {
      setConfigDraft({ version: "", reason: "", confirmation: "" });
    }
  }

  async function startBuild(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const started = await write<GlobalGraphGeneration>(
      "global-build",
      "/api/v1/admin/graph/global/generations",
      buildDraft,
      (generation) =>
        `Global Generation ${generation.globalGeneration} 已进入构建队列`,
    );
    if (started) {
      setBuildDraft((current) => ({
        ...current,
        reason: "",
        confirmation: "",
      }));
    }
  }

  async function release(
    generation: GlobalGraphGeneration,
    mode: "publish" | "rollback",
  ) {
    const confirmation = mode === "publish" ? "PUBLISH" : "ROLLBACK";
    if (
      releaseConfirmation.trim() !== confirmation
      || !releaseReason.trim()
    ) {
      setMessage(`请输入审计理由，并在确认字段输入 ${confirmation}`);
      return;
    }
    const released = await write<GlobalGraphGeneration>(
      `global-${mode}-${generation.globalGeneration}`,
      mode === "publish"
        ? "/api/v1/admin/graph/global/publications"
        : "/api/v1/admin/graph/global/rollbacks",
      {
        globalGeneration: generation.globalGeneration,
        reason: releaseReason.trim(),
        confirmation,
      },
      (updated) => mode === "publish"
        ? `Global Generation ${updated.globalGeneration} 已发布`
        : `已回滚到 Global Generation ${updated.globalGeneration}`,
    );
    if (released) {
      setReleaseReason("");
      setReleaseConfirmation("");
    }
  }

  async function showReport(reportId: string) {
    if (selectedGeneration === null || action) return;
    setAction(`global-report-${reportId}`);
    setMessage(null);
    try {
      const result = await apiRequest<GlobalCommunityReportDetail>(
        `/api/v1/admin/graph/global/reports/${reportId}?generation=${selectedGeneration}`,
      );
      setReportDetail(result);
    } catch (caught) {
      handleError(caught, "Global Report 详情加载失败");
    } finally {
      setAction(null);
    }
  }

  if (loading && !overview) {
    return (
      <section className="index-status-panel">
        <div className="inline-state">
          <span className="spinner" aria-hidden="true" />
          正在加载 ALL_USERS Global Reports
        </div>
      </section>
    );
  }

  if (!overview) {
    return (
      <section className="index-status-panel">
        <h2>公共主题报告不可用</h2>
        <p>{message ?? "请检查 Backend 状态"}</p>
        <button className="secondary-button" onClick={() => load()}>重试</button>
      </section>
    );
  }

  return (
    <section className="index-status-panel global-graph-panel">
      <header>
        <div>
          <h2>公共主题报告</h2>
          <p>只从所有用户可见的公共子图生成；报告提供上下文，引用始终回到原始 Child 与来源位置。</p>
        </div>
        <span className={overview.runtime.enabled ? "generation-status ready" : "generation-status"}>
          报告模型 {overview.runtime.enabled ? "已配置" : "未启用"}
        </span>
      </header>

      {message ? <p className="generation-message" role="status">{message}</p> : null}

      <dl className="index-metrics graph-metrics">
        <div><dt>当前生效代次</dt><dd>{overview.activeGeneration ?? "未发布"}</dd></div>
        <div><dt>报告模型</dt><dd>{overview.runtime.model || "未配置"}</dd></div>
        <div><dt>模型版本</dt><dd>{overview.runtime.revision || "暂无数据"}</dd></div>
        <div><dt>Prompt</dt><dd>{overview.runtime.promptVersion}</dd></div>
        <div><dt>Schema</dt><dd>{overview.runtime.schemaVersion}</dd></div>
      </dl>

      <form className="generation-form" onSubmit={startBuild}>
        <label>
          GlobalGraphConfig
          <select
            value={buildDraft.globalConfigVersion}
            onChange={(event) => setBuildDraft((current) => ({
              ...current,
              globalConfigVersion: event.target.value,
            }))}
            required
          >
            <option value="">选择配置</option>
            {overview.configs.map((config) => (
              <option key={config.version} value={config.version}>
                {config.version}{config.runtimeCompatible ? "" : " · 不兼容"}
              </option>
            ))}
          </select>
        </label>
        <label>
          构建理由
          <input
            value={buildDraft.reason}
            onChange={(event) => setBuildDraft((current) => ({
              ...current,
              reason: event.target.value,
            }))}
            placeholder="记录公共报告构建目的"
            required
          />
        </label>
        <label>
          确认
          <input
            value={buildDraft.confirmation}
            onChange={(event) => setBuildDraft((current) => ({
              ...current,
              confirmation: event.target.value,
            }))}
            placeholder="输入 BUILD"
            required
          />
        </label>
        <button className="primary-button" disabled={Boolean(action) || !overview.configs.length}>
          构建公共报告版本
        </button>
      </form>

      <details className="generation-release-controls">
        <summary>创建不可变公共报告配置</summary>
        <form className="generation-form" onSubmit={createConfig}>
          <label>
            版本
            <input
              value={configDraft.version}
              onChange={(event) => setConfigDraft((current) => ({
                ...current,
                version: event.target.value,
              }))}
              required
            />
          </label>
          <label>
            理由
            <input
              value={configDraft.reason}
              onChange={(event) => setConfigDraft((current) => ({
                ...current,
                reason: event.target.value,
              }))}
              required
            />
          </label>
          <label>
            确认
            <input
              value={configDraft.confirmation}
              onChange={(event) => setConfigDraft((current) => ({
                ...current,
                confirmation: event.target.value,
              }))}
              placeholder="输入 CREATE"
              required
            />
          </label>
          <button className="primary-button" disabled={Boolean(action)}>创建配置</button>
        </form>
      </details>

      <div className="graph-config-list">
        {overview.configs.map((config) => (
          <article key={config.version}>
            <strong>{config.version}</strong>
            <span>{config.reportModel} · {config.reportRevision}</span>
            <small>
              Reports {config.reportLimit} · Context {config.contextTokenBudget} tokens
              {" · "}Map {config.mapCallLimit} / Calls {config.modelCallLimit}
              {" · "}{config.hardTimeoutMs} ms
            </small>
          </article>
        ))}
      </div>

      <div className="generation-release-controls">
        <label>发布理由<input value={releaseReason} onChange={(event) => setReleaseReason(event.target.value)} /></label>
        <label>确认<input value={releaseConfirmation} onChange={(event) => setReleaseConfirmation(event.target.value)} placeholder="PUBLISH 或 ROLLBACK" /></label>
      </div>

      <div className="table-wrap">
        <table className="generation-table graph-generation-table">
          <thead>
            <tr>
              <th>Generation</th>
              <th>状态</th>
              <th>公共报告</th>
              <th>索引</th>
              <th>发布闭包</th>
              <th>构建</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {overview.generations.map((generation) => (
              <tr key={generation.id}>
                <td>
                  <strong>Global {generation.globalGeneration}</strong>
                  <small>{generation.globalConfigVersion} · Source Graph {generation.sourceGraphGeneration}</small>
                </td>
                <td>
                  <span className={statusClass(generation.status)}>{generation.status}</span>
                  <small>{generation.caughtUp ? "已追平" : "未追平"}</small>
                </td>
                <td>
                  {generation.reportCount} Report · {generation.claimCount} Claim
                  <small>{generation.evidenceCount} Evidence</small>
                </td>
                <td>
                  {generation.indexedReportCount}/{generation.reportCount} indexed
                  <small>{generation.validVectorCount} vectors</small>
                </td>
                <td>
                  {generation.closure && generation.recovery ? (
                    <ProjectionClosureSummary
                      closure={generation.closure}
                      recovery={generation.recovery}
                    />
                  ) : generation.caughtUp ? "已追平" : "未追平"}
                </td>
                <td>
                  {generation.modelCallCount} calls · attempt {generation.buildAttempt}
                  {generation.failureCode ? <small>{generation.failureCode} · {generation.failureReason}</small> : null}
                </td>
                <td>
                  {generation.status === "READY" ? (
                    <button
                      className="text-button"
                      type="button"
                      disabled={Boolean(action)}
                      onClick={() => void release(generation, "publish")}
                    >
                      发布
                    </button>
                  ) : null}
                  {generation.status === "RETIRED" ? (
                    <button
                      className="text-button"
                      type="button"
                      disabled={Boolean(action)}
                      onClick={() => void release(generation, "rollback")}
                    >
                      回滚
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
            {!overview.generations.length ? (
              <tr><td colSpan={7}>尚未创建 Global Generation</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>

      <header className="graph-browser-header">
        <div>
          <h3>公共 Community Reports</h3>
          <p>这里只显示 ALL_USERS Evidence；失效 Evidence 会使整份 Report 失效。</p>
        </div>
        <label>
          浏览 Generation
          <select
            value={selectedGeneration ?? ""}
            onChange={(event) => {
              setReportPage(0);
              setSelectedGeneration(event.target.value ? Number(event.target.value) : null);
            }}
          >
            <option value="">选择 Generation</option>
            {overview.generations
              .filter((generation) =>
                ["READY", "ACTIVE", "RETIRED"].includes(generation.status))
              .map((generation) => (
                <option key={generation.id} value={generation.globalGeneration}>
                  {generation.globalGeneration} · {generation.status}
                </option>
              ))}
          </select>
        </label>
      </header>

      {browsing ? (
        <div className="inline-state"><span className="spinner" />正在加载公共报告</div>
      ) : (
        <div className="graph-item-list global-report-list">
          {reports?.items.map((report) => (
            <button
              type="button"
              key={report.id}
              onClick={() => void showReport(report.id)}
            >
              <strong>{report.title}</strong>
              <span>Community {report.communityKey}</span>
              <small>
                {report.claimCount} Claim · {report.evidenceCount} Evidence · {report.tokenCount} tokens
              </small>
            </button>
          ))}
          {!reports?.items.length ? <p>暂无可见公共报告</p> : null}
        </div>
      )}

      {reports && reports.total > reports.size ? (
        <div className="pagination">
          <button type="button" disabled={reportPage === 0} onClick={() => setReportPage((page) => page - 1)}>上一页</button>
          <span>第 {reports.page + 1} 页</span>
          <button type="button" disabled={(reports.page + 1) * reports.size >= reports.total} onClick={() => setReportPage((page) => page + 1)}>下一页</button>
        </div>
      ) : null}

      {reportDetail ? (
        <article className="graph-detail global-report-detail">
          <header>
            <div>
              <span>Global Report · Community {reportDetail.report.communityKey}</span>
              <h3>{reportDetail.report.title}</h3>
              <p>{reportDetail.report.summary}</p>
            </div>
            <button className="text-button" onClick={() => setReportDetail(null)}>关闭</button>
          </header>
          <section>
            <h4>Claims 与原始 Evidence</h4>
            {reportDetail.claims.map((claim) => (
              <blockquote className="graph-claim" key={claim.id}>
                {claim.claimText}
                {claim.evidence.map((evidence) => (
                  <div className="global-claim-evidence" key={evidence.id}>
                    <p>{evidence.evidenceText}</p>
                    <footer>
                      {evidence.documentTitle}
                      {" · Revision "}{evidence.revisionNumber}
                      {" ("}{evidence.revisionId.slice(0, 8)}…{")"}
                      {" · SourceSpan "}{evidence.sourceSpanId.slice(0, 8)}…
                      {" · "}
                      <SourceLocation source={evidence} linkToSource={false} />
                      {" · "}
                      <Link to={`/chunks/${evidence.childChunkId}`}>Evidence Child</Link>
                      {" · "}
                      <Link to={`/documents/${evidence.documentId}`}>文档</Link>
                    </footer>
                  </div>
                ))}
              </blockquote>
            ))}
          </section>
        </article>
      ) : null}
    </section>
  );
}

export function GraphAdminPage() {
  const { expireSession } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [overview, setOverview] = useState<GraphOverview | null>(null);
  const [retrieval, setRetrieval] =
    useState<GraphRetrievalConfiguration | null>(null);
  const [rebuildRequests, setRebuildRequests] =
    useState<GraphRebuildRequest[]>([]);
  const [rebuildError, setRebuildError] = useState(false);
  const [selectedGeneration, setSelectedGeneration] = useState<number | null>(
    null,
  );
  const [entityPage, setEntityPage] = useState<GraphEntityPage | null>(null);
  const [communityPage, setCommunityPage] =
    useState<GraphCommunityPage | null>(null);
  const [entityDetail, setEntityDetail] =
    useState<GraphEntityDetail | null>(null);
  const [communityDetail, setCommunityDetail] =
    useState<GraphCommunityDetail | null>(null);
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [entityPageNumber, setEntityPageNumber] = useState(0);
  const [communityPageNumber, setCommunityPageNumber] = useState(0);
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [browsing, setBrowsing] = useState(false);
  const [action, setAction] = useState<string | null>(null);
  const [releaseReason, setReleaseReason] = useState("");
  const [releaseConfirmation, setReleaseConfirmation] = useState("");
  const [retrievalReleaseReason, setRetrievalReleaseReason] = useState("");
  const [retrievalReleaseConfirmation, setRetrievalReleaseConfirmation] =
    useState("");
  const [retrievalDraft, setRetrievalDraft] = useState({
    version: "",
    seedLimit: 5,
    maxHops: 2,
    entityLimit: 20,
    edgeLimit: 40,
    graphChildLimit: 30,
    graphWeight: 1,
    graphContextTokenBudget: 900,
    graphContextPercent: 15,
    statementTimeoutMs: 500,
    reason: "",
    confirmation: "",
  });
  const [configDraft, setConfigDraft] = useState({
    version: "",
    extractionModel: "",
    extractionRevision: "",
    reason: "",
    confirmation: "",
  });
  const [buildDraft, setBuildDraft] = useState({
    graphConfigVersion: "",
    reason: "",
    confirmation: "",
  });
  const [ruleDraft, setRuleDraft] = useState({
    newConfigVersion: "",
    action: "MERGE" as "MERGE" | "SPLIT",
    matchAliases: [] as string[],
    targetCanonicalName: "",
    targetEntityType: "",
    confirmation: "",
    reason: "",
  });
  const [ruleGeneration, setRuleGeneration] = useState<number | null>(null);
  const [ruleEntities, setRuleEntities] = useState<GraphEntitySummary[]>([]);
  const [ruleEntityQuery, setRuleEntityQuery] = useState("");
  const [ruleEntityType, setRuleEntityType] = useState("");
  const [ruleEntityResults, setRuleEntityResults] =
    useState<GraphEntityPage | null>(null);
  const [ruleEntityCursor, setRuleEntityCursor] = useState<string | null>(null);
  const [ruleSearching, setRuleSearching] = useState(false);
  const [rulePreview, setRulePreview] =
    useState<GraphResolutionPreview | null>(null);
  const [ruleIdempotencyKey, setRuleIdempotencyKey] = useState("");
  const [candidatePage, setCandidatePage] =
    useState<GraphResolutionCandidatePage | null>(null);
  const [candidateDetail, setCandidateDetail] =
    useState<GraphResolutionCandidateDetail | null>(null);
  const [candidateType, setCandidateType] = useState("");
  const [candidateStatus, setCandidateStatus] = useState("ACTIVE");
  const [candidateSignal, setCandidateSignal] = useState("");
  const [candidateQuery, setCandidateQuery] = useState("");
  const [candidateAppliedQuery, setCandidateAppliedQuery] = useState("");
  const [candidateLoading, setCandidateLoading] = useState(false);
  const [candidateRefreshDraft, setCandidateRefreshDraft] = useState({
    reason: "",
    confirmation: "",
  });
  const [candidateStateReason, setCandidateStateReason] = useState("");
  const [proposalPage, setProposalPage] =
    useState<GraphResolutionProposalPage | null>(null);
  const [proposalDetail, setProposalDetail] =
    useState<GraphResolutionProposalDetail | null>(null);
  const [proposalStatus, setProposalStatus] = useState("");
  const [proposalLoading, setProposalLoading] = useState(false);
  const [proposalActionReason, setProposalActionReason] = useState("");
  const [proposalConfirmation, setProposalConfirmation] = useState("");
  const [editingProposalId, setEditingProposalId] = useState<string | null>(null);
  const [originCandidateId, setOriginCandidateId] = useState<string | null>(null);
  const overviewRequest = useRef<AbortController | null>(null);
  const browseRequest = useRef<AbortController | null>(null);
  const detailRequest = useRef<AbortController | null>(null);
  const candidateRequest = useRef<AbortController | null>(null);
  const candidateDetailRequest = useRef<AbortController | null>(null);
  const proposalRequest = useRef<AbortController | null>(null);
  const resolutionSection = useRef<HTMLElement | null>(null);
  const graphView = searchParams.get("view") === "graph";
  const graphRootType = searchParams.get("rootType") as GraphRootType | null;
  const graphRootId = searchParams.get("rootId");
  const graphGeneration = Number(searchParams.get("generation"));
  const graphHops: 1 | 2 = searchParams.get("hops") === "2" ? 2 : 1;
  const graphRootValid = Boolean(
    graphRootId
    && (graphRootType === "ENTITY" || graphRootType === "COMMUNITY")
    && Number.isSafeInteger(graphGeneration)
    && graphGeneration > 0,
  );

  const handleError = useCallback((caught: unknown, fallback: string) => {
    if (caught instanceof ApiError && caught.status === 401) {
      expireSession();
      return;
    }
    setMessage(caught instanceof ApiError ? caught.message : fallback);
  }, [expireSession]);

  const loadOverview = useCallback((quiet = false) => {
    overviewRequest.current?.abort();
    const controller = new AbortController();
    overviewRequest.current = controller;
    if (!quiet) setLoading(true);
    setRebuildError(false);
    Promise.allSettled([
      apiRequest<GraphOverview>("/api/v1/admin/graph", {
        signal: controller.signal,
      }),
      apiRequest<GraphRetrievalConfiguration>(
        "/api/v1/admin/graph/retrieval",
        { signal: controller.signal },
      ),
      apiRequest<GraphRebuildRequest[]>(
        "/api/v1/admin/graph/rebuild-requests",
        { signal: controller.signal },
      ),
    ])
      .then(([overviewResult, retrievalResult, rebuildResult]) => {
        if (controller.signal.aborted) return;
        if (overviewResult.status === "rejected") throw overviewResult.reason;
        if (retrievalResult.status === "rejected") throw retrievalResult.reason;
        const response = overviewResult.value;
        const retrievalResponse = retrievalResult.value;
        setOverview(response);
        setRetrieval(retrievalResponse);
        if (rebuildResult.status === "fulfilled") {
          setRebuildRequests(rebuildResult.value);
          setRebuildError(false);
        } else {
          if (rebuildResult.reason instanceof ApiError
              && rebuildResult.reason.status === 401) {
            throw rebuildResult.reason;
          }
          setRebuildError(true);
        }
        setBuildDraft((current) => ({
          ...current,
          graphConfigVersion:
            current.graphConfigVersion
            || response.configs.at(-1)?.version
            || "",
        }));
        setRuleGeneration((current) => {
          const previewable = response.generations.filter(
            (item) => item.status === "ACTIVE" || item.status === "READY",
          );
          if (current !== null && previewable.some(
            (item) => item.graphGeneration === current,
          )) return current;
          return response.activeGeneration
            ?? previewable[0]?.graphGeneration
            ?? null;
        });
        setSelectedGeneration((current) => {
          if (
            current !== null
            && response.generations.some(
              (item) => item.graphGeneration === current,
            )
          ) {
            return current;
          }
          return response.activeGeneration
            ?? response.generations[0]?.graphGeneration
            ?? null;
        });
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) {
          handleError(caught, "图谱状态加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted && !quiet) setLoading(false);
        if (overviewRequest.current === controller) {
          overviewRequest.current = null;
        }
      });
  }, [handleError]);

  useEffect(() => {
    loadOverview();
    return () => {
      overviewRequest.current?.abort();
      browseRequest.current?.abort();
      detailRequest.current?.abort();
      candidateRequest.current?.abort();
      candidateDetailRequest.current?.abort();
    };
  }, [loadOverview]);

  const building = overview?.generations.some(
    (generation) => generation.status === "BUILDING",
  ) ?? false;

  useEffect(() => {
    if (!building) return;
    const timer = window.setInterval(() => loadOverview(true), 2000);
    return () => window.clearInterval(timer);
  }, [building, loadOverview]);

  useEffect(() => {
    if (selectedGeneration === null) {
      setEntityPage(null);
      setCommunityPage(null);
      return;
    }
    browseRequest.current?.abort();
    detailRequest.current?.abort();
    const controller = new AbortController();
    browseRequest.current = controller;
    setBrowsing(true);
    setEntityDetail(null);
    setCommunityDetail(null);
    const generation = encodeURIComponent(String(selectedGeneration));
    const entityQuery = encodeURIComponent(appliedQuery);
    Promise.all([
      apiRequest<GraphEntityPage>(
        `/api/v1/admin/graph/entities?generation=${generation}&query=${entityQuery}&page=${entityPageNumber}&size=20`,
        { signal: controller.signal },
      ),
      apiRequest<GraphCommunityPage>(
        `/api/v1/admin/graph/communities?generation=${generation}&page=${communityPageNumber}&size=20`,
        { signal: controller.signal },
      ),
    ])
      .then(([entities, communities]) => {
        if (controller.signal.aborted) return;
        setEntityPage(entities);
        setCommunityPage(communities);
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) {
          handleError(caught, "图谱内容加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setBrowsing(false);
        if (browseRequest.current === controller) {
          browseRequest.current = null;
        }
      });
    return () => controller.abort();
  }, [
    appliedQuery,
    communityPageNumber,
    entityPageNumber,
    handleError,
    selectedGeneration,
  ]);

  const selectedManifest = useMemo(
    () => overview?.generations.find(
      (item) => item.graphGeneration === selectedGeneration,
    ) ?? null,
    [overview, selectedGeneration],
  );
  const ruleManifest = useMemo(
    () => overview?.generations.find(
      (item) => item.graphGeneration === ruleGeneration,
    ) ?? null,
    [overview, ruleGeneration],
  );

  const loadCandidates = useCallback((append = false, cursor: string | null = null) => {
    if (ruleGeneration === null) {
      setCandidatePage(null);
      setCandidateDetail(null);
      return;
    }
    candidateRequest.current?.abort();
    const controller = new AbortController();
    candidateRequest.current = controller;
    setCandidateLoading(true);
    const params = new URLSearchParams({
      generation: String(ruleGeneration),
      limit: "20",
    });
    if (candidateType) params.set("candidateType", candidateType);
    if (candidateStatus) params.set("status", candidateStatus);
    if (candidateSignal) params.set("signal", candidateSignal);
    if (candidateAppliedQuery) params.set("entityQuery", candidateAppliedQuery);
    if (append && cursor) {
      params.set("cursor", cursor);
    }
    apiRequest<GraphResolutionCandidatePage>(
      `/api/v1/admin/graph/resolution-candidates?${params}`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (controller.signal.aborted) return;
        setCandidatePage((current) => {
          if (!append || !current) return result;
          const known = new Set(current.items.map((item) => item.id));
          return {
            ...result,
            items: [
              ...current.items,
              ...result.items.filter((item) => !known.has(item.id)),
            ],
          };
        });
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) {
          handleError(caught, "实体治理候选加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setCandidateLoading(false);
        if (candidateRequest.current === controller) {
          candidateRequest.current = null;
        }
      });
  }, [
    candidateAppliedQuery,
    candidateSignal,
    candidateStatus,
    candidateType,
    handleError,
    ruleGeneration,
  ]);

  useEffect(() => {
    loadCandidates(false);
    setCandidateDetail(null);
    return () => candidateRequest.current?.abort();
  }, [loadCandidates]);

  const loadProposals = useCallback((quiet = false) => {
    proposalRequest.current?.abort();
    const controller = new AbortController();
    proposalRequest.current = controller;
    if (!quiet) setProposalLoading(true);
    const params = new URLSearchParams({ page: "0", size: "30" });
    if (proposalStatus) params.set("status", proposalStatus);
    apiRequest<GraphResolutionProposalPage>(
      `/api/v1/admin/graph/resolution-proposals?${params}`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (!controller.signal.aborted) setProposalPage(result);
      })
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) {
          handleError(caught, "待生效规则加载失败");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setProposalLoading(false);
        if (proposalRequest.current === controller) proposalRequest.current = null;
      });
  }, [handleError, proposalStatus]);

  useEffect(() => {
    loadProposals(false);
    setProposalDetail(null);
    return () => proposalRequest.current?.abort();
  }, [loadProposals]);

  function invalidateRulePreview() {
    setRulePreview(null);
    setRuleIdempotencyKey("");
    setRuleDraft((current) => ({ ...current, confirmation: "" }));
  }

  function updateRuleDraft(
    updates: Partial<typeof ruleDraft>,
  ) {
    invalidateRulePreview();
    setRuleDraft((current) => ({ ...current, ...updates }));
  }

  useEffect(() => {
    setRuleEntities([]);
    setRuleEntityResults(null);
    setRuleEntityCursor(null);
    setRulePreview(null);
    setRuleIdempotencyKey("");
    setRuleDraft((current) => ({ ...current, confirmation: "" }));
  }, [ruleGeneration]);

  useEffect(() => {
    if (
      graphView
      && Number.isSafeInteger(graphGeneration)
      && graphGeneration > 0
      && overview?.generations.some(
        (item) => item.graphGeneration === graphGeneration
          && ["READY", "ACTIVE", "RETIRED"].includes(item.status),
      )
    ) {
      setSelectedGeneration(graphGeneration);
    }
  }, [graphGeneration, graphView, overview]);

  function updateGraphLocation(
    updates: Record<string, string | null>,
  ) {
    const next = new URLSearchParams(searchParams);
    Object.entries(updates).forEach(([key, value]) => {
      if (value === null) next.delete(key);
      else next.set(key, value);
    });
    setSearchParams(next);
  }

  function openGraph(rootType: GraphRootType, rootId: string) {
    if (selectedGeneration === null) return;
    updateGraphLocation({
      view: "graph",
      generation: String(selectedGeneration),
      rootType,
      rootId,
      hops: "1",
    });
  }

  function showList() {
    updateGraphLocation({ view: "list" });
  }

  async function write<T>(
    key: string,
    path: string,
    body: unknown,
    success: (result: T) => string,
  ): Promise<boolean> {
    if (action) return false;
    setAction(key);
    setMessage(null);
    try {
      const result = await apiRequest<T>(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      setMessage(success(result));
      loadOverview(true);
      return true;
    } catch (caught) {
      handleError(caught, "图谱操作失败");
      return false;
    } finally {
      setAction(null);
    }
  }

  async function createConfig(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const created = await write<GraphConfig>(
      "config",
      "/api/v1/admin/graph/configs",
      configDraft,
      (created) => `已创建不可变 GraphConfig ${created.version}`,
    );
    if (created) {
      setConfigDraft({
        version: "",
        extractionModel: "",
        extractionRevision: "",
        reason: "",
        confirmation: "",
      });
    }
  }

  async function createRetrievalProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const created = await write<GraphRetrievalProfile>(
      "retrieval-profile",
      "/api/v1/admin/graph/retrieval/profiles",
      retrievalDraft,
      (profile) => `已创建不可变 GraphRetrievalProfile ${profile.version}`,
    );
    if (created) {
      setRetrievalDraft((current) => ({
        ...current,
        version: "",
        reason: "",
        confirmation: "",
      }));
    }
  }

  async function releaseRetrievalProfile(
    profileVersion: string,
    mode: "publish" | "rollback",
  ) {
    const confirmation = mode === "publish" ? "PUBLISH" : "ROLLBACK";
    if (
      retrievalReleaseConfirmation.trim() !== confirmation
      || !retrievalReleaseReason.trim()
    ) {
      setMessage(`请输入审计理由，并在确认字段输入 ${confirmation}`);
      return;
    }
    const released = await write<GraphRetrievalPublication>(
      `retrieval-${mode}-${profileVersion}`,
      mode === "publish"
        ? "/api/v1/admin/graph/retrieval/publications"
        : "/api/v1/admin/graph/retrieval/rollbacks",
      {
        profileVersion,
        reason: retrievalReleaseReason.trim(),
        confirmation,
      },
      (publication) => (
        mode === "publish"
          ? `GraphRetrievalProfile ${publication.profileVersion} 已发布`
          : `已回滚到 GraphRetrievalProfile ${publication.profileVersion}`
      ),
    );
    if (released) {
      setRetrievalReleaseReason("");
      setRetrievalReleaseConfirmation("");
    }
  }

  async function startBuild(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const started = await write<GraphGeneration>(
      "build",
      "/api/v1/admin/graph/generations",
      buildDraft,
      (generation) => `Graph Generation ${generation.graphGeneration} 已进入离线构建队列`,
    );
    if (started) {
      setBuildDraft((current) => ({
        ...current,
        reason: "",
        confirmation: "",
      }));
    }
  }

  async function release(
    generation: GraphGeneration,
    mode: "publish" | "rollback",
  ) {
    const expected = mode === "publish" ? "PUBLISH" : "ROLLBACK";
    if (
      releaseConfirmation.trim() !== expected
      || !releaseReason.trim()
    ) {
      setMessage(`请输入审计理由，并在确认字段输入 ${expected}`);
      return;
    }
    const released = await write<GraphGeneration>(
      `${mode}-${generation.graphGeneration}`,
      mode === "publish"
        ? "/api/v1/admin/graph/publications"
        : "/api/v1/admin/graph/rollbacks",
      {
        graphGeneration: generation.graphGeneration,
        confirmation: expected,
        reason: releaseReason.trim(),
      },
      (updated) => (
        mode === "publish"
          ? `Graph Generation ${updated.graphGeneration} 已发布`
          : `已回滚到 Graph Generation ${updated.graphGeneration}`
      ),
    );
    if (released) {
      setReleaseReason("");
      setReleaseConfirmation("");
    }
  }

  async function searchRuleEntities(append = false) {
    if (ruleGeneration === null || ruleSearching) return;
    setRuleSearching(true);
    setMessage(null);
    try {
      const params = new URLSearchParams({
        generation: String(ruleGeneration),
        query: ruleEntityQuery.trim(),
        entityType: ruleEntityType.trim(),
        size: "10",
      });
      if (append && ruleEntityCursor) {
        params.set("cursor", ruleEntityCursor);
      }
      const result = await apiRequest<GraphEntityPage>(
        `/api/v1/admin/graph/entities?${params}`,
      );
      setRuleEntityResults((current) => {
        if (!append || !current) return result;
        const known = new Set(current.items.map((item) => item.id));
        return {
          ...result,
          items: [
            ...current.items,
            ...result.items.filter((item) => !known.has(item.id)),
          ],
        };
      });
      setRuleEntityCursor(result.nextCursor ?? null);
    } catch (caught) {
      handleError(caught, "实体搜索失败");
    } finally {
      setRuleSearching(false);
    }
  }

  function addRuleEntity(entity: GraphEntitySummary) {
    if (ruleEntities.some((item) => item.id === entity.id)) return;
    if (ruleDraft.action === "MERGE" && ruleEntities.length >= 20) {
      setMessage("MERGE 最多选择 20 个来源实体");
      return;
    }
    invalidateRulePreview();
    setRuleEntities((current) => (
      ruleDraft.action === "SPLIT" ? [entity] : [...current, entity]
    ));
    setRuleDraft((current) => ({
      ...current,
      matchAliases: [],
      targetCanonicalName: current.targetCanonicalName
        || entity.canonicalName,
      targetEntityType: current.targetEntityType || entity.entityType,
    }));
  }

  function removeRuleEntity(entityId: string) {
    invalidateRulePreview();
    setRuleEntities((current) => current.filter(
      (item) => item.id !== entityId,
    ));
  }

  function changeRuleAction(next: "MERGE" | "SPLIT") {
    invalidateRulePreview();
    setRuleEntities((current) => (
      next === "SPLIT" ? current.slice(0, 1) : current
    ));
    setRuleDraft((current) => ({
      ...current,
      action: next,
      matchAliases: [],
    }));
  }

  function toggleRuleAlias(alias: string) {
    const selected = ruleDraft.matchAliases.includes(alias);
    updateRuleDraft({
      matchAliases: selected
        ? ruleDraft.matchAliases.filter((item) => item !== alias)
        : [...ruleDraft.matchAliases, alias],
    });
  }

  async function previewRule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ruleManifest || action) return;
    setAction("rule-preview");
    setMessage(null);
    try {
      const preview = await apiRequest<GraphResolutionPreview>(
        "/api/v1/admin/graph/resolution-rules/previews",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            graphGeneration: ruleManifest.graphGeneration,
            baseConfigVersion: ruleManifest.graphConfigVersion,
            action: ruleDraft.action,
            sourceEntityIds: ruleEntities.map((item) => item.id),
            matchAliases: ruleDraft.matchAliases,
            targetCanonicalName: ruleDraft.targetCanonicalName,
            targetEntityType: ruleDraft.targetEntityType,
          }),
        },
      );
      setRulePreview(preview);
      setRuleIdempotencyKey(
        preview.previewToken ? newIdempotencyKey() : "",
      );
    } catch (caught) {
      handleError(caught, "影响预检失败");
    } finally {
      setAction(null);
    }
  }

  async function createRule() {
    if (!rulePreview?.previewToken || action) return;
    if (ruleDraft.confirmation.trim() !== "APPLY_NEXT_BUILD") {
      setMessage("请在确认字段输入 APPLY_NEXT_BUILD");
      return;
    }
    setAction("rule-create");
    setMessage(null);
    try {
      const created = await apiRequest<GraphConfig>(
        "/api/v1/admin/graph/resolution-rules",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            previewToken: rulePreview.previewToken,
            newConfigVersion: ruleDraft.newConfigVersion,
            confirmation: ruleDraft.confirmation.trim(),
            reason: ruleDraft.reason.trim(),
            idempotencyKey: ruleIdempotencyKey,
          }),
        },
      );
      setMessage(`规则将在 ${created.version} 的下一次构建中生效`);
      setRuleEntities([]);
      setRulePreview(null);
      setRuleIdempotencyKey("");
      setRuleDraft((current) => ({
        ...current,
        newConfigVersion: "",
        matchAliases: [],
        targetCanonicalName: "",
        targetEntityType: "",
        confirmation: "",
        reason: "",
      }));
      loadOverview(true);
    } catch (caught) {
      if (caught instanceof ApiError && [
        "GRAPH_RULE_PREVIEW_EXPIRED",
        "GRAPH_RULE_PREVIEW_STALE",
        "GRAPH_RULE_PREVIEW_CONSUMED",
      ].includes(caught.code)) {
        setRulePreview(null);
        setRuleIdempotencyKey("");
        setRuleDraft((current) => ({
          ...current,
          confirmation: "",
        }));
      }
      handleError(caught, "实体消歧规则创建失败");
    } finally {
      setAction(null);
    }
  }

  function resetProposalEditor() {
    setEditingProposalId(null);
    setOriginCandidateId(null);
    setRuleEntities([]);
    setRulePreview(null);
    setRuleIdempotencyKey("");
    setRuleDraft((current) => ({
      ...current,
      newConfigVersion: "",
      matchAliases: [],
      targetCanonicalName: "",
      targetEntityType: "",
      confirmation: "",
      reason: "",
    }));
  }

  async function saveProposal() {
    if (!ruleManifest || action) return;
    if (ruleDraft.reason.trim().length < 8) {
      setMessage("请填写至少 8 个字的 Proposal 修订理由");
      return;
    }
    const editing = editingProposalId
      ? proposalDetail?.proposal.id === editingProposalId
        ? proposalDetail.proposal
        : proposalPage?.items.find((item) => item.id === editingProposalId)
      : null;
    setAction("proposal-save");
    setMessage(null);
    try {
      const result = await apiRequest<GraphResolutionProposalDetail>(
        editing
          ? `/api/v1/admin/graph/resolution-proposals/${editing.id}/revisions`
          : "/api/v1/admin/graph/resolution-proposals",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            ...(editing ? {
              expectedRevision: editing.currentRevision,
              expectedVersion: editing.version,
            } : {
              candidateId: originCandidateId,
              graphGeneration: ruleManifest.graphGeneration,
              baseConfigVersion: ruleManifest.graphConfigVersion,
            }),
            action: ruleDraft.action,
            sourceEntityIds: ruleEntities.map((item) => item.id),
            matchAliases: ruleDraft.matchAliases,
            targetCanonicalName: ruleDraft.targetCanonicalName,
            targetEntityType: ruleDraft.targetEntityType,
            reason: ruleDraft.reason.trim(),
            idempotencyKey: newIdempotencyKey(),
          }),
        },
      );
      setProposalDetail(result);
      setEditingProposalId(result.proposal.id);
      setOriginCandidateId(result.proposal.candidateId);
      setMessage(editing ? "已追加不可变 Proposal Revision" : "治理草案已进入待生效规则队列");
      loadProposals(true);
    } catch (caught) {
      handleError(caught, editing ? "Proposal 修订失败" : "Proposal 创建失败");
    } finally {
      setAction(null);
    }
  }

  async function showProposal(id: string) {
    setProposalLoading(true);
    try {
      const detail = await apiRequest<GraphResolutionProposalDetail>(
        `/api/v1/admin/graph/resolution-proposals/${id}`,
      );
      setProposalDetail(detail);
    } catch (caught) {
      handleError(caught, "Proposal 详情加载失败");
    } finally {
      setProposalLoading(false);
    }
  }

  function editProposal(proposal: GraphResolutionProposalSummary) {
    void showProposal(proposal.id);
    setEditingProposalId(proposal.id);
    setOriginCandidateId(proposal.candidateId);
    setRuleGeneration(proposal.baseGraphGeneration);
    setRuleEntities(proposal.entities.map((entity) => ({
      id: entity.id,
      canonicalName: entity.canonicalName,
      entityType: entity.entityType,
      description: null,
      aliases: entity.aliases,
      mentionCount: entity.mentionCount,
      relationshipCount: entity.relationshipCount,
      communityKey: null,
    })));
    setRuleDraft((current) => ({
      ...current,
      action: proposal.action,
      matchAliases: proposal.matchAliases,
      targetCanonicalName: proposal.targetCanonicalName,
      targetEntityType: proposal.targetEntityType,
      newConfigVersion: proposal.materializedConfigVersion ?? "",
      confirmation: "",
      reason: "",
    }));
    setRulePreview(null);
    setMessage("已载入当前 Proposal；修订会追加新版本，不会覆盖历史 Revision");
    window.requestAnimationFrame(() => {
      resolutionSection.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
    });
  }

  async function withdrawProposal(proposal: GraphResolutionProposalSummary) {
    if (proposalActionReason.trim().length < 8
      || proposalConfirmation !== "WITHDRAW_RESOLUTION_PROPOSAL") {
      setMessage("请填写至少 8 个字的理由，并输入 WITHDRAW_RESOLUTION_PROPOSAL");
      return;
    }
    setAction(`proposal-withdraw-${proposal.id}`);
    try {
      const result = await apiRequest<GraphResolutionProposalDetail>(
        `/api/v1/admin/graph/resolution-proposals/${proposal.id}/withdraw`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            expectedRevision: proposal.currentRevision,
            expectedVersion: proposal.version,
            confirmation: proposalConfirmation,
            reason: proposalActionReason.trim(),
            idempotencyKey: newIdempotencyKey(),
          }),
        },
      );
      setProposalDetail(result);
      setProposalActionReason("");
      setProposalConfirmation("");
      setMessage("Proposal 已撤回，历史 Revision 保持只读");
      loadProposals(true);
    } catch (caught) {
      handleError(caught, "Proposal 撤回失败");
    } finally {
      setAction(null);
    }
  }

  async function materializeProposal() {
    const proposal = proposalDetail?.proposal;
    if (!proposal || !rulePreview?.previewToken || action) return;
    if (proposal.id !== editingProposalId) {
      setMessage("请先从待生效规则载入当前 Proposal");
      return;
    }
    if (ruleDraft.confirmation !== "MATERIALIZE_RESOLUTION_PROPOSAL") {
      setMessage("请在确认字段输入 MATERIALIZE_RESOLUTION_PROPOSAL");
      return;
    }
    setAction("proposal-materialize");
    try {
      const result = await apiRequest<GraphResolutionProposalDetail>(
        `/api/v1/admin/graph/resolution-proposals/${proposal.id}/materialize`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            expectedRevision: proposal.currentRevision,
            expectedVersion: proposal.version,
            previewToken: rulePreview.previewToken,
            newConfigVersion: ruleDraft.newConfigVersion,
            confirmation: ruleDraft.confirmation,
            reason: ruleDraft.reason.trim(),
            idempotencyKey: ruleIdempotencyKey || newIdempotencyKey(),
          }),
        },
      );
      setProposalDetail(result);
      setRulePreview(null);
      setMessage(`Proposal 已物化为 ${result.proposal.materializedConfigVersion}；尚未构建或发布`);
      loadProposals(true);
      loadOverview(true);
    } catch (caught) {
      if (caught instanceof ApiError && caught.code.includes("PREVIEW")) {
        setRulePreview(null);
      }
      handleError(caught, "Proposal 物化失败");
    } finally {
      setAction(null);
    }
  }

  async function refreshResolutionCandidates(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (ruleGeneration === null || action) return;
    const reason = candidateRefreshDraft.reason.trim();
    if (reason.length < 8) {
      setMessage("请填写至少 8 个字的候选刷新审计理由");
      return;
    }
    if (candidateRefreshDraft.confirmation !== "REFRESH_RESOLUTION_CANDIDATES") {
      setMessage("请在确认字段输入 REFRESH_RESOLUTION_CANDIDATES");
      return;
    }
    setAction("candidate-refresh");
    setMessage(null);
    try {
      const snapshot = await apiRequest<NonNullable<GraphResolutionCandidatePage["snapshot"]>>(
        "/api/v1/admin/graph/resolution-candidates/refresh",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            graphGeneration: ruleGeneration,
            reason,
            confirmation: candidateRefreshDraft.confirmation,
            idempotencyKey: newIdempotencyKey(),
          }),
        },
      );
      setCandidateRefreshDraft({ reason: "", confirmation: "" });
      setMessage(
        `候选快照已就绪：${snapshot.duplicateCandidateCount} 条疑似重复，${snapshot.splitCandidateCount} 条疑似误合并`,
      );
      loadCandidates(false);
    } catch (caught) {
      handleError(caught, "候选刷新失败");
    } finally {
      setAction(null);
    }
  }

  async function showCandidate(candidateId: string) {
    candidateDetailRequest.current?.abort();
    const controller = new AbortController();
    candidateDetailRequest.current = controller;
    setCandidateLoading(true);
    try {
      const detail = await apiRequest<GraphResolutionCandidateDetail>(
        `/api/v1/admin/graph/resolution-candidates/${candidateId}`,
        { signal: controller.signal },
      );
      if (!controller.signal.aborted) setCandidateDetail(detail);
    } catch (caught) {
      if (!controller.signal.aborted) {
        handleError(caught, "候选详情加载失败");
      }
    } finally {
      if (!controller.signal.aborted) setCandidateLoading(false);
      if (candidateDetailRequest.current === controller) {
        candidateDetailRequest.current = null;
      }
    }
  }

  async function changeCandidateState(
    candidate: GraphResolutionCandidateSummary,
    operation: "IGNORE" | "RESTORE",
  ) {
    if (action) return;
    const reason = candidateStateReason.trim();
    if (reason.length < 8) {
      setMessage("请先填写至少 8 个字的候选处理理由");
      return;
    }
    setAction(`candidate-${operation.toLowerCase()}-${candidate.id}`);
    setMessage(null);
    try {
      const updated = await apiRequest<GraphResolutionCandidateSummary>(
        `/api/v1/admin/graph/resolution-candidates/${candidate.id}/${operation.toLowerCase()}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            expectedVersion: candidate.version,
            confirmation: `${operation}_RESOLUTION_CANDIDATE`,
            reason,
            idempotencyKey: newIdempotencyKey(),
          }),
        },
      );
      setCandidateStateReason("");
      setMessage(operation === "IGNORE" ? "候选已忽略" : "候选已恢复为待核对");
      setCandidateDetail((current) => current && current.candidate.id === updated.id
        ? { ...current, candidate: updated }
        : current);
      loadCandidates(false);
    } catch (caught) {
      handleError(caught, operation === "IGNORE" ? "忽略候选失败" : "恢复候选失败");
    } finally {
      setAction(null);
    }
  }

  function startRuleFromCandidate(candidate: GraphResolutionCandidateSummary) {
    if (candidate.status !== "ACTIVE" || !candidatePage?.snapshot) return;
    invalidateRulePreview();
    setEditingProposalId(null);
    setOriginCandidateId(candidate.id);
    setRuleGeneration(candidatePage.snapshot.graphGeneration);
    setRuleEntities(candidate.entities.map((entity) => ({
      id: entity.id,
      canonicalName: entity.canonicalName,
      entityType: entity.entityType,
      description: null,
      aliases: entity.aliases,
      mentionCount: entity.mentionCount,
      relationshipCount: entity.relationshipCount,
      communityKey: null,
    })));
    setRuleDraft((current) => ({
      ...current,
      action: candidate.suggestedAction,
      matchAliases: candidate.suggestedAction === "SPLIT"
        ? candidate.suggestedAliases
        : [],
      targetCanonicalName: candidate.suggestedTargetName
        ?? candidate.entities[0]?.canonicalName
        ?? "",
      targetEntityType: candidate.suggestedTargetType
        ?? candidate.entities[0]?.entityType
        ?? "",
      confirmation: "",
    }));
    setMessage("候选已带入规则草案；请核对实体与 Evidence 后重新执行影响预检");
    window.requestAnimationFrame(() => {
      resolutionSection.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
    });
  }

  async function showEntity(id: string, generation = selectedGeneration) {
    if (generation === null) return;
    detailRequest.current?.abort();
    const controller = new AbortController();
    detailRequest.current = controller;
    setAction(`entity-${id}`);
    setCommunityDetail(null);
    try {
      const detail = await apiRequest<GraphEntityDetail>(
        `/api/v1/admin/graph/entities/${id}?generation=${generation}`,
        { signal: controller.signal },
      );
      if (controller.signal.aborted) return;
      setEntityDetail(detail);
    } catch (caught) {
      if (!controller.signal.aborted) {
        handleError(caught, "实体详情加载失败");
      }
    } finally {
      if (detailRequest.current === controller) {
        detailRequest.current = null;
        setAction(null);
      }
    }
  }

  async function showCommunity(id: string) {
    if (selectedGeneration === null) return;
    detailRequest.current?.abort();
    const controller = new AbortController();
    detailRequest.current = controller;
    setAction(`community-${id}`);
    setEntityDetail(null);
    try {
      const detail = await apiRequest<GraphCommunityDetail>(
        `/api/v1/admin/graph/communities/${id}?generation=${selectedGeneration}`,
        { signal: controller.signal },
      );
      if (controller.signal.aborted) return;
      setCommunityDetail(detail);
    } catch (caught) {
      if (!controller.signal.aborted) {
        handleError(caught, "Community 详情加载失败");
      }
    } finally {
      if (detailRequest.current === controller) {
        detailRequest.current = null;
        setAction(null);
      }
    }
  }

  if (loading && !overview) {
    return (
      <div className="inline-state" aria-live="polite">
        <span className="spinner" aria-hidden="true" />
        正在加载离线知识图谱
      </div>
    );
  }

  if (!overview) {
    return (
      <section className="index-status-panel">
        <h2>知识图谱不可用</h2>
        <p>{message ?? "请检查 Backend 状态"}</p>
        <button className="secondary-button" onClick={() => loadOverview()}>
          重试
        </button>
      </section>
    );
  }

  return (
    <div className="graph-admin-page">
      {message ? <p className="generation-message" role="status">{message}</p> : null}

      <section className="index-status-panel">
        <header>
          <div>
            <h2>知识图谱版本</h2>
            <p>离线构建不会改变默认混合检索；候选就绪后必须由管理员显式发布。</p>
          </div>
          <span className={overview.extraction.enabled ? "generation-status ready" : "generation-status"}>
            抽取模型 {overview.extraction.enabled ? "已配置" : "未启用"}
          </span>
        </header>

        <dl className="index-metrics graph-metrics">
          <div><dt>当前生效代次</dt><dd>{overview.activeGeneration ?? "未发布"}</dd></div>
          <div><dt>抽取模型</dt><dd>{overview.extraction.model || "未配置"}</dd></div>
          <div><dt>模型版本</dt><dd>{overview.extraction.revision || "暂无数据"}</dd></div>
          <div><dt>Prompt</dt><dd>{overview.extraction.promptVersion}</dd></div>
          <div><dt>Schema</dt><dd>{overview.extraction.schemaVersion}</dd></div>
        </dl>

        <section className="graph-rebuild-requests">
          <div className="section-heading">
            <div>
              <h3>Revision / ACL 重建申请</h3>
              <p>当前生效图谱保持不可变；下一次候选版本会吸收仍有效的申请。</p>
            </div>
            <span className="generation-status">
              {rebuildError
                ? "读取失败"
                : `${rebuildRequests.filter((item) => ACTIVE_REBUILD_STATES.has(item.state)).length} 进行中`}
            </span>
          </div>
          {rebuildError ? (
            <div className="table-state error-state">
              <p>重建申请读取失败，现有数量未被当作 0。</p>
              <button type="button" className="secondary-button" onClick={() => loadOverview()}>
                重试
              </button>
            </div>
          ) : rebuildRequests.length ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>文档</th>
                    <th>目标快照</th>
                    <th>原因</th>
                    <th>状态</th>
                    <th>Graph 阶段</th>
                    <th>Global 阶段</th>
                    <th>申请 / 完成</th>
                  </tr>
                </thead>
                <tbody>
                  {rebuildRequests.slice(0, 10).map((request) => (
                    <tr key={request.id}>
                      <td><Link to={`/documents/${request.documentId}`}>{request.documentTitle}</Link></td>
                      <td>R{request.targetRevisionNumber} · ACL v{request.targetAclVersion}</td>
                      <td>{request.reason}</td>
                      <td><span className={statusClass(request.state)}>{request.state}</span></td>
                      <td>
                        <strong>
                          {formatGeneration(request.sourceGraphGeneration)}
                          {" → "}
                          {request.candidateGraphGeneration === null
                            ? "待候选"
                            : formatGeneration(request.candidateGraphGeneration)}
                        </strong>
                        <br />
                        <small>{graphStageLabel(request)}</small>
                      </td>
                      <td>
                        {request.globalRebuildRequired ? (
                          <>
                            <strong>
                              {formatGeneration(request.sourceGlobalGeneration)}
                              {" → "}
                              {request.candidateGlobalGeneration === null
                                ? "待候选"
                                : formatGeneration(request.candidateGlobalGeneration)}
                            </strong>
                            <br />
                            <small>{globalStageLabel(request)}</small>
                          </>
                        ) : (
                          <>
                            <strong>无需重建</strong>
                            <br />
                            <small>{globalStageLabel(request)}</small>
                          </>
                        )}
                      </td>
                      <td>
                        <span>申请 {formatTime(request.requestedAt)}</span>
                        <br />
                        <small>完成 {formatTime(request.completedAt)}</small>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <p className="artifact-empty">当前没有 Revision 或 ACL stale 申请。</p>}
        </section>

        <form className="generation-form" onSubmit={startBuild}>
          <label>
            GraphConfig
            <select
              value={buildDraft.graphConfigVersion}
              onChange={(event) => setBuildDraft((current) => ({
                ...current,
                graphConfigVersion: event.target.value,
              }))}
              required
            >
              <option value="">选择配置</option>
              {overview.configs.map((config) => (
                <option key={config.version} value={config.version}>
                  {config.version}
                </option>
              ))}
            </select>
          </label>
          <label>
            构建理由
            <input
              value={buildDraft.reason}
              onChange={(event) => setBuildDraft((current) => ({
                ...current,
                reason: event.target.value,
              }))}
              placeholder="记录本次图谱构建目的"
              required
            />
          </label>
          <label>
            确认
            <input
              value={buildDraft.confirmation}
              onChange={(event) => setBuildDraft((current) => ({
                ...current,
                confirmation: event.target.value,
              }))}
              placeholder="输入 BUILD"
              required
            />
          </label>
          <button
            className="primary-button"
            disabled={Boolean(action) || !overview.configs.length}
          >
            {action === "build" ? "提交中" : "构建 Generation"}
          </button>
        </form>

        <div className="generation-release-form">
          <label>
            发布 / 回滚理由
            <input
              value={releaseReason}
              onChange={(event) => setReleaseReason(event.target.value)}
              placeholder="审计理由"
            />
          </label>
          <label>
            确认字段
            <input
              value={releaseConfirmation}
              onChange={(event) => setReleaseConfirmation(event.target.value)}
              placeholder="PUBLISH 或 ROLLBACK"
            />
          </label>
          <p>发布要求 READY 且追平当前 Revision/ACL；回滚同样实时校验。</p>
        </div>

        <div className="table-wrap generation-table-wrap">
          <table className="generation-table graph-generation-table">
            <thead>
              <tr>
                <th>Generation</th>
                <th>状态 / 进度</th>
                <th>知识事实</th>
                <th>缓存</th>
                <th>发布闭包</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {overview.generations.map((generation) => (
                <tr key={generation.id}>
                  <td>
                    <strong>Generation {generation.graphGeneration}</strong>
                    <small>{generation.graphConfigVersion}</small>
                    <small>{formatTime(generation.createdAt)}</small>
                  </td>
                  <td>
                    <span className={statusClass(generation.status)}>
                      {generation.status}
                    </span>
                    <progress
                      max={Math.max(1, generation.expectedDocumentCount)}
                      value={generation.projectedDocumentCount}
                    />
                    <small>
                      {generation.projectedDocumentCount} / {generation.expectedDocumentCount} 文档
                    </small>
                    {generation.failureCode ? (
                      <small className="generation-failure">
                        {generation.failureCode} · {generation.failureReason}
                      </small>
                    ) : null}
                  </td>
                  <td>
                    {generation.entityCount} 实体 · {generation.relationshipCount} 关系
                    <small>
                      {generation.mentionCount} Mention · {generation.relationshipEvidenceCount} Evidence
                    </small>
                    <small>
                      {generation.communityCount} Community · {generation.communityClaimCount} Claim
                    </small>
                  </td>
                  <td>
                    {(generation.cacheHitRate * 100).toFixed(1)}%
                    <small>{generation.cacheHitCount} 命中 · {generation.modelCallCount} 调用</small>
                  </td>
                  <td>
                    {generation.closure && generation.recovery ? (
                      <ProjectionClosureSummary
                        closure={generation.closure}
                        recovery={generation.recovery}
                      />
                    ) : generation.caughtUp ? "已追平" : "未追平"}
                  </td>
                  <td className="row-actions">
                    {generation.status === "READY" ? (
                      <button
                        type="button"
                        onClick={() => release(generation, "publish")}
                        disabled={Boolean(action)}
                      >
                        发布
                      </button>
                    ) : null}
                    {generation.status === "RETIRED" ? (
                      <button
                        type="button"
                        onClick={() => release(generation, "rollback")}
                        disabled={Boolean(action)}
                      >
                        回滚
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
              {!overview.generations.length ? (
                <tr><td colSpan={6}>尚未创建 Graph Generation</td></tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </section>

      {retrieval ? (
        <section className="index-status-panel">
          <header>
            <div>
              <h2>Local GraphRAG 检索配置</h2>
              <p>Profile 不可修改；发布只切换预算与融合参数，不改写已发布图谱。</p>
            </div>
            <span className="generation-status active">
              ACTIVE {retrieval.currentPublication.profileVersion}
            </span>
          </header>
          <dl className="index-metrics graph-metrics">
            <div>
              <dt>Graph Generation</dt>
              <dd>{retrieval.activeGraphGeneration ?? "未发布"}</dd>
            </div>
            <div>
              <dt>Publication</dt>
              <dd>#{retrieval.currentPublication.publicationEventId}</dd>
            </div>
            <div>
              <dt>发布时间</dt>
              <dd>{formatTime(retrieval.currentPublication.publishedAt)}</dd>
            </div>
          </dl>
          <div className="generation-release-form">
            <label>
              发布 / 回滚理由
              <input
                value={retrievalReleaseReason}
                onChange={(event) => setRetrievalReleaseReason(event.target.value)}
                placeholder="审计理由"
              />
            </label>
            <label>
              确认字段
              <input
                value={retrievalReleaseConfirmation}
                onChange={(event) => setRetrievalReleaseConfirmation(event.target.value)}
                placeholder="PUBLISH 或 ROLLBACK"
              />
            </label>
          </div>
          <div className="graph-config-list">
            {retrieval.profiles.map((profile) => {
              const active = profile.version
                === retrieval.currentPublication.profileVersion;
              return (
                <article key={profile.version}>
                  <header>
                    <strong>{profile.version}</strong>
                    <span className={active ? "generation-status active" : "generation-status ready"}>
                      {active ? "ACTIVE" : "IMMUTABLE"}
                    </span>
                  </header>
                  <p>
                    {profile.seedLimit} seeds · {profile.maxHops} hops ·{" "}
                    {profile.entityLimit} entities · {profile.edgeLimit} edges
                  </p>
                  <small>
                    Child {profile.graphChildLimit} · weight {profile.graphWeight}
                    {" · "}context {profile.graphContextTokenBudget} tokens / {profile.graphContextPercent}%
                    {" · "}timeout {profile.statementTimeoutMs} ms
                  </small>
                  <small>{profile.reason}</small>
                  {!active ? (
                    <div className="row-actions">
                      <button
                        type="button"
                        disabled={Boolean(action)}
                        onClick={() => void releaseRetrievalProfile(profile.version, "publish")}
                      >
                        发布
                      </button>
                      <button
                        type="button"
                        disabled={Boolean(action)}
                        onClick={() => void releaseRetrievalProfile(profile.version, "rollback")}
                      >
                        回滚
                      </button>
                    </div>
                  ) : null}
                </article>
              );
            })}
          </div>
          <details className="profile-create">
            <summary>创建 GraphRetrievalProfile</summary>
            <form className="graph-config-form" onSubmit={createRetrievalProfile}>
              <label>版本<input value={retrievalDraft.version} onChange={(event) => setRetrievalDraft((current) => ({ ...current, version: event.target.value }))} required /></label>
              <label>种子数<input type="number" min={1} max={5} value={retrievalDraft.seedLimit} onChange={(event) => setRetrievalDraft((current) => ({ ...current, seedLimit: Number(event.target.value) }))} required /></label>
              <label>跳数<input type="number" min={1} max={2} value={retrievalDraft.maxHops} onChange={(event) => setRetrievalDraft((current) => ({ ...current, maxHops: Number(event.target.value) }))} required /></label>
              <label>实体上限<input type="number" min={1} max={20} value={retrievalDraft.entityLimit} onChange={(event) => setRetrievalDraft((current) => ({ ...current, entityLimit: Number(event.target.value) }))} required /></label>
              <label>边上限<input type="number" min={1} max={40} value={retrievalDraft.edgeLimit} onChange={(event) => setRetrievalDraft((current) => ({ ...current, edgeLimit: Number(event.target.value) }))} required /></label>
              <label>Graph Child<input type="number" min={1} max={30} value={retrievalDraft.graphChildLimit} onChange={(event) => setRetrievalDraft((current) => ({ ...current, graphChildLimit: Number(event.target.value) }))} required /></label>
              <label>融合权重<input type="number" min={0.001} max={4} step={0.001} value={retrievalDraft.graphWeight} onChange={(event) => setRetrievalDraft((current) => ({ ...current, graphWeight: Number(event.target.value) }))} required /></label>
              <label>Graph Token<input type="number" min={0} max={900} value={retrievalDraft.graphContextTokenBudget} onChange={(event) => setRetrievalDraft((current) => ({ ...current, graphContextTokenBudget: Number(event.target.value) }))} required /></label>
              <label>上下文占比 %<input type="number" min={0} max={15} value={retrievalDraft.graphContextPercent} onChange={(event) => setRetrievalDraft((current) => ({ ...current, graphContextPercent: Number(event.target.value) }))} required /></label>
              <label>SQL 超时 ms<input type="number" min={50} max={1000} value={retrievalDraft.statementTimeoutMs} onChange={(event) => setRetrievalDraft((current) => ({ ...current, statementTimeoutMs: Number(event.target.value) }))} required /></label>
              <label>理由<input value={retrievalDraft.reason} onChange={(event) => setRetrievalDraft((current) => ({ ...current, reason: event.target.value }))} required /></label>
              <label>确认<input value={retrievalDraft.confirmation} onChange={(event) => setRetrievalDraft((current) => ({ ...current, confirmation: event.target.value }))} placeholder="CREATE" required /></label>
              <button className="primary-button" disabled={Boolean(action)}>创建 Profile</button>
            </form>
          </details>
        </section>
      ) : null}

      <section className="index-status-panel">
        <header>
          <div>
            <h2>不可变 GraphConfig</h2>
            <p>模型、Prompt、Schema、归一化、消歧规则与 Leiden 参数共同决定 Generation。</p>
          </div>
        </header>
        <div className="graph-config-list">
          {overview.configs.map((config) => (
            <article key={config.version}>
              <header>
                <strong>{config.version}</strong>
                <span className={config.runtimeCompatible ? "generation-status ready" : "generation-status failed"}>
                  {config.runtimeCompatible ? "运行时匹配" : "运行时不匹配"}
                </span>
              </header>
              <p>{config.extractionModel} · {config.extractionRevision}</p>
              <small>
                {config.resolutionRuleSetVersion} · {config.communityAlgorithm} {config.communityAlgorithmVersion}
              </small>
              <small>{config.reason}</small>
            </article>
          ))}
        </div>
        <details className="profile-create">
          <summary>创建 GraphConfig</summary>
          <form className="graph-config-form" onSubmit={createConfig}>
            <label>版本<input value={configDraft.version} onChange={(event) => setConfigDraft((current) => ({ ...current, version: event.target.value }))} required /></label>
            <label>抽取模型<input value={configDraft.extractionModel} onChange={(event) => setConfigDraft((current) => ({ ...current, extractionModel: event.target.value }))} required /></label>
            <label>模型 Revision<input value={configDraft.extractionRevision} onChange={(event) => setConfigDraft((current) => ({ ...current, extractionRevision: event.target.value }))} required /></label>
            <label>理由<input value={configDraft.reason} onChange={(event) => setConfigDraft((current) => ({ ...current, reason: event.target.value }))} required /></label>
            <label>确认<input value={configDraft.confirmation} onChange={(event) => setConfigDraft((current) => ({ ...current, confirmation: event.target.value }))} placeholder="CREATE" required /></label>
            <button className="primary-button" disabled={Boolean(action)}>创建配置</button>
          </form>
        </details>
      </section>

      <section className="index-status-panel">
        <header className="graph-browser-header">
          <div>
            <h2>实体、关系与 Community</h2>
            <p>这里只展示仍属于当前发布 Revision 的 SourceSpan 锚点。</p>
          </div>
          <label>
            浏览 Generation
            <select
              value={selectedGeneration ?? ""}
              onChange={(event) => {
                setEntityPageNumber(0);
                setCommunityPageNumber(0);
                setSelectedGeneration(
                  event.target.value ? Number(event.target.value) : null,
                );
                if (graphView && event.target.value) {
                  updateGraphLocation({ generation: event.target.value });
                }
              }}
            >
              {overview.generations
                .filter((item) => ["READY", "ACTIVE", "RETIRED"].includes(item.status))
                .map((item) => (
                  <option key={item.id} value={item.graphGeneration}>
                    {item.graphGeneration} · {item.status}
                  </option>
                ))}
            </select>
          </label>
        </header>

        {selectedManifest ? (
          <p className="graph-generation-context">
            Generation {selectedManifest.graphGeneration} · {selectedManifest.graphConfigVersion} · {selectedManifest.status}
          </p>
        ) : null}

        <div className="graph-view-switch" aria-label="图谱浏览方式">
          <button
            type="button"
            className={!graphView ? "active" : ""}
            aria-pressed={!graphView}
            onClick={showList}
          >列表</button>
          <button
            type="button"
            className={graphView ? "active" : ""}
            aria-pressed={graphView}
            disabled={!graphRootValid}
            onClick={() => updateGraphLocation({ view: "graph" })}
            title={graphRootValid ? "查看最近选择的局部关系图" : "请先从实体或 Community 选择“在关系图中查看”"}
          >关系图</button>
        </div>

        {graphView && graphRootValid && graphRootType && graphRootId ? (
          <GraphTopologyPanel
            generation={graphGeneration}
            rootType={graphRootType}
            rootId={graphRootId}
            hops={graphHops}
            onHopsChange={(nextHops) => updateGraphLocation({
              hops: String(nextHops),
            })}
            onBack={showList}
          />
        ) : graphView ? (
          <div className="graph-topology-empty">
            <strong>请选择关系图起点</strong>
            <p>返回列表，从一个实体或 Community 打开受限局部关系图。</p>
            <button type="button" className="secondary-button" onClick={showList}>
              返回列表
            </button>
          </div>
        ) : (
          <>

        <form
          className="graph-search-form"
          onSubmit={(event) => {
            event.preventDefault();
            setEntityPageNumber(0);
            setAppliedQuery(query.trim());
          }}
        >
          <label>
            实体名称、别名或类型
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="例如：Context Generator"
            />
          </label>
          <button className="secondary-button">筛选实体</button>
        </form>

        {browsing ? (
          <div className="inline-state">
            <span className="spinner" aria-hidden="true" />正在加载图谱事实
          </div>
        ) : (
          <div className="graph-browser-grid">
            <section>
              <h3>实体 <span>{entityPage?.total ?? 0}</span></h3>
              <div className="graph-item-list">
                {entityPage?.items.map((entity) => (
                  <article className="graph-item-row" key={entity.id}>
                    <button
                      type="button"
                      onClick={() => showEntity(entity.id)}
                    >
                      <strong>{entity.canonicalName}</strong>
                      <span>{entity.entityType}</span>
                      <small>{entity.mentionCount} Mention · {entity.relationshipCount} 关系</small>
                    </button>
                    <button
                      type="button"
                      className="text-button"
                      onClick={() => openGraph("ENTITY", entity.id)}
                    >在关系图中查看</button>
                  </article>
                ))}
                {!entityPage?.items.length ? <p>暂无可见实体</p> : null}
              </div>
              <div className="pagination">
                <button
                  type="button"
                  disabled={!entityPage || entityPage.page === 0}
                  onClick={() => setEntityPageNumber((page) => page - 1)}
                >
                  上一页
                </button>
                <span>第 {(entityPage?.page ?? 0) + 1} 页</span>
                <button
                  type="button"
                  disabled={
                    !entityPage
                    || (entityPage.page + 1) * entityPage.size
                      >= entityPage.total
                  }
                  onClick={() => setEntityPageNumber((page) => page + 1)}
                >
                  下一页
                </button>
              </div>
            </section>
            <section>
              <h3>Community <span>{communityPage?.total ?? 0}</span></h3>
              <div className="graph-item-list">
                {communityPage?.items.map((community) => (
                  <article className="graph-item-row" key={community.id}>
                    <button
                      type="button"
                      onClick={() => showCommunity(community.id)}
                    >
                      <strong>{community.title}</strong>
                      <span>Community {community.communityKey}</span>
                      <small>{community.entityCount} 实体 · {community.claimCount} Claim</small>
                    </button>
                    <button
                      type="button"
                      className="text-button"
                      onClick={() => openGraph("COMMUNITY", community.id)}
                    >在关系图中查看</button>
                  </article>
                ))}
                {!communityPage?.items.length ? <p>暂无可见 Community</p> : null}
              </div>
              <div className="pagination">
                <button
                  type="button"
                  disabled={!communityPage || communityPage.page === 0}
                  onClick={() => setCommunityPageNumber((page) => page - 1)}
                >
                  上一页
                </button>
                <span>第 {(communityPage?.page ?? 0) + 1} 页</span>
                <button
                  type="button"
                  disabled={
                    !communityPage
                    || (communityPage.page + 1) * communityPage.size
                      >= communityPage.total
                  }
                  onClick={() => setCommunityPageNumber((page) => page + 1)}
                >
                  下一页
                </button>
              </div>
            </section>
          </div>
        )}

        {entityDetail ? (
          <GraphEntityDetailPanel
            detail={entityDetail}
            onClose={() => setEntityDetail(null)}
          />
        ) : null}

        {communityDetail ? (
          <article className="graph-detail">
            <header>
              <div>
                <span>Community {communityDetail.community.communityKey}</span>
                <h3>{communityDetail.community.title}</h3>
                <p>{communityDetail.community.summary}</p>
              </div>
              <button className="text-button" onClick={() => setCommunityDetail(null)}>关闭</button>
            </header>
            <div className="graph-detail-columns">
              <section>
                <h4>成员实体</h4>
                <ul className="graph-member-list">
                  {communityDetail.entities.map((entity) => (
                    <li key={entity.id}>{entity.canonicalName} <span>{entity.entityType}</span></li>
                  ))}
                </ul>
              </section>
              <section>
                <h4>Evidence-backed Claims</h4>
                {communityDetail.claims.map((claim) => (
                  <blockquote className="graph-claim" key={claim.id}>
                    {claim.claimText}
                    <footer>
                      {claim.evidence.documentTitle} ·{" "}
                      <SourceLocation source={claim.evidence} linkToSource={false} />
                      {" · "}
                      <Link to={`/chunks/${claim.evidence.childChunkId}`}>查看 Child</Link>
                    </footer>
                  </blockquote>
                ))}
              </section>
            </div>
          </article>
        ) : null}
          </>
        )}
      </section>

      <GlobalGraphPanel />

      <section className="index-status-panel graph-candidate-workbench">
        <header>
          <div>
            <h2>实体治理候选</h2>
            <p>用当前图谱事实发现疑似重复与疑似误合并；候选只是核对线索，系统不会自动修改图谱。</p>
          </div>
          {candidatePage?.snapshot ? (
            <span className={statusClass(candidatePage.snapshot.status)}>
              G{candidatePage.snapshot.graphGeneration} · {candidatePage.snapshot.status === "READY" ? "可核对" : "已过期"}
            </span>
          ) : null}
        </header>

        <div className="graph-candidate-controls">
          <label>
            事实版本
            <select
              value={ruleGeneration ?? ""}
              onChange={(event) => setRuleGeneration(
                event.target.value ? Number(event.target.value) : null,
              )}
            >
              <option value="">没有可用 Generation</option>
              {overview.generations
                .filter((item) => item.status === "ACTIVE" || item.status === "READY")
                .map((item) => (
                  <option key={item.graphGeneration} value={item.graphGeneration}>
                    G{item.graphGeneration} · {item.status} · {item.graphConfigVersion}
                  </option>
                ))}
            </select>
          </label>
          <label>
            候选类型
            <select value={candidateType} onChange={(event) => setCandidateType(event.target.value)}>
              <option value="">全部类型</option>
              <option value="SUSPECTED_DUPLICATE">疑似重复</option>
              <option value="SUSPECTED_MERGE">疑似误合并</option>
            </select>
          </label>
          <label>
            状态
            <select value={candidateStatus} onChange={(event) => setCandidateStatus(event.target.value)}>
              <option value="ACTIVE">待核对</option>
              <option value="IGNORED">已忽略</option>
              <option value="STALE">事实已过期</option>
              <option value="">全部状态</option>
            </select>
          </label>
          <label>
            解释信号
            <select value={candidateSignal} onChange={(event) => setCandidateSignal(event.target.value)}>
              <option value="">全部信号</option>
              <option value="SAME_NORMALIZED_NAME">规范化名称相同</option>
              <option value="ALIAS_OVERLAP">Alias 重叠</option>
              <option value="STRING_SIMILARITY">名称相似</option>
              <option value="MENTION_COOCCURRENCE">原文共同出现</option>
              <option value="NEIGHBOR_OVERLAP">邻接关系重叠</option>
              <option value="SOURCE_CLUSTER_SEPARATION">来源簇分离</option>
              <option value="TYPE_HINT_CONFLICT">类型提示冲突</option>
            </select>
          </label>
          <form
            className="graph-candidate-search"
            onSubmit={(event) => {
              event.preventDefault();
              setCandidateAppliedQuery(candidateQuery.trim());
            }}
          >
            <label>
              实体名称
              <input
                value={candidateQuery}
                onChange={(event) => setCandidateQuery(event.target.value)}
                placeholder="搜索候选中的实体"
              />
            </label>
            <button className="secondary-button" disabled={candidateLoading}>筛选</button>
          </form>
        </div>

        {candidatePage?.snapshot ? (
          <div className="graph-candidate-snapshot">
            <div>
              <strong>{candidatePage.snapshot.algorithmVersion}</strong>
              <span>
                {candidatePage.snapshot.duplicateCandidateCount} 条疑似重复 · {candidatePage.snapshot.splitCandidateCount} 条疑似误合并
              </span>
              <small>生成于 {formatTime(candidatePage.snapshot.createdAt)} · GraphConfig {candidatePage.snapshot.graphConfigVersion}</small>
            </div>
            {candidatePage.snapshot.status === "STALE" ? (
              <p className="graph-resolution-warning">
                图谱事实已变化，旧候选只保留审计状态，Evidence 不再展示。请先完成 Graph Projection 追平，再刷新候选。
                <small>{candidatePage.snapshot.staleReason}</small>
              </p>
            ) : null}
          </div>
        ) : (
          <div className="empty-state compact-empty-state">
            <strong>当前 Generation 还没有候选快照</strong>
            <p>刷新只生成可删除、可重建的治理线索，不会创建规则、构建或发布图谱。</p>
          </div>
        )}

        <details className="graph-candidate-refresh">
          <summary>刷新候选快照</summary>
          <form onSubmit={refreshResolutionCandidates}>
            <p><strong>会：</strong>读取当前有效 Entity、Alias、Mention 与邻接事实并生成候选。<strong>不会：</strong>修改 GraphConfig、规则、Generation、Publication 或在线回答。</p>
            <label>
              刷新理由（8–500 字）
              <input
                value={candidateRefreshDraft.reason}
                minLength={8}
                maxLength={500}
                onChange={(event) => setCandidateRefreshDraft((current) => ({ ...current, reason: event.target.value }))}
                placeholder="例如：核对当前 Generation 的实体重复与误合并线索"
                required
              />
            </label>
            <label>
              确认输入 REFRESH_RESOLUTION_CANDIDATES
              <input
                value={candidateRefreshDraft.confirmation}
                onChange={(event) => setCandidateRefreshDraft((current) => ({ ...current, confirmation: event.target.value }))}
                placeholder="REFRESH_RESOLUTION_CANDIDATES"
                required
              />
            </label>
            <button className="secondary-button" disabled={Boolean(action) || ruleGeneration === null}>
              {action === "candidate-refresh" ? "正在刷新" : "创建候选快照"}
            </button>
          </form>
        </details>

        {candidateLoading && !candidatePage ? (
          <div className="inline-state"><span className="spinner" aria-hidden="true" />正在加载治理候选</div>
        ) : (
          <div className="graph-candidate-list" aria-live="polite">
            {candidatePage?.items.map((candidate) => (
              <article key={candidate.id} className={candidate.status === "STALE" ? "stale" : ""}>
                <header>
                  <div>
                    <span className={`candidate-type ${candidate.candidateType.toLowerCase()}`}>
                      {candidateTypeLabel(candidate.candidateType)}
                    </span>
                    <strong>{candidate.entities.map((entity) => entity.canonicalName).join(" ↔ ")}</strong>
                    <small>{candidate.entities.map((entity) => entity.entityType).join(" · ")}</small>
                  </div>
                  <span className={statusClass(candidate.status)}>{candidateStatusLabel(candidate.status)}</span>
                </header>
                <div className="graph-candidate-signals">
                  {candidate.signals.map((signal) => (
                    <span key={signal.code} title={signal.explanation}>
                      {candidateSignalLabel(signal.code)}
                    </span>
                  ))}
                </div>
                <p>{candidate.evidenceCount} 条当前 Evidence · {candidate.sourceDocumentCount} 份来源文档</p>
                <div className="row-actions">
                  <button type="button" className="text-button" onClick={() => void showCandidate(candidate.id)}>核对证据</button>
                  {candidate.status === "ACTIVE" ? (
                    <button type="button" className="secondary-button" onClick={() => startRuleFromCandidate(candidate)}>
                      发起{candidate.suggestedAction === "MERGE" ? "合并" : "拆分"}
                    </button>
                  ) : null}
                </div>
              </article>
            ))}
            {candidatePage && !candidatePage.items.length ? (
              <div className="empty-state compact-empty-state">
                <strong>当前筛选没有候选</strong>
                <p>可调整类型、状态、信号或实体名称；候选少不代表图谱构建失败。</p>
              </div>
            ) : null}
          </div>
        )}
        {candidatePage?.nextCursor ? (
          <button
            type="button"
            className="text-button"
            disabled={candidateLoading}
            onClick={() => loadCandidates(true, candidatePage.nextCursor)}
          >加载更多候选</button>
        ) : null}

        {candidateDetail ? (
          <article className="graph-candidate-detail">
            <header>
              <div>
                <span>{candidateTypeLabel(candidateDetail.candidate.candidateType)}</span>
                <h3>{candidateDetail.candidate.entities.map((entity) => entity.canonicalName).join(" ↔ ")}</h3>
                <p>以下内容均已重新复核当前 Revision、ACL 与 SourceSpan。</p>
              </div>
              <button type="button" className="text-button" onClick={() => setCandidateDetail(null)}>关闭</button>
            </header>
            {candidateDetail.candidate.status === "STALE" ? (
              <p className="graph-resolution-warning">事实已过期，旧 Evidence 正文已停止返回。请刷新候选后重新核对。</p>
            ) : (
              <div className="graph-candidate-detail-grid">
                <section>
                  <h4>原文 Evidence</h4>
                  {candidateDetail.evidence.map((evidence) => (
                    <blockquote key={`${evidence.anchorType}-${evidence.anchorId}`}>
                      {evidence.excerpt}
                      <footer>
                        {evidence.documentTitle} · <SourceLocation source={evidence} linkToSource={false} /> · <Link to={`/chunks/${evidence.childChunkId}`}>查看 Child</Link>
                      </footer>
                    </blockquote>
                  ))}
                  {!candidateDetail.evidence.length ? <p>没有仍然有效的 Evidence。</p> : null}
                </section>
                <section>
                  <h4>邻接核对</h4>
                  <ul>
                    {candidateDetail.neighbors.map((neighbor) => (
                      <li key={`${neighbor.entityId}-${neighbor.neighborId}`}>
                        <strong>{neighbor.entityName}</strong> → {neighbor.neighborName}
                        <span>{neighbor.shared ? "共享邻居" : "分离邻居"} · {neighbor.evidenceCount} Evidence</span>
                      </li>
                    ))}
                  </ul>
                  {!candidateDetail.neighbors.length ? <p>没有可见邻接关系。</p> : null}
                </section>
              </div>
            )}
            <div className="graph-candidate-state-actions">
              <label>
                处理理由（8–500 字）
                <input
                  value={candidateStateReason}
                  minLength={8}
                  maxLength={500}
                  onChange={(event) => setCandidateStateReason(event.target.value)}
                  placeholder="记录为什么忽略或恢复这条候选"
                />
              </label>
              {candidateDetail.candidate.status === "ACTIVE" ? (
                <button type="button" className="secondary-button" disabled={Boolean(action)} onClick={() => void changeCandidateState(candidateDetail.candidate, "IGNORE")}>忽略候选</button>
              ) : candidateDetail.candidate.status === "IGNORED" ? (
                <button type="button" className="secondary-button" disabled={Boolean(action)} onClick={() => void changeCandidateState(candidateDetail.candidate, "RESTORE")}>恢复候选</button>
              ) : null}
            </div>
          </article>
        ) : null}
      </section>

      <section className="index-status-panel graph-proposal-workbench">
        <header>
          <div>
            <h2>待生效规则</h2>
            <p>Proposal 可以修订或撤回；只有实时预检后物化，才会创建不可变 Rule Set 与 GraphConfig。</p>
          </div>
          <label>
            状态
            <select value={proposalStatus} onChange={(event) => setProposalStatus(event.target.value)}>
              <option value="">全部</option>
              <option value="READY">待物化</option>
              <option value="CONFLICTED">存在冲突</option>
              <option value="STALE">事实已过期</option>
              <option value="DRAFT">草案</option>
              <option value="MATERIALIZED">已物化</option>
              <option value="APPLIED">已被构建吸收</option>
              <option value="WITHDRAWN">已撤回</option>
            </select>
          </label>
        </header>
        {proposalLoading && !proposalPage ? (
          <div className="inline-state"><span className="spinner" aria-hidden="true" />正在加载待生效规则</div>
        ) : (
          <div className="graph-proposal-list">
            {proposalPage?.items.map((proposal) => (
              <article key={proposal.id} className={proposal.status.toLowerCase()}>
                <header>
                  <div>
                    <span className={statusClass(proposal.status)}>{proposalStatusLabel(proposal.status)}</span>
                    <strong>{proposal.action === "MERGE" ? "合并" : "拆分"} · {proposal.targetCanonicalName}</strong>
                    <small>Revision {proposal.currentRevision} · G{proposal.baseGraphGeneration} · {proposal.baseGraphConfigVersion}</small>
                  </div>
                  <span>{formatTime(proposal.updatedAt)}</span>
                </header>
                <p>{proposal.entities.map((entity) => entity.canonicalName).join("、")}</p>
                <small>{proposal.nextStep}</small>
                {proposal.conflicts.length ? (
                  <div className="graph-proposal-conflicts">
                    {proposal.conflicts.map((conflict) => (
                      <p key={`${conflict.conflictingProposalId}-${conflict.code}`}>
                        {conflict.message}
                      </p>
                    ))}
                  </div>
                ) : null}
                <div className="row-actions">
                  <button type="button" className="text-button" onClick={() => void showProposal(proposal.id)}>查看历史与影响</button>
                  {["DRAFT", "READY", "CONFLICTED", "STALE"].includes(proposal.status) ? (
                    <button type="button" className="secondary-button" onClick={() => editProposal(proposal)}>修订或预检</button>
                  ) : (
                    <span className="subtle">不可原地编辑；需要纠正时创建新 Proposal</span>
                  )}
                </div>
              </article>
            ))}
            {proposalPage && !proposalPage.items.length ? (
              <div className="empty-state compact-empty-state">
                <strong>当前没有符合筛选的 Proposal</strong>
                <p>可从治理候选发起，或在下方规则草案中保存为待生效规则。</p>
              </div>
            ) : null}
          </div>
        )}
        {proposalDetail ? (
          <article className="graph-proposal-detail">
            <header>
              <div>
                <span>{proposalStatusLabel(proposalDetail.proposal.status)}</span>
                <h3>{proposalDetail.proposal.targetCanonicalName}</h3>
                <p>{proposalDetail.proposal.nextStep}</p>
              </div>
              <button type="button" className="text-button" onClick={() => setProposalDetail(null)}>关闭</button>
            </header>
            <dl className="graph-proposal-impact">
              <div><dt>关系</dt><dd>{proposalDetail.proposal.impact.relationshipCount}</dd></div>
              <div><dt>SourceSpan</dt><dd>{proposalDetail.proposal.impact.sourceSpanCount}</dd></div>
              <div><dt>文档</dt><dd>{proposalDetail.proposal.impact.documentCount}</dd></div>
              <div><dt>Community</dt><dd>{proposalDetail.proposal.impact.communityCount}</dd></div>
            </dl>
            {proposalDetail.proposal.conflicts.map((conflict) => (
              <p className="graph-resolution-blocker" key={`${conflict.conflictingProposalId}-${conflict.code}`}>
                {conflict.message}<small>冲突 Proposal：{conflict.conflictingProposalId}</small>
              </p>
            ))}
            <details>
              <summary>Revision 与事件历史</summary>
              <ol className="graph-proposal-history">
                {proposalDetail.revisions.map((revision) => (
                  <li key={revision.id}>
                    <strong>Revision {revision.revision} · {revision.action}</strong>
                    <span>{revision.targetCanonicalName} · {revision.entities.map((entity) => entity.canonicalName).join("、")}</span>
                    <small>{revision.createdBy} · {formatTime(revision.createdAt)} · {revision.reason}</small>
                  </li>
                ))}
              </ol>
              <ol className="graph-proposal-history">
                {proposalDetail.events.map((event) => (
                  <li key={event.id}>
                    <strong>{event.eventType} · {event.nextStatus}</strong>
                    <small>{formatTime(event.createdAt)} · {event.reason}</small>
                  </li>
                ))}
              </ol>
            </details>
            {["DRAFT", "READY", "CONFLICTED", "STALE"].includes(proposalDetail.proposal.status) ? (
              <div className="graph-proposal-governance-actions">
                <label>
                  操作理由（8–500 字）
                  <input value={proposalActionReason} onChange={(event) => setProposalActionReason(event.target.value)} />
                </label>
                <label>
                  撤回时输入 WITHDRAW_RESOLUTION_PROPOSAL
                  <input value={proposalConfirmation} onChange={(event) => setProposalConfirmation(event.target.value)} />
                </label>
                <button type="button" className="secondary-button danger" disabled={Boolean(action)} onClick={() => void withdrawProposal(proposalDetail.proposal)}>撤回 Proposal</button>
              </div>
            ) : (
              <p className="subtle">该 Proposal 已进入只读状态。纠错只能创建新的 Proposal，不会改写历史 GraphConfig。</p>
            )}
          </article>
        ) : null}
      </section>

      <section className="index-status-panel" ref={resolutionSection}>
        <header>
          <div>
            <h2>实体消歧规则</h2>
            <p>先选择并核对实体，再预览影响。最终判断仍由管理员确认。</p>
          </div>
        </header>
        <div className="graph-resolution-workspace">
          <section className="graph-resolution-selector" aria-labelledby="resolution-source-title">
            <div className="graph-resolution-step">
              <span>1</span>
              <div>
                <h3 id="resolution-source-title">选择来源实体</h3>
                <p>可按名称或 Alias 搜索，无需复制 UUID。</p>
              </div>
            </div>
            <label>
              事实版本
              <select
                value={ruleGeneration ?? ""}
                onChange={(event) => setRuleGeneration(
                  event.target.value ? Number(event.target.value) : null,
                )}
              >
                <option value="">没有可预检的 Generation</option>
                {overview.generations
                  .filter((item) => (
                    item.status === "ACTIVE" || item.status === "READY"
                  ))
                  .map((item) => (
                    <option
                      key={item.graphGeneration}
                      value={item.graphGeneration}
                    >
                      G{item.graphGeneration} · {item.status} · {item.graphConfigVersion}
                    </option>
                  ))}
              </select>
            </label>
            <form
              className="graph-resolution-search"
              onSubmit={(event) => {
                event.preventDefault();
                setRuleEntityCursor(null);
                void searchRuleEntities(false);
              }}
            >
              <label>
                名称或 Alias
                <input
                  value={ruleEntityQuery}
                  onChange={(event) => setRuleEntityQuery(event.target.value)}
                  placeholder="输入实体名称或别名"
                />
              </label>
              <label>
                类型（可选）
                <input
                  value={ruleEntityType}
                  onChange={(event) => setRuleEntityType(event.target.value)}
                  placeholder="例如 ORGANIZATION"
                />
              </label>
              <button
                className="secondary-button"
                disabled={ruleGeneration === null || ruleSearching}
              >
                {ruleSearching ? "搜索中" : "搜索实体"}
              </button>
            </form>
            {ruleEntityResults ? (
              <div className="graph-resolution-results" aria-live="polite">
                {ruleEntityResults.items.map((entity) => {
                  const selected = ruleEntities.some(
                    (item) => item.id === entity.id,
                  );
                  return (
                    <article key={entity.id}>
                      <div>
                        <strong>{entity.canonicalName}</strong>
                        <span>{entity.entityType}</span>
                        <small>
                          {entity.matchSource === "ALIAS" && entity.matchedAlias
                            ? `匹配 Alias：${entity.matchedAlias} · `
                            : ""}
                          {entity.mentionCount} Mention · {entity.relationshipCount} 关系
                        </small>
                      </div>
                      <button
                        type="button"
                        className="text-button"
                        disabled={selected}
                        onClick={() => addRuleEntity(entity)}
                      >{selected ? "已选择" : "选择"}</button>
                    </article>
                  );
                })}
                {!ruleEntityResults.items.length ? (
                  <p className="subtle">没有符合当前条件的可见实体。</p>
                ) : null}
                {ruleEntityCursor ? (
                  <button
                    type="button"
                    className="text-button"
                    disabled={ruleSearching}
                    onClick={() => void searchRuleEntities(true)}
                  >加载更多</button>
                ) : null}
              </div>
            ) : (
              <p className="subtle">搜索结果会在此显示；只返回当前有效 Evidence 支撑的实体。</p>
            )}
          </section>

          <form className="graph-rule-form" onSubmit={previewRule}>
            <div className="graph-resolution-step graph-wide-field">
              <span>2</span>
              <div>
                <h3>核对规则草案</h3>
                <p>{editingProposalId ? `正在修订 Proposal ${editingProposalId}` : ruleDraft.action === "MERGE" ? "选择 2–20 个实体合并为一个目标实体。" : "选择 1 个实体，并指定要拆出的 Alias。"}</p>
              </div>
            </div>
            <label>
              动作
              <select
                value={ruleDraft.action}
                onChange={(event) => changeRuleAction(
                  event.target.value as "MERGE" | "SPLIT",
                )}
              >
                <option value="MERGE">合并实体</option>
                <option value="SPLIT">拆分实体</option>
              </select>
            </label>
            <label>
              基础 GraphConfig
              <input value={ruleManifest?.graphConfigVersion ?? ""} readOnly />
            </label>
            <div className="graph-selected-entities graph-wide-field">
              <span>已选择 {ruleEntities.length} 个来源实体</span>
              <div>
                {ruleEntities.map((entity) => (
                  <article key={entity.id}>
                    <button
                      type="button"
                      className="graph-selected-entity"
                      onClick={() => showEntity(entity.id, ruleGeneration)}
                      title="打开实体 Evidence 详情"
                    >
                      <strong>{entity.canonicalName}</strong>
                      <small>{entity.entityType} · {entity.mentionCount} Mention</small>
                    </button>
                    <button
                      type="button"
                      className="graph-chip-remove"
                      aria-label={`移除 ${entity.canonicalName}`}
                      onClick={() => removeRuleEntity(entity.id)}
                    >×</button>
                  </article>
                ))}
                {!ruleEntities.length ? <p>请先从左侧搜索结果选择实体。</p> : null}
              </div>
            </div>
            {ruleDraft.action === "SPLIT" ? (
              <fieldset className="graph-alias-selector graph-wide-field">
                <legend>选择要拆出的 Alias</legend>
                {(ruleEntities[0]?.aliases ?? []).map((alias) => (
                  <label key={alias}>
                    <input
                      type="checkbox"
                      checked={ruleDraft.matchAliases.includes(alias)}
                      onChange={() => toggleRuleAlias(alias)}
                    />
                    <span>{alias}</span>
                  </label>
                ))}
                {ruleEntities.length === 1
                    && !(ruleEntities[0].aliases ?? []).length ? (
                  <p>当前实体没有可供拆分的有效 Alias。</p>
                ) : null}
              </fieldset>
            ) : null}
            <label>
              目标名称
              <input
                value={ruleDraft.targetCanonicalName}
                onChange={(event) => updateRuleDraft({
                  targetCanonicalName: event.target.value,
                })}
                required
              />
            </label>
            <label>
              目标类型
              <input
                value={ruleDraft.targetEntityType}
                onChange={(event) => updateRuleDraft({
                  targetEntityType: event.target.value,
                })}
                required
              />
            </label>
            <label>
              新配置版本
              <input
                value={ruleDraft.newConfigVersion}
                onChange={(event) => setRuleDraft((current) => ({
                  ...current,
                  newConfigVersion: event.target.value,
                }))}
                required
              />
            </label>
            <label className="graph-wide-field">
              审计理由（8–500 字）
              <textarea
                value={ruleDraft.reason}
                minLength={8}
                maxLength={500}
                onChange={(event) => setRuleDraft((current) => ({
                  ...current,
                  reason: event.target.value,
                }))}
                placeholder="说明为什么需要合并或拆分，以及已核对的 Evidence"
                required
              />
            </label>
            <div className="graph-rule-actions graph-wide-field">
              <button
                type="button"
                className="secondary-button"
                disabled={
                  Boolean(action)
                  || ruleGeneration === null
                  || ruleDraft.reason.trim().length < 8
                  || (ruleDraft.action === "MERGE" && ruleEntities.length < 2)
                  || (ruleDraft.action === "SPLIT" && (ruleEntities.length !== 1 || ruleDraft.matchAliases.length === 0))
                }
                onClick={() => void saveProposal()}
              >{action === "proposal-save" ? "正在保存" : editingProposalId ? "追加 Proposal Revision" : "保存为治理草案"}</button>
              {editingProposalId ? (
                <button type="button" className="text-button" onClick={resetProposalEditor}>退出 Proposal 修订</button>
              ) : null}
              <button
                className="primary-button"
                disabled={
                  Boolean(action)
                  || ruleGeneration === null
                  || (ruleDraft.action === "MERGE" && ruleEntities.length < 2)
                  || (ruleDraft.action === "SPLIT"
                    && (ruleEntities.length !== 1
                      || ruleDraft.matchAliases.length === 0))
                }
              >{action === "rule-preview" ? "正在核对" : "预览影响"}</button>
            </div>
          </form>
        </div>

        {rulePreview ? (
          <section className="graph-resolution-preview" aria-live="polite">
            <div className="graph-resolution-step">
              <span>3</span>
              <div>
                <h3>影响预览</h3>
                <p>基于 G{rulePreview.graphGeneration} · {rulePreview.graphStatus} 的当前事实计算。</p>
              </div>
            </div>
            <dl>
              <div><dt>Mention</dt><dd>{rulePreview.impact.mentionCount}</dd></div>
              <div><dt>SourceSpan</dt><dd>{rulePreview.impact.sourceSpanCount}</dd></div>
              <div><dt>关系</dt><dd>{rulePreview.impact.relationshipCount}</dd></div>
              <div><dt>关系 Evidence</dt><dd>{rulePreview.impact.relationshipEvidenceCount}</dd></div>
              <div><dt>Community</dt><dd>{rulePreview.impact.communityCount}</dd></div>
              <div><dt>文档</dt><dd>{rulePreview.impact.documentCount}</dd></div>
            </dl>
            <p className="graph-query-impact">
              下游查询范围：暂无法预估。{rulePreview.impact.queryImpactReason}
            </p>
            {rulePreview.warnings.map((warning) => (
              <p className="graph-resolution-warning" key={warning.code}>
                {warning.message}
                <small>技术代码：{warning.code}</small>
              </p>
            ))}
            {rulePreview.blockers.map((blocker) => (
              <p className="graph-resolution-blocker" key={blocker.code}>
                {blocker.message}
                <small>技术代码：{blocker.code}</small>
              </p>
            ))}
            {rulePreview.previewToken ? (
              <div className="graph-resolution-confirm">
                <p>
                  <strong>此操作会：</strong>创建不可变规则与 GraphConfig。
                  <strong> 不会：</strong>修改 ACTIVE Graph、启动构建或自动发布。
                </p>
                <label>
                  确认输入 {editingProposalId ? "MATERIALIZE_RESOLUTION_PROPOSAL" : "APPLY_NEXT_BUILD"}
                  <input
                    value={ruleDraft.confirmation}
                    onChange={(event) => setRuleDraft((current) => ({
                      ...current,
                      confirmation: event.target.value,
                    }))}
                    placeholder={editingProposalId ? "MATERIALIZE_RESOLUTION_PROPOSAL" : "APPLY_NEXT_BUILD"}
                  />
                </label>
                <button
                  type="button"
                  className="primary-button"
                  disabled={
                    Boolean(action)
                    || ruleDraft.confirmation.trim() !== (editingProposalId
                      ? "MATERIALIZE_RESOLUTION_PROPOSAL"
                      : "APPLY_NEXT_BUILD")
                  }
                  onClick={() => void (editingProposalId ? materializeProposal() : createRule())}
                >{action === "proposal-materialize" || action === "rule-create" ? "正在创建" : editingProposalId ? "物化当前 Proposal" : "创建下一代配置"}</button>
              </div>
            ) : (
              <p className="graph-resolution-blocked-summary">
                当前预览存在阻断项，修正草案后重新预检。
              </p>
            )}
          </section>
        ) : null}
      </section>
    </div>
  );
}
