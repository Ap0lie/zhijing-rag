import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";

import { ApiError, apiRequest } from "../api";
import { AdminTerm } from "../components/AdminTerm";
import { AdminValue } from "../components/AdminValue";
import { useAuth } from "../auth";
import { EmbeddingCachePanel } from "../components/EmbeddingCachePanel";
import { ProjectionClosureSummary } from "../components/ProjectionClosureSummary";
import {
  SourceLocation,
  sourceLocationText,
} from "../components/SourceLocation";
import type {
  Bm25DebugResponse,
  CreateRetrievalProfileRequest,
  DocumentVisibility,
  IndexGeneration,
  IndexGenerationsResponse,
  ModelServicesHealth,
  GraphMode,
  RetrievalConfiguration,
  RetrievalMode,
} from "../types";

type LoadState = "loading" | "ready" | "error";
type NumericProfileField = Exclude<keyof CreateRetrievalProfileRequest, "version" | "mode">;

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", {
  dateStyle: "medium",
  timeStyle: "medium",
});

const INITIAL_PROFILE: CreateRetrievalProfileRequest = {
  version: "",
  mode: "HYBRID",
  defaultPageSize: 20,
  maxPageSize: 50,
  bm25TopK: 50,
  vectorTopK: 50,
  rrfRankConstant: 60,
  rerankTopK: 30,
  evidenceTopK: 8,
  parentTokenBudget: 6000,
};

const PROFILE_NUMBER_FIELDS: {
  field: NumericProfileField;
  label: string;
  min: number;
  max: number;
}[] = [
  { field: "defaultPageSize", label: "默认分页", min: 1, max: 100 },
  { field: "maxPageSize", label: "最大分页", min: 1, max: 100 },
  { field: "bm25TopK", label: "BM25 TopK", min: 1, max: 200 },
  { field: "vectorTopK", label: "Vector TopK", min: 0, max: 200 },
  { field: "rrfRankConstant", label: "RRF k", min: 1, max: 1000 },
  { field: "rerankTopK", label: "Rerank TopK", min: 0, max: 200 },
  { field: "evidenceTopK", label: "Evidence TopK", min: 1, max: 50 },
  { field: "parentTokenBudget", label: "上下文 Token 预算", min: 0, max: 6000 },
];

function formatTime(value: string | null) {
  return value ? DATE_TIME_FORMATTER.format(new Date(value)) : "—";
}

function percentage(value: number | null, reportAvailable: boolean) {
  if (value === null) return reportAvailable ? "不适用" : "未运行";
  return `${(value * 100).toFixed(1)}%`;
}

function modelStatusLabel(status: "DISABLED" | "UP" | "DOWN") {
  if (status === "UP") return "可用";
  if (status === "DOWN") return "不可用";
  return "未启用";
}

