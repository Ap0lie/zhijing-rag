import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";

import { ApiError, apiRequest } from "../api";
import { useAuth } from "../auth";
import type {
  ClearEmbeddingCacheRequest,
  ClearEmbeddingCacheResult,
  EmbeddingCacheModelStatistics,
  EmbeddingCacheStatistics,
} from "../types";

type LoadState = "loading" | "ready" | "error";

const NUMBER_FORMATTER = new Intl.NumberFormat("zh-CN");

function formatCount(value: number) {
  return NUMBER_FORMATTER.format(value);
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KiB`;
  if (value < 1024 ** 3) return `${(value / 1024 ** 2).toFixed(1)} MiB`;
  return `${(value / 1024 ** 3).toFixed(2)} GiB`;
}

function modelNamespaceKey(model: EmbeddingCacheModelStatistics) {
  return `${model.providerKey}\u0000${model.model}\u0000${model.revision}`;
}

function modelRowKey(model: EmbeddingCacheModelStatistics) {
  return `${modelNamespaceKey(model)}\u0000${model.dimensions}`;
}

export function EmbeddingCachePanel() {
  const { expireSession } = useAuth();
  const [statistics, setStatistics] = useState<EmbeddingCacheStatistics | null>(null);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [loadMessage, setLoadMessage] = useState<string | null>(null);
  const [selectedModelKey, setSelectedModelKey] = useState("");
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [clearing, setClearing] = useState(false);
  const [actionMessage, setActionMessage] = useState<string | null>(null);
  const [actionError, setActionError] = useState(false);
  const requestRef = useRef<AbortController | null>(null);

  const loadStatistics = useCallback(() => {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setLoadState("loading");
    setLoadMessage(null);
    apiRequest<EmbeddingCacheStatistics>("/api/v1/admin/embedding-cache/stats", {
      signal: controller.signal,
    })
      .then((response) => {
        if (controller.signal.aborted) return;
        setStatistics(response);
        setLoadState("ready");
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return;
        if (caught instanceof ApiError && caught.status === 401) {
          expireSession();
          return;
        }
        setLoadMessage(caught instanceof ApiError ? caught.message : "Embedding 缓存统计加载失败");
        setLoadState("error");
      })
      .finally(() => {
        if (requestRef.current === controller) requestRef.current = null;
      });
  }, [expireSession]);

  useEffect(() => {
    loadStatistics();
    return () => requestRef.current?.abort();
  }, [loadStatistics]);

  const models = statistics?.models ?? [];
  const clearModels = [...new Map(
    models.map((model) => [modelNamespaceKey(model), model]),
  ).values()];
  const effectiveModelKey = clearModels.some((model) => modelNamespaceKey(model) === selectedModelKey)
    ? selectedModelKey
    : clearModels[0] ? modelNamespaceKey(clearModels[0]) : "";
  const selectedModel = clearModels.find(
    (model) => modelNamespaceKey(model) === effectiveModelKey,
  ) ?? null;

  async function clearCache(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedModel || confirmation !== "CLEAR" || !reason.trim()) return;

    setClearing(true);
    setActionMessage(null);
    setActionError(false);
    const request: ClearEmbeddingCacheRequest = {
      providerKey: selectedModel.providerKey,
      model: selectedModel.model,
      revision: selectedModel.revision,
      confirmation: "CLEAR",
      reason: reason.trim(),
    };
    try {
      const result = await apiRequest<ClearEmbeddingCacheResult>(
        "/api/v1/admin/embedding-cache/clear",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(request),
        },
      );
      setReason("");
      setConfirmation("");
      setActionMessage(
        `已删除 ${formatCount(result.deletedArtifacts)} 个 Artifact，`
        + `释放 ${formatBytes(result.freedBytes)}，失效 ${formatCount(result.invalidatedQueryEntries)} 个 Query 向量`,
      );
      loadStatistics();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        expireSession();
        return;
      }
      setActionMessage(caught instanceof ApiError ? caught.message : "Embedding 缓存清理失败");
      setActionError(true);
    } finally {
      setClearing(false);
    }
  }

  return (
    <section className="retrieval-panel embedding-cache-panel" aria-labelledby="embedding-cache-title">
      <header className="retrieval-panel-header">
        <div>
          <h2 id="embedding-cache-title">Embedding 缓存</h2>
          <p>仅复用向量计算；检索候选、权限、Rerank、Evidence 和回答不会被缓存。</p>
        </div>
        <button
          className="secondary-button"
          type="button"
          onClick={loadStatistics}
          disabled={loadState === "loading"}
        >
          刷新缓存统计
        </button>
      </header>

      {actionMessage ? (
        <p className={actionError ? "form-error" : "success-message"} role={actionError ? "alert" : "status"}>
          {actionMessage}
        </p>
      ) : null}
      {loadState === "loading" ? (
        <div className="inline-state" aria-live="polite">
          <span className="spinner" aria-hidden="true" />
          <span>正在读取 Embedding 缓存统计</span>
        </div>
      ) : null}
      {loadState === "error" ? (
        <div className="section-error" role="alert">
          <p>{loadMessage ?? "Embedding 缓存统计加载失败"}</p>
          <button className="secondary-button" type="button" onClick={loadStatistics}>重试</button>
        </div>
      ) : null}
      {loadState === "ready" && statistics ? (
        <>
          <div className="model-service-grid embedding-cache-summary">
            <article className="model-service-card">
              <header>
                <div><small>在线查询</small><strong>Query L1</strong></div>
                <span className="model-service-status up">内存</span>
              </header>
              <dl>
                <div><dt>条目</dt><dd>{formatCount(statistics.query.entries)} / {formatCount(statistics.query.maxEntries)}</dd></div>
                <div><dt>命中 / 未命中</dt><dd>{formatCount(statistics.query.hits)} / {formatCount(statistics.query.misses)}</dd></div>
                <div><dt>合并 / 淘汰</dt><dd>{formatCount(statistics.query.coalesced)} / {formatCount(statistics.query.evictions)}</dd></div>
                <div><dt>模型调用 / 节省</dt><dd>{formatCount(statistics.query.modelCalls)} / {formatCount(statistics.query.savedModelCalls)}</dd></div>
              </dl>
            </article>
            <article className="model-service-card">
              <header>
                <div><small>索引构建</small><strong>PostgreSQL Artifact</strong></div>
                <span className="model-service-status up">持久化</span>
              </header>
              <dl>
                <div><dt>条目</dt><dd>{formatCount(statistics.artifacts.entries)}</dd></div>
                <div><dt>容量</dt><dd>{formatBytes(statistics.artifacts.bytes)} / {formatBytes(statistics.artifacts.maxBytes)}</dd></div>
                <div><dt>命中 / 未命中</dt><dd>{formatCount(statistics.artifacts.hits)} / {formatCount(statistics.artifacts.misses)}</dd></div>
                <div><dt>模型调用 / 节省</dt><dd>{formatCount(statistics.artifacts.modelCalls)} / {formatCount(statistics.artifacts.savedModelCalls)}</dd></div>
                <div><dt>淘汰 / 损坏</dt><dd>{formatCount(statistics.artifacts.evictions)} / {formatCount(statistics.artifacts.corruptions)}</dd></div>
              </dl>
            </article>
          </div>

          <div className="table-wrap retrieval-config-table-wrap">
            <table className="data-table retrieval-config-table">
              <caption className="sr-only">按模型版本统计的 Embedding 缓存容量与条目</caption>
              <thead>
                <tr>
                  <th>Provider / 模型</th>
                  <th>Revision</th>
                  <th>维度</th>
                  <th>Query 条目</th>
                  <th>Artifact</th>
                  <th>容量</th>
                </tr>
              </thead>
              <tbody>
                {models.map((model) => (
                  <tr key={modelRowKey(model)}>
                    <td>{model.model}<small>{model.providerKey}</small></td>
                    <td><span title={model.revision}>{model.revision}</span></td>
                    <td>{model.dimensions}</td>
                    <td>{formatCount(model.queryEntries)}</td>
                    <td>{formatCount(model.artifactEntries)}</td>
                    <td>{formatBytes(model.artifactBytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {models.length === 0 ? <p className="empty-inline">当前没有可清理的模型缓存。</p> : null}
          </div>

          <details className="profile-create embedding-cache-clear">
            <summary>按模型与 Revision 清理缓存</summary>
            <form onSubmit={clearCache}>
              <label>
                <span>模型 / Revision</span>
                <select
                  aria-label="缓存模型与 Revision"
                  value={effectiveModelKey}
                  onChange={(event) => setSelectedModelKey(event.target.value)}
                  disabled={models.length === 0 || clearing}
                >
                  {clearModels.map((model) => (
                    <option key={modelNamespaceKey(model)} value={modelNamespaceKey(model)}>
                      {model.model} · {model.revision}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>审计理由</span>
                <input
                  value={reason}
                  maxLength={500}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="说明为什么需要清理"
                  disabled={clearing}
                  required
                />
              </label>
              <label>
                <span>确认字段</span>
                <input
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                  placeholder="输入 CLEAR"
                  disabled={clearing}
                  required
                />
              </label>
              <div className="profile-create-actions">
                <button
                  className="danger-button"
                  type="submit"
                  disabled={!selectedModel || !reason.trim() || confirmation !== "CLEAR" || clearing}
                >
                  {clearing ? "正在清理" : "清理缓存"}
                </button>
              </div>
            </form>
          </details>
        </>
      ) : null}
    </section>
  );
}