export function RetrievalDebugPage() {
  const { expireSession } = useAuth();
  const [configuration, setConfiguration] = useState<RetrievalConfiguration | null>(null);
  const [configurationState, setConfigurationState] = useState<LoadState>("loading");
  const [configurationMessage, setConfigurationMessage] = useState<string | null>(null);
  const [modelHealth, setModelHealth] = useState<ModelServicesHealth | null>(null);
  const [modelState, setModelState] = useState<LoadState>("loading");
  const [modelMessage, setModelMessage] = useState<string | null>(null);
  const [profileDraft, setProfileDraft] = useState<CreateRetrievalProfileRequest>(INITIAL_PROFILE);
  const [creatingProfile, setCreatingProfile] = useState(false);
  const [profileMessage, setProfileMessage] = useState<string | null>(null);
  const [profileError, setProfileError] = useState(false);
  const [query, setQuery] = useState("");
  const [visibility, setVisibility] = useState("");
  const [documentId, setDocumentId] = useState("");
  const [graphMode, setGraphMode] = useState<GraphMode>("AUTO");
  const [debugging, setDebugging] = useState(false);
  const [debugResult, setDebugResult] = useState<Bm25DebugResponse | null>(null);
  const [debugMessage, setDebugMessage] = useState<string | null>(null);
  const [generations, setGenerations] = useState<IndexGenerationsResponse | null>(null);
  const [generationState, setGenerationState] = useState<LoadState>("loading");
  const [generationMessage, setGenerationMessage] = useState<string | null>(null);
  const [generationAction, setGenerationAction] = useState<string | null>(null);
  const [buildConfig, setBuildConfig] = useState("");
  const [buildReason, setBuildReason] = useState("");
  const [buildConfirmation, setBuildConfirmation] = useState("");
  const [releaseProfile, setReleaseProfile] = useState("phase6c-hybrid-rerank-v1");
  const [releaseReason, setReleaseReason] = useState("");
  const [releaseConfirmation, setReleaseConfirmation] = useState("");
  const configurationRequestRef = useRef<AbortController | null>(null);
  const modelRequestRef = useRef<AbortController | null>(null);
  const debugRequestRef = useRef<AbortController | null>(null);
  const generationRequestRef = useRef<AbortController | null>(null);

  const loadConfiguration = useCallback(() => {
    configurationRequestRef.current?.abort();
    const controller = new AbortController();
    configurationRequestRef.current = controller;
    setConfigurationState("loading");
    setConfigurationMessage(null);
    apiRequest<RetrievalConfiguration>("/api/v1/admin/retrieval/configuration", {
      signal: controller.signal,
    })
      .then((response) => {
        if (controller.signal.aborted) return;
        setConfiguration(response);
        const buildableConfigs = response.indexConfigs.filter((config) =>
          config.vectorDimensions !== null && config.schemaVersion.startsWith("source-locator-"));
        setBuildConfig((current) => {
          if (buildableConfigs.some((config) => config.version === current)) return current;
          const activeVersion = response.activeManifest?.indexConfigVersion;
          if (activeVersion && buildableConfigs.some((config) => config.version === activeVersion)) {
            return activeVersion;
          }
          return buildableConfigs[buildableConfigs.length - 1]?.version ?? "";
        });
        setConfigurationState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setConfigurationMessage(caught instanceof ApiError ? caught.message : "检索配置加载失败");
        setConfigurationState("error");
      })
      .finally(() => {
        if (configurationRequestRef.current === controller) configurationRequestRef.current = null;
      });
  }, [expireSession]);

  const loadModelHealth = useCallback(() => {
    modelRequestRef.current?.abort();
    const controller = new AbortController();
    modelRequestRef.current = controller;
    setModelState("loading");
    setModelMessage(null);
    apiRequest<ModelServicesHealth>("/api/v1/admin/model-services/health", {
      signal: controller.signal,
    })
      .then((response) => {
        if (controller.signal.aborted) return;
        setModelHealth(response);
        setModelState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setModelMessage(caught instanceof ApiError ? caught.message : "模型状态加载失败");
        setModelState("error");
      })
      .finally(() => {
        if (modelRequestRef.current === controller) modelRequestRef.current = null;
      });
  }, [expireSession]);

  const loadGenerations = useCallback((quiet = false) => {
    generationRequestRef.current?.abort();
    const controller = new AbortController();
    generationRequestRef.current = controller;
    if (!quiet) {
      setGenerationState("loading");
      setGenerationMessage(null);
    }
    apiRequest<IndexGenerationsResponse>("/api/v1/admin/index-builds", {
      signal: controller.signal,
    })
      .then((response) => {
        if (controller.signal.aborted) return;
        setGenerations(response);
        setGenerationState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setGenerationMessage(caught instanceof ApiError ? caught.message : "Generation 加载失败");
        if (!quiet) setGenerationState("error");
      })
      .finally(() => {
        if (generationRequestRef.current === controller) generationRequestRef.current = null;
      });
  }, [expireSession]);

  useEffect(() => {
    loadConfiguration();
    loadModelHealth();
    loadGenerations();
    return () => {
      configurationRequestRef.current?.abort();
      modelRequestRef.current?.abort();
      generationRequestRef.current?.abort();
      debugRequestRef.current?.abort();
    };
  }, [loadConfiguration, loadGenerations, loadModelHealth]);

  const generationBuilding = generations?.generations.some(
    (generation) => generation.status === "BUILDING",
  ) ?? false;

  useEffect(() => {
    if (!generationBuilding) return;
    const timer = window.setInterval(() => loadGenerations(true), 2000);
    return () => window.clearInterval(timer);
  }, [generationBuilding, loadGenerations]);

  function changeProfileMode(mode: RetrievalMode) {
    setProfileDraft((current) => ({
      ...current,
      mode,
      vectorTopK: mode === "HYBRID" ? Math.max(current.vectorTopK, 50) : 0,
      rerankTopK: mode === "HYBRID" ? Math.max(current.rerankTopK, 30) : 0,
    }));
  }

  function changeProfileNumber(field: NumericProfileField, value: string) {
    setProfileDraft((current) => ({
      ...current,
      [field]: Number(value),
    }));
  }

  async function createProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const version = profileDraft.version.trim();
    if (!version) {
      setProfileMessage("请输入 Profile 版本");
      setProfileError(true);
      return;
    }

    setCreatingProfile(true);
    setProfileMessage(null);
    setProfileError(false);
    try {
      const created = await apiRequest<RetrievalConfiguration["profiles"][number]>(
        "/api/v1/admin/retrieval/profiles",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...profileDraft, version }),
        },
      );
      setConfiguration((current) => current
        ? { ...current, profiles: [...current.profiles, created] }
        : current);
      setProfileDraft(INITIAL_PROFILE);
      setProfileMessage(`已创建 ${created.version}；当前发布 Profile 未改变`);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setProfileMessage(caught instanceof ApiError ? caught.message : "Profile 创建失败");
      setProfileError(true);
    } finally {
      setCreatingProfile(false);
    }
  }

  async function submitDebug(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedQuery = query.trim();
    if (!normalizedQuery) {
      setDebugResult(null);
      setDebugMessage("请输入调试查询");
      return;
    }

    debugRequestRef.current?.abort();
    const controller = new AbortController();
    debugRequestRef.current = controller;
    setDebugging(true);
    setDebugResult(null);
    setDebugMessage(null);
    try {
      const response = await apiRequest<Bm25DebugResponse>("/api/v1/admin/search/debug", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          query: normalizedQuery,
          page: 0,
          size: 20,
          graphModeRequested: graphMode,
          ...(visibility ? { visibility: visibility as DocumentVisibility } : {}),
          ...(documentId.trim() ? { documentId: documentId.trim() } : {}),
        }),
        signal: controller.signal,
      });
      if (!controller.signal.aborted) setDebugResult(response);
    } catch (caught) {
      if (controller.signal.aborted) return;
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setDebugMessage(caught instanceof ApiError ? caught.message : "检索调试失败");
    } finally {
      if (!controller.signal.aborted) setDebugging(false);
      if (debugRequestRef.current === controller) debugRequestRef.current = null;
    }
  }

  async function startBuild(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (generationAction) return;
    setGenerationAction("build");
    setGenerationMessage(null);
    try {
      await apiRequest<IndexGeneration>("/api/v1/admin/index-builds", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          indexConfigVersion: buildConfig,
          reason: buildReason.trim(),
          confirmation: buildConfirmation.trim(),
        }),
      });
      setBuildReason("");
      setBuildConfirmation("");
      setGenerationMessage("Generation 构建已进入队列；READY 后不会自动发布。");
      loadGenerations(true);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setGenerationMessage(caught instanceof ApiError ? caught.message : "Generation 构建启动失败");
    } finally {
      setGenerationAction(null);
    }
  }

  async function releaseGeneration(generation: IndexGeneration, action: "publish" | "rollback") {
    if (generationAction) return;
    const confirmation = action === "publish" ? "PUBLISH" : "ROLLBACK";
    if (releaseConfirmation.trim() !== confirmation || !releaseReason.trim()) {
      setGenerationMessage(`请输入审计理由，并在确认字段输入 ${confirmation}`);
      return;
    }
    setGenerationAction(`${action}-${generation.indexGeneration}`);
    setGenerationMessage(null);
    try {
      await apiRequest<IndexGeneration>(
        action === "publish"
          ? "/api/v1/admin/retrieval/releases"
          : "/api/v1/admin/retrieval/rollback",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            indexGeneration: generation.indexGeneration,
            profileVersion: releaseProfile,
            reason: releaseReason.trim(),
            confirmation,
          }),
        },
      );
      setReleaseReason("");
      setReleaseConfirmation("");
      setGenerationMessage(
        action === "publish"
          ? `Generation ${generation.indexGeneration} 已发布`
          : `Generation ${generation.indexGeneration} 已回滚为 ACTIVE`,
      );
      loadGenerations(true);
      loadConfiguration();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setGenerationMessage(caught instanceof ApiError ? caught.message : "Generation 发布操作失败");
    } finally {
      setGenerationAction(null);
    }
  }

  return (
    <section className="retrieval-debug-page">
      <section className="retrieval-panel model-services-panel" aria-labelledby="model-services-title">
        <header className="retrieval-panel-header">
          <div>
            <h2 id="model-services-title">模型服务</h2>
            <p>Hybrid Profile 未启动时，在线搜索继续使用 BM25。</p>
          </div>
          <button
            className="secondary-button"
            type="button"
            onClick={loadModelHealth}
            disabled={modelState === "loading"}
          >
            刷新模型状态
          </button>
        </header>
        {modelState === "loading" ? (
          <div className="inline-state" aria-live="polite">
            <span className="spinner" aria-hidden="true" />
            <span>正在检查模型服务</span>
          </div>
        ) : null}
        {modelState === "error" ? (
          <div className="section-error" role="alert">
            <p>{modelMessage ?? "模型状态加载失败"}</p>
            <button className="secondary-button" type="button" onClick={loadModelHealth}>重试</button>
          </div>
        ) : null}
        {modelState === "ready" && modelHealth ? (
          <div className="model-service-grid">
            {modelHealth.services.map((service) => (
              <article key={service.type} className="model-service-card">
                <header>
                  <div>
                    <small>{service.type === "EMBEDDING" ? "Embedding" : "Reranker"}</small>
                    <strong>{service.model || "未配置模型"}</strong>
                  </div>
                  <span className={`model-service-status ${service.status.toLowerCase()}`}>
                    {modelStatusLabel(service.status)}
                  </span>
                </header>
                <dl>
                  <div><dt><AdminTerm term="MODEL_REVISION" /></dt><dd title={service.revision}><AdminValue state={service.revision ? "VALUE" : "NOT_AVAILABLE"}>{service.revision || undefined}</AdminValue></dd></div>
                  <div><dt>向量维度</dt><dd><AdminValue state={service.dimensions === null ? (service.type === "RERANK" ? "NOT_APPLICABLE" : "NOT_AVAILABLE") : "VALUE"}>{service.dimensions ?? undefined}</AdminValue></dd></div>
                  <div><dt><AdminTerm term="HEALTH_PROBE_LATENCY" /></dt><dd><AdminValue state={service.latencyMs === null ? "NOT_AVAILABLE" : "VALUE"}>{service.latencyMs === null ? undefined : `${service.latencyMs} ms`}</AdminValue></dd></div>
                  <div><dt>检查时间</dt><dd>{formatTime(service.checkedAt)}</dd></div>
                </dl>
                {service.errorCode ? <p className="model-error-code">{service.errorCode}</p> : null}
              </article>
            ))}
            {modelHealth.services.length === 0 ? <p className="empty-inline">没有已配置的模型服务。</p> : null}
          </div>
        ) : null}
      </section>

      <EmbeddingCachePanel />

      <section className="retrieval-panel retrieval-configuration" aria-labelledby="retrieval-configuration-title">
        <header className="retrieval-panel-header">
          <div>
            <h2 id="retrieval-configuration-title">检索配置</h2>
            <p>Profile 与 IndexConfig 都是不可变版本；创建或构建不会自动改变当前发布。</p>
          </div>
          <button
            className="secondary-button"
            type="button"
            onClick={loadConfiguration}
            disabled={configurationState === "loading"}
          >
            刷新配置
          </button>
        </header>
        {configurationState === "loading" ? (
          <div className="inline-state" aria-live="polite">
            <span className="spinner" aria-hidden="true" />
            <span>正在读取检索配置</span>
          </div>
        ) : null}
        {configurationState === "error" ? (
          <div className="section-error" role="alert">
            <p>{configurationMessage ?? "检索配置加载失败"}</p>
            <button className="secondary-button" type="button" onClick={loadConfiguration}>重试</button>
          </div>
        ) : null}
        {configurationState === "ready" && configuration ? (
          <>
            <div className="retrieval-current-grid">
              <article>
                <small>当前发布 Profile</small>
                <strong>{configuration.currentPublication?.profileVersion ?? "未发布"}</strong>
                <span>{configuration.currentPublication
                  ? `发布于 ${formatTime(configuration.currentPublication.publishedAt)}`
                  : "系统尚无发布记录"}</span>
              </article>
              <article>
                <small>当前 IndexConfig</small>
                <strong>{configuration.activeManifest?.indexConfigVersion ?? "未激活"}</strong>
                <span>{configuration.activeManifest
                  ? `Generation ${configuration.activeManifest.indexGeneration} · ${configuration.activeManifest.status}`
                  : "系统尚无 ACTIVE Manifest"}</span>
              </article>
            </div>

            <section className="retrieval-config-section" aria-labelledby="profiles-title">
              <div className="section-title-row">
                <div>
                  <h3 id="profiles-title">RetrievalProfile</h3>
                  <p>创建新版本不会发布、切换索引或改变在线搜索。</p>
                </div>
              </div>
              <div className="table-wrap retrieval-config-table-wrap">
                <table className="retrieval-config-table">
                  <thead>
                    <tr>
                      <th>版本</th>
                      <th>模式</th>
                      <th>召回</th>
                      <th>Rerank / Evidence</th>
                      <th>Parent 预算</th>
                      <th>创建时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {configuration.profiles.map((profile) => {
                      const published = configuration.currentPublication?.profileVersion === profile.version;
                      return (
                        <tr key={profile.version}>
                          <td>
                            <strong>{profile.version}</strong>
                            {published ? <span className="current-badge">当前发布</span> : null}
                          </td>
                          <td>{profile.mode}</td>
                          <td>BM25 {profile.bm25TopK} / Vector {profile.vectorTopK}</td>
                          <td>{profile.rerankTopK} / {profile.evidenceTopK}</td>
                          <td>{profile.parentTokenBudget}</td>
                          <td>{formatTime(profile.createdAt)}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
                {configuration.profiles.length === 0 ? (
                  <div className="empty-inline">尚无 RetrievalProfile。</div>
                ) : null}
              </div>

              <details className="profile-create">
                <summary>创建不可变 Profile</summary>
                <form onSubmit={createProfile}>
                  <label>
                    <span>版本</span>
                    <input
                      value={profileDraft.version}
                      onChange={(event) => setProfileDraft((current) => ({
                        ...current,
                        version: event.target.value,
                      }))}
                      placeholder="例如 phase6-hybrid-v1"
                      maxLength={64}
                      required
                    />
                  </label>
                  <label>
                    <span>模式</span>
                    <select
                      value={profileDraft.mode}
                      onChange={(event) => changeProfileMode(event.target.value as RetrievalMode)}
                    >
                      <option value="BM25">BM25</option>
                      <option value="HYBRID">HYBRID</option>
                    </select>
                  </label>
                  {PROFILE_NUMBER_FIELDS.map(({ field, label, min, max }) => {
                    const disabled = profileDraft.mode === "BM25"
                      && (field === "vectorTopK" || field === "rerankTopK");
                    return (
                      <label key={field}>
                        <span>{label}</span>
                        <input
                          type="number"
                          min={min}
                          max={max}
                          value={profileDraft[field]}
                          disabled={disabled}
                          onChange={(event) => changeProfileNumber(field, event.target.value)}
                          required
                        />
                      </label>
                    );
                  })}
                  <div className="profile-create-actions">
                    <button className="primary-button" type="submit" disabled={creatingProfile}>
                      {creatingProfile ? "创建中" : "创建 Profile"}
                    </button>
                  </div>
                </form>
                {profileMessage ? (
                  <div className={profileError ? "form-error" : "success-message"} role={profileError ? "alert" : "status"}>
                    {profileMessage}
                  </div>
                ) : null}
              </details>
            </section>

            <section className="retrieval-config-section" aria-labelledby="index-configs-title">
              <h3 id="index-configs-title">IndexConfig</h3>
              <div className="table-wrap retrieval-config-table-wrap">
                <table className="retrieval-config-table index-config-table">
                  <thead>
                    <tr>
                      <th>版本</th>
                      <th>Schema / Analyzer</th>
                      <th>Embedding</th>
                      <th>Vector</th>
                      <th>HNSW</th>
                      <th>创建时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {configuration.indexConfigs.map((config) => {
                      const active = configuration.activeManifest?.indexConfigVersion === config.version;
                      return (
                        <tr key={config.version}>
                          <td>
                            <strong>{config.version}</strong>
                            {active ? <span className="current-badge">ACTIVE</span> : null}
                          </td>
                          <td>{config.schemaVersion}<small>{config.analyzer}</small></td>
                          <td>
                            {config.embeddingModel ?? "无向量"}
                            {config.embeddingRevision ? <small title={config.embeddingRevision}>{config.embeddingRevision}</small> : null}
                            {config.embeddingModel ? (
                              <small>
                                {config.embeddingProviderKey} · {config.embeddingInputFormatVersion} · {config.embeddingNormalizationVersion}
                              </small>
                            ) : null}
                          </td>
                          <td>{config.vectorDimensions === null
                            ? "—"
                            : `${config.vectorDimensions} / ${config.distance ?? "—"}`}</td>
                          <td>{config.hnswM === null
                            ? "—"
                            : `m=${config.hnswM}, ef=${config.hnswEfConstruction ?? "—"}`}</td>
                          <td>{formatTime(config.createdAt)}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </section>

            <section className="retrieval-config-section golden-baseline" aria-labelledby="golden-baseline-title">
              <div className="section-title-row">
                <div>
                  <h3 id="golden-baseline-title">Golden 基准</h3>
                  <p>{configuration.goldenBaseline.datasetVersion} · {configuration.goldenBaseline.caseCount} 个案例</p>
                </div>
                <span className={`baseline-status ${configuration.goldenBaseline.status.toLowerCase()}`}>
                  {configuration.goldenBaseline.reportAvailable
                    ? configuration.goldenBaseline.status
                    : "报告未生成"}
                </span>
              </div>
              <div className="golden-slices">
                {configuration.goldenBaseline.slices.map((slice) => (
                  <article key={slice.name}>
                    <small>{slice.name}</small>
                    <strong>{percentage(
                      slice.candidateHitAt50,
                      configuration.goldenBaseline.reportAvailable,
                    )}</strong>
                    <span>{slice.caseCount} cases · Candidate Hit@50</span>
                  </article>
                ))}
              </div>
              <p className="baseline-time">
                {configuration.goldenBaseline.generatedAt
                  ? `报告生成于 ${formatTime(configuration.goldenBaseline.generatedAt)}`
                  : "版本化数据集已就绪；没有持久化报告时不展示伪造指标。"}
              </p>
            </section>
          </>
        ) : null}
      </section>

      <section className="index-status-panel generation-panel" aria-labelledby="index-status-title">
        <header>
          <div>
            <h2 id="index-status-title">索引版本</h2>
            <p>完整构建产生独立候选版本；管理员显式发布后才切换线上索引，PostgreSQL 始终是事实源。</p>
          </div>
          <button
            className="secondary-button"
            type="button"
            onClick={() => loadGenerations()}
            disabled={generationState === "loading"}
          >
            刷新
          </button>
        </header>

        {generationState === "loading" ? (
          <div className="inline-state"><span className="spinner" /><span>正在读取索引版本</span></div>
        ) : null}
        {generationState === "error" ? (
          <div className="form-error" role="alert">{generationMessage ?? "索引版本加载失败"}</div>
        ) : null}

        {generationState === "ready" && generations ? (
          <>
            <div className="generation-active">
              <span>当前生效</span>
              <strong>{generations.activeGeneration
                ? `第 ${generations.activeGeneration} 代`
                : "尚未激活"}</strong>
              {generationBuilding ? <small>后台正在构建新索引版本</small> : null}
            </div>

            <form className="generation-form" onSubmit={startBuild}>
              <label>
                <span>IndexConfig</span>
                <select value={buildConfig} onChange={(event) => setBuildConfig(event.target.value)}>
                  {(configuration?.indexConfigs ?? []).filter((config) =>
                    config.vectorDimensions !== null && config.schemaVersion.startsWith("source-locator-"))
                    .map((config) => <option key={config.version} value={config.version}>{config.version}</option>)}
                </select>
              </label>
              <label>
                <span>构建理由</span>
                <input
                  value={buildReason}
                  onChange={(event) => setBuildReason(event.target.value)}
                  maxLength={500}
                  placeholder="记录本次构建目的"
                  required
                />
              </label>
              <label>
                <span>确认文字</span>
                <input
                  value={buildConfirmation}
                  onChange={(event) => setBuildConfirmation(event.target.value)}
                  placeholder="输入 BUILD"
                  pattern="BUILD"
                  required
                />
              </label>
              <button
                className="primary-button"
                type="submit"
                disabled={Boolean(generationAction) || generationBuilding || !buildConfig}
              >
                {generationAction === "build" ? "提交中" : "构建索引版本"}
              </button>
            </form>

            <div className="generation-release-form">
              <label>
                <span>发布 Profile</span>
                <select value={releaseProfile} onChange={(event) => setReleaseProfile(event.target.value)}>
                  {(configuration?.profiles ?? []).map((profile) => (
                    <option key={profile.version} value={profile.version}>{profile.version}</option>
                  ))}
                </select>
              </label>
              <label>
                <span>审计理由</span>
                <input
                  value={releaseReason}
                  onChange={(event) => setReleaseReason(event.target.value)}
                  maxLength={500}
                  placeholder="发布或回滚原因"
                />
              </label>
              <label>
                <span>确认文字</span>
                <input
                  value={releaseConfirmation}
                  onChange={(event) => setReleaseConfirmation(event.target.value)}
                  placeholder="PUBLISH 或 ROLLBACK"
                />
              </label>
            </div>

            <div className="table-wrap generation-table-wrap">
              <table className="generation-table">
                <thead>
                  <tr>
                    <th>代次</th>
                    <th>状态</th>
                    <th>进度</th>
                    <th>Vector 覆盖</th>
                    <th>发布闭包</th>
                    <th>IndexConfig</th>
                    <th>更新时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {generations.generations.map((generation) => {
                    const progress = generation.expectedChunkCount === 0
                      ? 100
                      : Math.min(100, generation.indexedChunkCount / generation.expectedChunkCount * 100);
                    const action = generation.status === "READY"
                      ? "publish"
                      : generation.status === "RETIRED" ? "rollback" : null;
                    return (
                      <tr key={generation.id}>
                        <td>
                          <strong>#{generation.indexGeneration}</strong>
                          <small title={generation.indexName}>{generation.indexName}</small>
                        </td>
                        <td>
                          <span className={`generation-status ${generation.status.toLowerCase()}`}>
                            {generation.status}
                          </span>
                          {generation.failureCode ? (
                            <small className="generation-failure" title={generation.failureReason ?? ""}>
                              {generation.failureCode}
                            </small>
                          ) : null}
                        </td>
                        <td>
                          <span>{generation.indexedChunkCount} / {generation.expectedChunkCount}</span>
                          <progress value={progress} max={100}>{progress.toFixed(0)}%</progress>
                        </td>
                        <td>
                          {generation.validVectorCount} / {generation.expectedChunkCount}
                          <small>{(generation.vectorCoverage * 100).toFixed(1)}% · {
                            generation.readyCheckPassed ? "检查通过" : "未就绪"
                          }</small>
                        </td>
                        <td>
                          {generation.closure && generation.recovery ? (
                            <ProjectionClosureSummary
                              closure={generation.closure}
                              recovery={generation.recovery}
                            />
                          ) : generation.readyCheckPassed ? "检查通过" : "未就绪"}
                        </td>
                        <td>{generation.indexConfigVersion}</td>
                        <td>{formatTime(generation.updatedAt)}</td>
                        <td>
                          {action ? (
                            <button
                              className="secondary-button compact-button"
                              type="button"
                              onClick={() => releaseGeneration(generation, action)}
                              disabled={Boolean(generationAction)}
                            >
                              {generationAction === `${action}-${generation.indexGeneration}`
                                ? "处理中"
                                : action === "publish" ? "发布" : "回滚"}
                            </button>
                          ) : "—"}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </>
        ) : null}
        {generationMessage && generationState === "ready" ? (
          <div className="generation-message" role="status">{generationMessage}</div>
        ) : null}
      </section>

      <section className="debug-query-panel" aria-labelledby="debug-query-title">
        <header><h2 id="debug-query-title">Hybrid、Local 与 Global Graph 调试</h2><p>展示授权后的路径或公共报告、一次 Rerank、Evidence 与上下文预算；不暴露被权限过滤的节点或统计。</p></header>
        <form className="debug-query-form" onSubmit={submitDebug}>
          <label className="debug-query-input"><span>查询</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入中文、English 或混合查询" /></label>
          <label><span>可见性</span><select value={visibility} onChange={(event) => setVisibility(event.target.value)}><option value="">全部</option><option value="ALL_USERS">所有用户</option><option value="RESTRICTED">受限</option></select></label>
          <label><span>Graph 模式</span><select value={graphMode} onChange={(event) => setGraphMode(event.target.value as GraphMode)}><option value="AUTO">AUTO（请求级 Hybrid / Local / Global）</option><option value="HYBRID">HYBRID</option><option value="LOCAL_GRAPH">LOCAL_GRAPH</option><option value="GLOBAL_GRAPH">GLOBAL_GRAPH</option></select></label>
          <label><span>文档 ID（可选）</span><input value={documentId} onChange={(event) => setDocumentId(event.target.value)} placeholder="UUID" /></label>
          <button className="primary-button" type="submit" disabled={debugging}>{debugging ? "执行中" : "执行调试"}</button>
        </form>
        {debugMessage ? <div className="form-error debug-message" role="alert">{debugMessage}</div> : null}
      </section>

      {debugging ? <div className="table-state debug-state" aria-live="polite"><span className="spinner" /><p>正在执行检索</p></div> : null}

      {debugResult ? (
        <section className="debug-results" aria-labelledby="debug-results-title">
          <header className="debug-summary">
            <div><h2 id="debug-results-title">候选结果</h2><p>{debugResult.query}</p></div>
            <div>
              <span>{debugResult.retrievalProfile}</span>
              <span>Generation {debugResult.indexGeneration}</span>
              <span>{debugResult.modeUsed}{debugResult.degraded ? " · DEGRADED" : ""}</span>
              <span>
                Graph {debugResult.graphModeRequested} → {debugResult.graphModeUsed}
                {debugResult.graphGeneration ? ` · G${debugResult.graphGeneration}` : ""}
              </span>
              <span>
                Router {debugResult.result.routeExecution?.requestedMode ?? debugResult.graphModeRequested}
                {" → "}{debugResult.result.routeExecution?.selectedMode ?? debugResult.graphModeUsed}
                {" · "}{debugResult.result.routeExecution?.routerCallCount ?? 0}/1
              </span>
              {debugResult.globalExecution ? (
                <span>
                  Global G{debugResult.globalExecution.globalGeneration ?? "—"}
                  {" · "}{debugResult.globalExecution.reportCount}/{debugResult.globalExecution.reportLimit} Reports
                </span>
              ) : null}
              <span>{debugResult.tookMs} ms</span>
            </div>
          </header>
          {debugResult.degraded ? (
            <div className="debug-degradation" role="status">
              安全降级：{debugResult.degradationCode ?? "UNKNOWN"}
            </div>
          ) : null}
          {debugResult.graphDegraded ? (
            <div className="debug-degradation" role="status">
              Graph {debugResult.graphModeRequested} → {debugResult.graphModeUsed}：
              {debugResult.graphDegradationCode ?? "GRAPH_UNAVAILABLE"}
            </div>
          ) : null}
          {debugResult.result.routeExecution?.degraded ? (
            <div className="debug-degradation" role="status">
              AUTO 安全回退：{debugResult.result.routeExecution.degradationCode ?? "ROUTER_UNAVAILABLE"}
            </div>
          ) : null}
          {debugResult.stages ? (
            <ol className="debug-stages" aria-label="检索阶段">
              {debugResult.stages.map((stage) => (
                <li key={stage.name} className={stage.status.toLowerCase()}>
                  <header>
                    <strong>{stage.name}</strong>
                    <span>{stage.status}</span>
                  </header>
                  <p>{stage.inputCount} → {stage.outputCount}</p>
                  <small>{stage.tookMs} ms{stage.code ? ` · ${stage.code}` : ""}</small>
                </li>
              ))}
            </ol>
          ) : null}
          {debugResult.contextBudget ? (
            <section className="context-budget" aria-labelledby="context-budget-title">
              <div>
                <h3 id="context-budget-title">Evidence 上下文预算</h3>
                <p>
                  {debugResult.contextBudget.globalTokens == null ? (
                    <>Child {debugResult.contextBudget.childTokens} + Parent {
                      debugResult.contextBudget.parentTokens
                    } + Graph {debugResult.contextBudget.graphTokens} = {
                      debugResult.contextBudget.totalTokens
                    } / {debugResult.contextBudget.limitTokens} tokens</>
                  ) : (
                    <>Child {debugResult.contextBudget.childTokens} + Parent {
                      debugResult.contextBudget.parentTokens
                    } + Local Graph {debugResult.contextBudget.graphTokens} + Global {
                      debugResult.contextBudget.globalTokens
                    } = {debugResult.contextBudget.totalTokens} / {
                      debugResult.contextBudget.limitTokens
                    } tokens</>
                  )}
                </p>
              </div>
              <span>
                {debugResult.contextBudget.parentCount} Parents ·{" "}
                {debugResult.contextBudget.graphPathCount} Graph Paths
                {debugResult.contextBudget.globalClaimCount == null
                  ? ""
                  : ` · ${debugResult.contextBudget.globalClaimCount} Global Claims`}
              </span>
              {debugResult.contextBudget.trimReasons.length > 0 ? (
                <ul>
                  {debugResult.contextBudget.trimReasons.map((reason) => (
                    <li key={reason}>{reason}</li>
                  ))}
                </ul>
              ) : null}
            </section>
          ) : null}
          {debugResult.globalExecution ? (
            <section className="context-budget global-budget" aria-label="Global Graph 执行预算">
              <div>
                <h3>Global Graph 执行</h3>
                <p>
                  {debugResult.globalExecution.configVersion} · Generation {
                    debugResult.globalExecution.globalGeneration ?? "—"
                  } · {debugResult.globalExecution.reportCount}/{
                    debugResult.globalExecution.reportLimit
                  } Reports
                </p>
              </div>
              <span>
                最多 {debugResult.globalExecution.modelCallLimit} 次模型调用 ·{" "}
                {debugResult.globalExecution.hardTimeoutMs} ms
                {debugResult.globalExecution.shadow ? " · SHADOW" : ""}
              </span>
            </section>
          ) : null}
          <div className="table-wrap debug-table-wrap">
            <table className="debug-table">
              <thead>
                <tr>
                  <th>Rank</th>
                  <th>文档 / Chunk</th>
                  <th>BM25</th>
                  <th>Vector</th>
                  <th>RRF</th>
                  <th>Graph</th>
                  <th>Global</th>
                  <th>Rerank</th>
                  <th>Evidence</th>
                  <th>Score</th>
                  <th>状态</th>
                  <th>片段</th>
                </tr>
              </thead>
              <tbody>{debugResult.candidates.map((candidate) => {
                const hit = candidate.result;
                return (
                  <tr key={`${candidate.rank}-${hit?.chunkId ?? "filtered"}`}>
                    <td>#{candidate.rank}</td>
                    <td>{hit ? <Link to={`/chunks/${hit.chunkId}`}><strong>{hit.documentTitle}</strong><small>R{hit.revisionNumber} · {hit.chunkId}</small></Link> : <span className="filtered-candidate">不可展示</span>}</td>
                    <td>{candidate.bm25Rank === null ? "—" : `#${candidate.bm25Rank}`}</td>
                    <td>{candidate.vectorRank === null ? "—" : `#${candidate.vectorRank}`}</td>
                    <td>{candidate.rrfScore === null ? "—" : candidate.rrfScore.toFixed(6)}</td>
                    <td>
                      {candidate.graphRank == null ? "—" : `#${candidate.graphRank}`}
                      {candidate.graphPaths?.length ? (
                        <details>
                          <summary>{candidate.graphPaths.length} 条授权路径</summary>
                          {candidate.graphPaths.map((path) => (
                            <p key={path.relationshipId}>
                              {path.relationshipType} · {path.depth} hop ·{" "}
                              {path.documentTitle} {sourceLocationText(path)}
                            </p>
                          ))}
                        </details>
                      ) : null}
                    </td>
                    <td>
                      {candidate.globalRank == null ? "—" : `#${candidate.globalRank}`}
                      {candidate.globalClaims?.length ? (
                        <details>
                          <summary>{candidate.globalClaims.length} 条公共 Claim</summary>
                          {candidate.globalClaims.map((claim) => (
                            <p key={`${claim.claimId}-${claim.sourceSpanId}`}>
                              {claim.reportTitle} · Community {claim.communityKey}
                              {" · "}
                              <Link to={`/chunks/${claim.supportingChunkId}`}>Evidence Child</Link>
                            </p>
                          ))}
                        </details>
                      ) : null}
                    </td>
                    <td>{candidate.rerankRank == null
                      ? "—"
                      : `#${candidate.rerankRank} · ${candidate.rerankScore?.toFixed(4) ?? "—"}`}</td>
                    <td>{candidate.evidenceRank == null ? "—" : `#${candidate.evidenceRank}`}</td>
                    <td>{candidate.score.toFixed(4)}</td>
                    <td><span className={`debug-decision ${candidate.accepted ? "accepted" : "rejected"}`}>{candidate.accepted ? "最终结果" : candidate.rejectionReason ?? "已过滤"}</span></td>
                    <td className="debug-snippet">{hit?.snippet ?? "—"}</td>
                  </tr>
                );
              })}</tbody>
            </table>
            {debugResult.candidates.length === 0 ? <div className="table-state debug-state"><strong>没有检索候选</strong></div> : null}
          </div>

          <section className="debug-final" aria-labelledby="debug-final-title">
            <h3 id="debug-final-title">最终返回结果（{debugResult.result.items.length}）</h3>
            {debugResult.result.items.length === 0 ? <p>所有候选均被过滤，或查询没有命中。</p> : (
              <ol>{debugResult.result.items.map((result) => (
                <li key={result.chunkId}>
                  <Link to={`/chunks/${result.chunkId}`}>{result.documentTitle}</Link>
                  <SourceLocation
                    source={result}
                    revisionNumber={result.revisionNumber}
                    linkToSource={false}
                  />
                  <p>{result.snippet}</p>
                  {result.evidence?.parent ? (
                    <small>
                      Parent · {sourceLocationText(result.evidence.parent)} ·{" "}
                      {result.evidence.parent.contributedTokens} tokens
                      {result.evidence.parent.truncated ? " · 已裁剪" : ""}
                    </small>
                  ) : null}
                  {result.evidence?.graphPaths?.length ? (
                    <details>
                      <summary>{result.evidence.graphPaths.length} 条 Local Graph Evidence</summary>
                      {result.evidence.graphPaths.map((path) => (
                        <p key={path.relationshipId}>
                          <strong>{path.relationshipType}</strong> · {path.documentTitle}
                          {" "}{sourceLocationText(path)} · {path.evidenceText}
                        </p>
                      ))}
                    </details>
                  ) : null}
                  {result.evidence?.globalClaims?.length ? (
                    <details>
                      <summary>{result.evidence.globalClaims.length} 条 Global Community Claim</summary>
                      {result.evidence.globalClaims.map((claim) => (
                        <blockquote key={`${claim.claimId}-${claim.sourceSpanId}`}>
                          <strong>{claim.reportTitle}</strong> · {claim.claimText}
                          <footer>
                            {claim.documentTitle} {sourceLocationText(claim)}
                            {" · "}
                            <Link to={`/chunks/${claim.supportingChunkId}`}>Evidence Child</Link>
                          </footer>
                        </blockquote>
                      ))}
                    </details>
                  ) : null}
                </li>
              ))}</ol>
            )}
          </section>
        </section>
      ) : null}
    </section>
  );
}
